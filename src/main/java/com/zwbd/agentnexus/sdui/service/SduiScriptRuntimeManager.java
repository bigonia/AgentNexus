package com.zwbd.agentnexus.sdui.service;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.zwbd.agentnexus.sdui.dto.SduiRuntimeWorkerStatus;
import com.zwbd.agentnexus.sdui.model.SduiAppVersion;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

@Slf4j
@Service
@RequiredArgsConstructor
public class SduiScriptRuntimeManager {

    private final ObjectMapper objectMapper;
    private final ObjectMapper protocolMapper = new ObjectMapper();
    private final AtomicInteger roundRobin = new AtomicInteger(0);
    private final List<PythonWorkerClient> workers = new ArrayList<>();

    @Value("${sdui.runtime.worker-count:2}")
    private int workerCount;

    @Value("${sdui.runtime.python-command:python}")
    private String pythonCommand;

    @Value("${sdui.runtime.request-timeout-ms:800}")
    private long requestTimeoutMs;

    private File workerScriptFile;

    @PostConstruct
    public void init() {
        try {
            workerScriptFile = extractWorkerScript();
            for (int i = 0; i < workerCount; i++) {
                workers.add(PythonWorkerClient.start("worker-" + i, pythonCommand, workerScriptFile, protocolMapper));
            }
            log.info("SDUI runtime initialized with {} python workers", workers.size());
        } catch (Exception e) {
            log.error("Failed to initialize python runtime workers. Runtime fallback mode enabled.", e);
            workers.clear();
        }
    }

    @PreDestroy
    public void destroy() {
        for (PythonWorkerClient worker : workers) {
            worker.close();
        }
        workers.clear();
    }

    public ScriptExecutionResult invokeOnStart(SduiAppVersion version, String deviceId, Map<String, Object> ctx) {
        return invoke(version, deviceId, "on_start", ctx, Map.of());
    }

    public ScriptExecutionResult invokeOnEvent(SduiAppVersion version, String deviceId, Map<String, Object> ctx, Map<String, Object> event) {
        return invoke(version, deviceId, "on_event", ctx, event);
    }

    public List<SduiRuntimeWorkerStatus> workerStatuses() {
        List<SduiRuntimeWorkerStatus> statusList = new ArrayList<>();
        for (PythonWorkerClient worker : workers) {
            statusList.add(new SduiRuntimeWorkerStatus(
                    worker.getWorkerId(),
                    worker.getState(),
                    worker.isHealthy(),
                    worker.getHandledRequests(),
                    worker.getLastError()
            ));
        }
        return statusList;
    }

    private ScriptExecutionResult invoke(SduiAppVersion version, String deviceId, String method, Map<String, Object> ctx, Map<String, Object> payload) {
        if (workers.isEmpty()) {
            return ScriptExecutionResult.error("runtime_unavailable");
        }

        PythonWorkerClient worker = selectWorker();
        RuntimeRequest request = new RuntimeRequest();
        request.setRequestId(UUID.randomUUID().toString());
        request.setAppId(version.getApp().getId());
        request.setAppVersion(version.getVersionNo());
        request.setScriptContent(version.getScriptContent());
        request.setDeviceId(deviceId);
        request.setMethod(method);
        request.setCtx(ctx);
        request.setPayload(payload);

        try {
            RuntimeResponse response = worker.invoke(request, Duration.ofMillis(requestTimeoutMs));
            if (!response.isOk()) {
                return ScriptExecutionResult.error(response.getError() == null ? "script_error" : response.getError());
            }
            JsonNode result = response.getResult();
            if (result == null || result.isNull()) {
                return ScriptExecutionResult.noop();
            }
            String action = result.path("action").asText("noop");
            String pageId = result.path("page_id").asText("home");
            JsonNode actionPayload = result.path("payload");
            return new ScriptExecutionResult(action, pageId, actionPayload, null, response.getStore());
        } catch (Exception e) {
            log.warn("Worker execution failed for device {} method {}", deviceId, method, e);
            return ScriptExecutionResult.error("runtime_exception:" + e.getMessage());
        }
    }

    private PythonWorkerClient selectWorker() {
        int index = Math.abs(roundRobin.getAndIncrement() % workers.size());
        return workers.get(index);
    }

    private File extractWorkerScript() throws IOException {
        ClassPathResource resource = new ClassPathResource("sdui/python_worker.py");
        String content = resource.getContentAsString(StandardCharsets.UTF_8);
        File tempFile = Files.createTempFile("sdui-python-worker", ".py").toFile();
        Files.writeString(tempFile.toPath(), content, StandardCharsets.UTF_8);
        tempFile.deleteOnExit();
        return tempFile;
    }

