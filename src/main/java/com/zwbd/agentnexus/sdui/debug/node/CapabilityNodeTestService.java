package com.zwbd.agentnexus.sdui.debug.node;

import com.zwbd.agentnexus.sdui.DeviceSessionManager;
import com.zwbd.agentnexus.sdui.debug.DebugArtifactStore;
import com.zwbd.agentnexus.sdui.dto.SduiControlDispatchResult;
import com.zwbd.agentnexus.sdui.event.EventPayload;
import com.zwbd.agentnexus.sdui.handler.EventInputHandler;
import com.zwbd.agentnexus.sdui.service.CommandService;
import com.zwbd.agentnexus.sdui.service.AudioService;
import com.zwbd.agentnexus.sdui.service.audio.AudioRecordSessionManager;
import com.zwbd.agentnexus.sdui.workflow.CapabilityNodeExecutorService;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class CapabilityNodeTestService implements EventInputHandler.PayloadEventListener {

    private static final long DEFAULT_TIMEOUT_MS = 30_000L;
    private static final long MAX_TIMEOUT_MS = 300_000L;
    private static final String AUDIO_RECORD_ARTIFACT_ID = "audio-record-latest";

    private final DeviceSessionManager sessionManager;
    private final CommandService commandService;
    private final AudioService audioService;
    private final AudioRecordSessionManager audioRecordSessionManager;
    private final DebugArtifactStore artifactStore;
    private final CapabilityNodeExecutorService executorService;
    private final Map<String, NodeTestHandle> tests = new ConcurrentHashMap<>();

    public CapabilityNodeTestService(EventInputHandler eventInputHandler,
                                     DeviceSessionManager sessionManager,
                                     CommandService commandService,
                                     AudioService audioService,
                                     AudioRecordSessionManager audioRecordSessionManager,
                                     DebugArtifactStore artifactStore,
                                     CapabilityNodeExecutorService executorService) {
        this.sessionManager = sessionManager;
        this.commandService = commandService;
        this.audioService = audioService;
        this.audioRecordSessionManager = audioRecordSessionManager;
        this.artifactStore = artifactStore;
        this.executorService = executorService;
        eventInputHandler.addPayloadListener(this);
    }

    public Map<String, Object> createInputTest(String deviceId, Map<String, Object> body) {
        if (!sessionManager.isDeviceOnline(deviceId)) {
            throw new IllegalArgumentException("device is offline");
        }
        String eventId = string(body.get("eventId"));
        if (eventId.isBlank()) {
            throw new IllegalArgumentException("eventId is required");
        }
        long timeoutMs = normalizeTimeout(body.get("timeoutMs"));
        String testId = UUID.randomUUID().toString();
        long now = System.currentTimeMillis();
        NodeTestHandle handle = new NodeTestHandle(
                testId,
                deviceId,
                string(body.get("nodeType")),
                string(body.get("capabilityId")),
                eventId,
                string(body.get("nodeId")),
                "waiting",
                now,
                now + timeoutMs,
                null,
                null
        );
        tests.put(testId, handle);
        return handle.toMap();
    }

    public Optional<Map<String, Object>> getTest(String deviceId, String testId) {
        NodeTestHandle handle = tests.get(testId);
        if (handle == null || !deviceId.equals(handle.deviceId())) {
            return Optional.empty();
        }
        return Optional.of(refresh(handle).toMap());
    }

    public Map<String, Object> executeOutputTest(String deviceId, Map<String, Object> body) {
        String nodeType = string(body.get("nodeType"));
        Map<String, Object> params = normalizeMap(body.get("params"));
        return executorService.execute(deviceId, nodeType, params);
    }

    @Override
    public void onEvent(EventPayload payload) {
        if (payload == null || payload.deviceId() == null) {
            return;
        }
        for (NodeTestHandle handle : tests.values()) {
            NodeTestHandle current = refresh(handle);
            if (!"waiting".equals(current.status())) {
                continue;
            }
            if (!payload.deviceId().equals(current.deviceId())) {
                continue;
            }
            if (!eventMatches(current.eventId(), payload.eventId())) {
                continue;
            }
            if (!current.nodeId().isBlank() && !current.nodeId().equals(payload.nodeId())) {
                continue;
            }

            Map<String, Object> event = new LinkedHashMap<>(payload.toLegacyMap());
            event.put("eventId", payload.eventId());
            event.put("deviceId", payload.deviceId());
            tests.put(current.testId(), current.withStatus("passed", event, null));
        }
    }

    private Map<String, Object> executeAudioPlay(String deviceId, Map<String, Object> params) {
        if (hasText(params, "artifact_id")) {
            return playArtifact(deviceId, string(params.get("artifact_id")));
        }
        if (hasText(params, "audio_file")) {
            return playArtifact(deviceId, string(params.get("audio_file")));
        }
        if (hasText(params, "text")) {
            return dispatchCommand(deviceId, "audio.tts.speak", Map.of("text", string(params.get("text"))), "audio.play");
        }
        String preset = hasText(params, "preset") ? string(params.get("preset")) : "notification";
        return dispatchCommand(deviceId, "audio.prompt.play", Map.of("preset", preset), "audio.play");
    }

    private Map<String, Object> executeRgbEffect(String deviceId, Map<String, Object> params) {
        boolean off = booleanParam(params.get("off"));
        String mode = string(params.get("mode"));
        if (off || "off".equals(mode)) {
            return dispatchCommand(deviceId, "rgb.off", Map.of(), "rgb.effect");
        }
        return dispatchCommand(deviceId, "rgb.effect.set", params, "rgb.effect");
    }

    private Map<String, Object> playArtifact(String deviceId, String artifactRef) {
        String artifactId = normalizeArtifactId(artifactRef);
        Optional<DebugArtifactStore.Artifact> artifact = artifactStore.get(deviceId, artifactId);
        if (artifact.isEmpty() || artifact.get().blob() == null) {
            return Map.of(
                    "deviceId", deviceId,
                    "nodeType", "audio.play",
                    "sent", false,
                    "status", "artifact_not_found",
                    "artifactId", artifactId
            );
        }
        AudioService.PlayResult result = audioService.playWav(deviceId, artifact.get().blob());
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("deviceId", deviceId);
        response.put("nodeType", "audio.play");
        response.put("command", "audio.artifact.play");
        response.put("artifactId", artifactId);
        response.put("sent", result.sent());
        response.put("samples", result.samples());
        response.put("durationMs", result.durationMs());
        response.put("status", result.sent() ? "sent" : "send_failed");
        response.put("artifact", artifactToMap(deviceId, artifact.get()));
        return response;
    }

    private Map<String, Object> executeAudioRecord(String deviceId, Map<String, Object> params) {
        String control = string(params.getOrDefault("control", params.getOrDefault("action", "toggle")));
        if (control.isBlank()) {
            control = "toggle";
        }
        String command = switch (control) {
            case "start" -> "audio.record.start";
            case "stop" -> "audio.record.stop";
            case "toggle" -> audioRecordSessionManager.isRecording(deviceId)
                    ? "audio.record.stop" : "audio.record.start";
            default -> throw new IllegalArgumentException("invalid audio.record control: " + control);
        };

        if ("audio.record.stop".equals(command) && !sessionManager.isDeviceOnline(deviceId)) {
            audioRecordSessionManager.requestStopOnReconnect(deviceId, "node_test_offline");
            Map<String, Object> response = new LinkedHashMap<>();
            response.put("deviceId", deviceId);
            response.put("nodeType", "audio.record");
            response.put("command", command);
            response.put("sent", false);
            response.put("ackStatus", "PENDING_RECONNECT");
            response.put("status", "pending_reconnect");
            response.put("control", control);
            response.put("recording", true);
            response.put("pendingStop", true);
            return response;
        }

        Map<String, Object> response = dispatchCommand(deviceId, command, Map.of(), "audio.record");
        response.put("control", control);
        response.put("recording", audioRecordSessionManager.isRecording(deviceId));
        artifactStore.get(deviceId, AUDIO_RECORD_ARTIFACT_ID).ifPresent(artifact ->
                response.put("artifact", artifactToMap(deviceId, artifact)));
        return response;
    }

    private boolean canDeferAudioRecordStop(String deviceId, String nodeType, Map<String, Object> params) {
        if (!"audio.record".equals(nodeType) || !audioRecordSessionManager.isRecording(deviceId)) {
            return false;
        }
        String control = string(params.getOrDefault("control", params.getOrDefault("action", "toggle")));
        return "stop".equals(control) || "toggle".equals(control) || control.isBlank();
    }

    private Map<String, Object> dispatchCommand(String deviceId, String command,
                                                Map<String, Object> params, String nodeType) {
        SduiControlDispatchResult result = commandService.dispatchCommand(deviceId, command, params);
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("deviceId", deviceId);
        response.put("nodeType", nodeType);
        response.put("command", command);
        response.put("params", params);
        response.put("cmdId", result.cmdId());
        response.put("action", result.action());
        response.put("sent", result.sent());
        response.put("ackStatus", result.status());
        response.put("status", result.sent() ? "sent" : "send_failed");
        return response;
    }

    private NodeTestHandle refresh(NodeTestHandle handle) {
        if ("waiting".equals(handle.status()) && System.currentTimeMillis() > handle.expiresAt()) {
            NodeTestHandle timeout = handle.withStatus("timeout", null, "timeout waiting for input event");
            tests.put(handle.testId(), timeout);
            return timeout;
        }
        return handle;
    }

    private boolean eventMatches(String expected, String actual) {
        if (Objects.equals(expected, actual)) {
            return true;
        }
        if (expected == null || actual == null || expected.isBlank() || actual.isBlank()) {
            return false;
        }
        return expected.endsWith("." + actual);
    }

    private Map<String, Object> artifactToMap(String deviceId, DebugArtifactStore.Artifact artifact) {
        Map<String, Object> data = new LinkedHashMap<>(artifact.metadata());
        data.put("artifactId", artifact.artifactId());
        data.put("artifactRef", "artifact:" + deviceId + ":" + artifact.artifactId());
        data.put("audioFile", "/api/v1/sdui/debug/" + deviceId + "/artifacts/" + artifact.artifactId() + "/blob");
        data.put("mimeType", artifact.mimeType());
        data.put("blobBytes", artifact.blob() != null ? artifact.blob().length : 0);
        data.put("createdAt", artifact.createdAt());
        data.put("createdAtIso", Instant.ofEpochMilli(artifact.createdAt()).toString());
        return data;
    }

    private long normalizeTimeout(Object raw) {
        long value = DEFAULT_TIMEOUT_MS;
        if (raw instanceof Number n) {
            value = n.longValue();
        } else if (raw instanceof String s && !s.isBlank()) {
            value = Long.parseLong(s);
        }
        return Math.max(1_000L, Math.min(value, MAX_TIMEOUT_MS));
    }

    private boolean hasText(Map<String, Object> params, String key) {
        return !string(params.get(key)).isBlank();
    }

    private boolean booleanParam(Object value) {
        if (value instanceof Boolean b) return b;
        if (value instanceof Number n) return n.intValue() != 0;
        if (value instanceof String s) return "true".equalsIgnoreCase(s) || "1".equals(s) || "on".equalsIgnoreCase(s);
        return false;
    }

    private String normalizeArtifactId(String value) {
        if (value == null || value.isBlank()) {
            return AUDIO_RECORD_ARTIFACT_ID;
        }
        String trimmed = value.trim();
        if (trimmed.startsWith("artifact:")) {
            String[] parts = trimmed.split(":");
            return parts.length >= 3 ? parts[2] : trimmed;
        }
        int slash = trimmed.lastIndexOf('/');
        return slash >= 0 ? trimmed.substring(slash + 1) : trimmed;
    }

    private String string(Object value) {
        return value == null ? "" : String.valueOf(value);
    }

    private Map<String, Object> normalizeMap(Object value) {
        Map<String, Object> normalized = new LinkedHashMap<>();
        if (value instanceof Map<?, ?> map) {
            for (var entry : map.entrySet()) {
                normalized.put(String.valueOf(entry.getKey()), entry.getValue());
            }
        }
        return normalized;
    }

    private record NodeTestHandle(
            String testId,
            String deviceId,
            String nodeType,
            String capabilityId,
            String eventId,
            String nodeId,
            String status,
            long createdAt,
            long expiresAt,
            Map<String, Object> matchedEvent,
            String reason
    ) {
        NodeTestHandle withStatus(String status, Map<String, Object> matchedEvent, String reason) {
            return new NodeTestHandle(testId, deviceId, nodeType, capabilityId, eventId, nodeId,
                    status, createdAt, expiresAt, matchedEvent, reason);
        }

        Map<String, Object> toMap() {
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("testId", testId);
            map.put("deviceId", deviceId);
            map.put("nodeType", nodeType);
            map.put("capabilityId", capabilityId);
            map.put("eventId", eventId);
            if (!nodeId.isBlank()) map.put("nodeId", nodeId);
            map.put("status", status);
            map.put("createdAt", createdAt);
            map.put("createdAtIso", Instant.ofEpochMilli(createdAt).toString());
            map.put("expiresAt", expiresAt);
            map.put("expiresAtIso", Instant.ofEpochMilli(expiresAt).toString());
            if (matchedEvent != null) map.put("matchedEvent", matchedEvent);
            if (reason != null) map.put("reason", reason);
            return map;
        }
    }
}