    @Data
    @JsonInclude(JsonInclude.Include.NON_NULL)
    private static class RuntimeRequest {
        @JsonProperty("request_id")
        private String requestId;
        @JsonProperty("app_id")
        private String appId;
        @JsonProperty("app_version")
        private Integer appVersion;
        @JsonProperty("script_content")
        private String scriptContent;
        @JsonProperty("device_id")
        private String deviceId;
        private String method;
        private Map<String, Object> ctx;
        private Map<String, Object> payload;
    }

    @Data
    private static class RuntimeResponse {
        @JsonProperty("request_id")
        private String requestId;
        private boolean ok;
        private JsonNode result;
        private String error;
        private Map<String, Object> store;
    }

    public record ScriptExecutionResult(
            String action,
            String pageId,
            JsonNode payload,
            String error,
            Map<String, Object> store
    ) {
        public static ScriptExecutionResult noop() {
            return new ScriptExecutionResult("noop", "home", null, null, null);
        }

        public static ScriptExecutionResult error(String error) {
            return new ScriptExecutionResult("error", "home", null, error, null);
        }
    }

    @Slf4j
    private static class PythonWorkerClient {
        private final String workerId;
        private final ObjectMapper objectMapper;
        private final Process process;
        private final BufferedWriter writer;
        private final BufferedReader reader;
        private final AtomicLong handledRequests = new AtomicLong(0);
        private volatile String state = "READY";
        private volatile String lastError = "";

        private PythonWorkerClient(String workerId, ObjectMapper objectMapper, Process process,
                                   BufferedWriter writer, BufferedReader reader) {
            this.workerId = workerId;
            this.objectMapper = objectMapper;
            this.process = process;
            this.writer = writer;
            this.reader = reader;
        }

        static PythonWorkerClient start(String workerId, String pythonCommand, File scriptFile, ObjectMapper objectMapper) throws IOException {
            ProcessBuilder pb = new ProcessBuilder(pythonCommand, "-X", "utf8", "-u", scriptFile.getAbsolutePath());
            pb.environment().put("PYTHONUTF8", "1");
            pb.environment().put("PYTHONIOENCODING", "UTF-8");
            pb.redirectErrorStream(true);
            Process process = pb.start();
            BufferedWriter writer = new BufferedWriter(new OutputStreamWriter(process.getOutputStream(), StandardCharsets.UTF_8));
            BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8));
            return new PythonWorkerClient(workerId, objectMapper, process, writer, reader);
        }

        synchronized RuntimeResponse invoke(RuntimeRequest request, Duration timeout) throws IOException {
            if (!process.isAlive()) {
                state = "DEAD";
                lastError = "process dead";
                throw new IOException("worker process is dead: " + workerId);
            }
            state = "BUSY";
            // Protocol is line-delimited JSON; force compact one-line payload
            // to avoid global pretty-print settings breaking worker json.loads(line).
            String requestJson = objectMapper.writer()
                    .without(SerializationFeature.INDENT_OUTPUT)
                    .writeValueAsString(request);
            log.debug("Dispatch runtime request. workerId={}, requestId={}, bytes={}",
                    workerId, request.getRequestId(), requestJson.getBytes(StandardCharsets.UTF_8).length);
            writer.write(requestJson);
            writer.newLine();
            writer.flush();

            long deadline = System.currentTimeMillis() + timeout.toMillis();
            String line;
            while (System.currentTimeMillis() < deadline) {
                if (reader.ready() && (line = reader.readLine()) != null) {
                    handledRequests.incrementAndGet();
                    state = "READY";
                    return objectMapper.readValue(line, RuntimeResponse.class);
                }
                try {
                    Thread.sleep(10L);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
            state = "UNHEALTHY";
            lastError = "timeout";
            throw new IOException("worker timeout: " + workerId);
        }

        void close() {
            try {
                writer.close();
                reader.close();
            } catch (Exception ignored) {
            }
            process.destroy();
        }

        String getWorkerId() {
            return workerId;
        }

        String getState() {
            return state;
        }

        boolean isHealthy() {
            return process.isAlive() && !"UNHEALTHY".equals(state);
        }

        long getHandledRequests() {
            return handledRequests.get();
        }

        String getLastError() {
            return lastError;
        }
    }
}
