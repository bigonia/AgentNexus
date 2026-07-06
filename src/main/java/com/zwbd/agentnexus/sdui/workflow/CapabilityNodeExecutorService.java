package com.zwbd.agentnexus.sdui.workflow;

import com.zwbd.agentnexus.sdui.DeviceSessionManager;
import com.zwbd.agentnexus.sdui.artifact.SduiArtifactEntity;
import com.zwbd.agentnexus.sdui.artifact.SduiArtifactService;
import com.zwbd.agentnexus.sdui.dto.SduiControlDispatchResult;
import com.zwbd.agentnexus.sdui.section.*;
import com.zwbd.agentnexus.sdui.service.AudioService;
import com.zwbd.agentnexus.sdui.service.CommandService;
import com.zwbd.agentnexus.sdui.service.audio.AudioRecordHandler;
import com.zwbd.agentnexus.sdui.service.audio.AudioRecordSessionManager;
import com.zwbd.agentnexus.sdui.ui.WorkflowUiContextService;
import org.springframework.stereotype.Service;

import java.util.*;

@Service
public class CapabilityNodeExecutorService {

    private static final String AUDIO_RECORD_ARTIFACT_ID = "audio-record-latest";
    private static final long AUDIO_RECORD_STOP_ACK_TIMEOUT_MS = 2_000L;
    private static final int AUDIO_RECORD_STOP_MAX_ATTEMPTS = 3;
    private static final long AUDIO_RECORD_STOP_EVENT_TIMEOUT_MS = 10_000L;
    private static final long AUDIO_RECORD_ARTIFACT_READY_TIMEOUT_MS = 5_000L;
    private static final long AUDIO_RECORD_ARTIFACT_POLL_MS = 150L;

    private final DeviceSessionManager sessionManager;
    private final CommandService commandService;
    private final AudioService audioService;
    private final AudioRecordSessionManager audioRecordSessionManager;
    private final SduiArtifactService artifactService;
    private final SectionOrchestrationService sectionOrchestrationService;
    private final SectionDataCodec sectionDataCodec;
    private final WorkflowUiContextService workflowUiContextService;
    private final SectionTypeCatalog sectionTypeCatalog;

    public CapabilityNodeExecutorService(DeviceSessionManager sessionManager,
                                         CommandService commandService,
                                         AudioService audioService,
                                         AudioRecordSessionManager audioRecordSessionManager,
                                         SduiArtifactService artifactService,
                                         SectionOrchestrationService sectionOrchestrationService,
                                         SectionDataCodec sectionDataCodec,
                                         WorkflowUiContextService workflowUiContextService,
                                         SectionTypeCatalog sectionTypeCatalog) {
        this.sessionManager = sessionManager;
        this.commandService = commandService;
        this.audioService = audioService;
        this.audioRecordSessionManager = audioRecordSessionManager;
        this.artifactService = artifactService;
        this.sectionOrchestrationService = sectionOrchestrationService;
        this.sectionDataCodec = sectionDataCodec;
        this.workflowUiContextService = workflowUiContextService;
        this.sectionTypeCatalog = sectionTypeCatalog;
    }

    public Map<String, Object> execute(String deviceId, String nodeType, Map<String, Object> params) {
        return execute(deviceId, nodeType, params, Map.of());
    }

    public Map<String, Object> execute(String deviceId, String nodeType, Map<String, Object> params,
                                       Map<String, Object> executionContext) {
        Map<String, Object> normalizedParams = normalizeMap(params);
        if (!sessionManager.isDeviceOnline(deviceId)
                && !canDeferAudioRecordStop(deviceId, nodeType, normalizedParams)) {
            throw new IllegalArgumentException("device is offline");
        }
        return switch (nodeType) {
            case "rgb.effect" -> executeRgbEffect(deviceId, normalizedParams);
            case "audio.play" -> executeAudioPlay(deviceId, normalizedParams);
            case "audio.record" -> executeAudioRecord(deviceId, normalizedParams);
            case "ui.update", "display.section" -> executeUiUpdate(deviceId, nodeType, normalizedParams, executionContext);
            default -> throw new IllegalArgumentException("unsupported output nodeType: " + nodeType);
        };
    }

    private Map<String, Object> executeAudioPlay(String deviceId, Map<String, Object> params) {
        if (hasText(params, "artifact_id")) {
            return playArtifact(deviceId, string(params.get("artifact_id")));
        }
        if (params.containsKey("artifact_id")) {
            return skipped(deviceId, "audio.play", "artifact not yet available");
        }
        if (hasText(params, "audio_file")) {
            return playArtifact(deviceId, string(params.get("audio_file")));
        }
        if (params.containsKey("audio_file")) {
            return skipped(deviceId, "audio.play", "audio file not available");
        }
        if (hasText(params, "text")) {
            return dispatchCommand(deviceId, "audio.tts.speak", Map.of("text", string(params.get("text"))), "audio.play");
        }
        String preset = hasText(params, "preset") ? string(params.get("preset")) : "notification";
        return dispatchCommand(deviceId, "audio.prompt.play", Map.of("preset", preset), "audio.play");
    }

    private Map<String, Object> skipped(String deviceId, String nodeType, String reason) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("deviceId", deviceId);
        result.put("nodeType", nodeType);
        result.put("status", "skipped");
        result.put("reason", reason);
        return result;
    }

    private Map<String, Object> executeRgbEffect(String deviceId, Map<String, Object> params) {
        boolean off = booleanParam(params.get("off"));
        String mode = string(params.get("mode"));
        if (off || "off".equals(mode)) {
            return dispatchCommand(deviceId, "rgb.off", Map.of(), "rgb.effect");
        }
        return dispatchCommand(deviceId, "rgb.effect.set", params, "rgb.effect");
    }

    private Map<String, Object> executeAudioRecord(String deviceId, Map<String, Object> params) {
        String control = string(params.getOrDefault("control", params.getOrDefault("action", "toggle")));
        if (control.isBlank()) {
            control = "toggle";
        }
        boolean wasRecording = audioRecordSessionManager.isRecording(deviceId);
        String command = switch (control) {
            case "start" -> "audio.record.start";
            case "stop" -> "audio.record.stop";
            case "toggle" -> wasRecording ? "audio.record.stop" : "audio.record.start";
            default -> throw new IllegalArgumentException("invalid audio.record control: " + control);
        };

        if ("audio.record.stop".equals(command) && !sessionManager.isDeviceOnline(deviceId)) {
            audioRecordSessionManager.requestStopOnReconnect(deviceId, "node_workflow_offline");
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

        String beforeArtifactId = artifactService.findLatest(deviceId, SduiArtifactService.AUDIO_RECORDING)
                .map(SduiArtifactEntity::getArtifactId)
                .orElse("");
        if ("audio.record.stop".equals(command)) {
            return executeAudioRecordStop(deviceId, control, beforeArtifactId);
        }

        Map<String, Object> response = dispatchCommand(deviceId, command, Map.of(), "audio.record");
        response.put("control", control);
        response.put("recording", audioRecordSessionManager.isRecording(deviceId));
        artifactService.findLatest(deviceId, SduiArtifactService.AUDIO_RECORDING).ifPresent(artifact ->
                response.put("artifact", artifactService.toMap(artifact)));
        return response;
    }

    private Map<String, Object> executeAudioRecordStop(String deviceId, String control, String beforeArtifactId) {
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("deviceId", deviceId);
        response.put("nodeType", "audio.record");
        response.put("command", "audio.record.stop");
        response.put("control", control);

        List<Map<String, Object>> attempts = new ArrayList<>();
        Map<String, Object> lastDispatch = Map.of();
        boolean acked = false;
        for (int attempt = 1; attempt <= AUDIO_RECORD_STOP_MAX_ATTEMPTS; attempt++) {
            lastDispatch = dispatchCommand(deviceId, "audio.record.stop", Map.of(), "audio.record");
            String cmdId = string(lastDispatch.get("cmdId"));
            String ackStatus = cmdId.isBlank()
                    ? "NO_CMD_ID"
                    : commandService.waitForControlAck(deviceId, cmdId, AUDIO_RECORD_STOP_ACK_TIMEOUT_MS);
            attempts.add(Map.of(
                    "attempt", attempt,
                    "cmdId", cmdId,
                    "sent", lastDispatch.get("sent"),
                    "ackStatus", ackStatus
            ));
            if ("ACKED".equals(ackStatus)) {
                acked = true;
                break;
            }
            if (!Boolean.TRUE.equals(lastDispatch.get("sent"))) {
                break;
            }
        }

        response.putAll(lastDispatch);
        response.put("control", control);
        response.put("attempts", attempts);

        if (acked) {
            boolean stopped = waitForRecordingSessionEnd(deviceId, AUDIO_RECORD_STOP_EVENT_TIMEOUT_MS);
            Optional<SduiArtifactEntity> artifact = waitForNewAudioRecordArtifact(deviceId, beforeArtifactId);
            if (artifact.isPresent()) {
                response.put("recording", false);
                response.put("artifact", artifactService.toMap(artifact.get()));
                return response;
            }
            return forceFinalizeAudioRecord(deviceId, beforeArtifactId,
                    stopped ? "artifact_timeout_after_stop_event" : "stop_event_timeout_after_ack",
                    response);
        }

        return forceFinalizeAudioRecord(deviceId, beforeArtifactId, "stop_ack_timeout", response);
    }

    private Map<String, Object> executeUiUpdate(String deviceId, String nodeType, Map<String, Object> params,
                                                Map<String, Object> executionContext) {
        if (params.containsKey("variableKey")) {
            String workflowId = string(executionContext.get("workflowId"));
            String deploymentId = string(executionContext.get("deploymentId"));
            if (workflowId.isBlank() || deploymentId.isBlank()) {
                throw new IllegalArgumentException("ui variable update requires workflow execution context");
            }
            Map<String, Object> enriched = new LinkedHashMap<>(params);
            enriched.putIfAbsent("slotId", executionContext.get("slotId"));
            return workflowUiContextService.updateVariable(workflowId, deploymentId, deviceId, enriched);
        }
        if (params.get("scene") instanceof Map<?, ?> rawScene) {
            SectionScene scene = toScene(normalizeMap(rawScene));
            boolean sent = sectionOrchestrationService.sendScene(deviceId, scene);
            return uiResponse(deviceId, nodeType, "scene", scene.pageId(), sent);
        }
        if (params.get("patch") instanceof Map<?, ?> rawPatch) {
            SectionPatch patch = toPatch(normalizeMap(rawPatch));
            boolean sent = sectionOrchestrationService.sendPatch(deviceId, patch);
            return uiResponse(deviceId, nodeType, "patch", patch.pageId(), sent);
        }
        throw new IllegalArgumentException("ui node requires scene or patch params");
    }

    private Map<String, Object> uiResponse(String deviceId, String nodeType, String operation, String pageId, boolean sent) {
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("deviceId", deviceId);
        response.put("nodeType", nodeType);
        response.put("operation", operation);
        response.put("pageId", pageId);
        response.put("sent", sent);
        response.put("status", sent ? "sent" : "send_failed");
        return response;
    }

    private SectionScene toScene(Map<String, Object> raw) {
        String pageId = string(raw.getOrDefault("pageId", raw.getOrDefault("page", "main")));
        SectionLayout layout = SectionLayout.fromWireName(string(raw.getOrDefault("layout", "vertical_scroll")));
        boolean autoScroll = booleanParam(raw.get("autoScroll"));
        int autoScrollMs = intParam(raw.get("autoScrollMs"), 0);
        List<SectionEntry> sections = new ArrayList<>();
        Object rawSections = raw.get("sections");
        if (rawSections instanceof List<?> list) {
            for (Object item : list) {
                if (item instanceof Map<?, ?> map) {
                    sections.add(toSectionEntry(normalizeMap(map)));
                }
            }
        }
        if (sections.isEmpty()) {
            throw new IllegalArgumentException("scene.sections is required");
        }
        return new SectionScene(pageId, layout, autoScroll, autoScrollMs, sections);
    }

    private SectionPatch toPatch(Map<String, Object> raw) {
        String pageId = string(raw.getOrDefault("pageId", raw.getOrDefault("page", "main")));
        List<SectionPatch.PatchEntry> entries = new ArrayList<>();
        Object rawPatches = raw.get("patches");
        if (rawPatches instanceof List<?> list) {
            for (Object item : list) {
                if (item instanceof Map<?, ?> map) {
                    entries.add(toPatchEntry(normalizeMap(map)));
                }
            }
        }
        if (entries.isEmpty()) {
            throw new IllegalArgumentException("patch.patches is required");
        }
        return new SectionPatch(pageId, entries);
    }

    private SectionEntry toSectionEntry(Map<String, Object> raw) {
        String sectionId = string(raw.getOrDefault("sectionId", raw.getOrDefault("id", "")));
        if (sectionId.isBlank()) {
            throw new IllegalArgumentException("sectionId is required");
        }
        String typeName = string(raw.getOrDefault("sectionType", raw.getOrDefault("type", "")));
        if (!sectionTypeCatalog.isValidType(typeName)) {
            throw new IllegalArgumentException("unsupported section type: " + typeName);
        }
        Map<String, Object> fields = raw.get("fields") instanceof Map<?, ?> map ? normalizeMap(map) : Map.of();
        SectionData data = sectionDataCodec.buildSectionData(typeName, fields, sectionId);
        if (data == null) {
            throw new IllegalArgumentException("invalid section fields for: " + typeName);
        }
        return new SectionEntry(typeName, sectionId, data);
    }

    private SectionPatch.PatchEntry toPatchEntry(Map<String, Object> raw) {
        String op = string(raw.getOrDefault("op", "update"));
        String sectionId = string(raw.getOrDefault("sectionId", raw.getOrDefault("id", "")));
        if (sectionId.isBlank()) {
            throw new IllegalArgumentException("patch sectionId is required");
        }
        if ("remove".equals(op)) {
            return new SectionPatch.PatchEntry(sectionId, op, null, null);
        }
        String typeName = string(raw.getOrDefault("sectionType", raw.getOrDefault("type", "")));
        if (!sectionTypeCatalog.isValidType(typeName)) {
            throw new IllegalArgumentException("unsupported patch section type: " + typeName);
        }
        Map<String, Object> fields = raw.get("fields") instanceof Map<?, ?> map ? normalizeMap(map) : Map.of();
        SectionData data = sectionDataCodec.buildSectionData(typeName, fields, sectionId);
        if (data == null) {
            throw new IllegalArgumentException("invalid patch section fields for: " + typeName);
        }
        return new SectionPatch.PatchEntry(sectionId, op, typeName, data);
    }

    private Map<String, Object> playArtifact(String deviceId, String artifactRef) {
        SduiArtifactService.ResolvedArtifact resolved = artifactService.resolve(deviceId, artifactRef);
        String requestedArtifactId = resolved != null ? resolved.requestedArtifactId() : normalizeArtifactId(artifactRef);
        if (resolved == null) {
            return Map.of(
                    "deviceId", deviceId,
                    "nodeType", "audio.play",
                    "sent", false,
                    "status", "artifact_not_found",
                    "artifactId", requestedArtifactId
            );
        }
        SduiArtifactEntity artifact = resolved.artifact();
        if (artifact.getBlob() == null) {
            return Map.of(
                    "deviceId", deviceId,
                    "nodeType", "audio.play",
                    "sent", false,
                    "status", "artifact_blob_missing",
                    "artifactId", artifact.getArtifactId()
            );
        }
        AudioService.PlayResult result = audioService.playWav(deviceId, artifact.getBlob());
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("deviceId", deviceId);
        response.put("nodeType", "audio.play");
        response.put("command", "audio.artifact.play");
        response.put("artifactId", artifact.getArtifactId());
        response.put("sent", result.sent());
        response.put("samples", result.samples());
        response.put("durationMs", result.durationMs());
        response.put("status", result.sent() ? "sent" : "send_failed");
        response.put("artifact", artifactService.toMap(artifact));
        return response;
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

    private boolean canDeferAudioRecordStop(String deviceId, String nodeType, Map<String, Object> params) {
        if (!"audio.record".equals(nodeType) || !audioRecordSessionManager.isRecording(deviceId)) {
            return false;
        }
        String control = string(params.getOrDefault("control", params.getOrDefault("action", "toggle")));
        return "stop".equals(control) || "toggle".equals(control) || control.isBlank();
    }

    private boolean waitForRecordingSessionEnd(String deviceId, long timeoutMs) {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() <= deadline) {
            if (!audioRecordSessionManager.isRecording(deviceId)) {
                return true;
            }
            try {
                Thread.sleep(AUDIO_RECORD_ARTIFACT_POLL_MS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
        return !audioRecordSessionManager.isRecording(deviceId);
    }

    private Optional<SduiArtifactEntity> waitForNewAudioRecordArtifact(String deviceId, String beforeArtifactId) {
        long deadline = System.currentTimeMillis() + AUDIO_RECORD_ARTIFACT_READY_TIMEOUT_MS;
        while (System.currentTimeMillis() <= deadline) {
            Optional<SduiArtifactEntity> latest = artifactService.findLatest(deviceId, SduiArtifactService.AUDIO_RECORDING);
            if (latest.isPresent() && !latest.get().getArtifactId().equals(beforeArtifactId)) {
                return latest;
            }
            try {
                Thread.sleep(AUDIO_RECORD_ARTIFACT_POLL_MS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return Optional.empty();
            }
        }
        return Optional.empty();
    }

    private Map<String, Object> forceFinalizeAudioRecord(String deviceId,
                                                         String beforeArtifactId,
                                                         String reason,
                                                         Map<String, Object> response) {
        Optional<SduiArtifactEntity> existing = waitForNewAudioRecordArtifact(deviceId, beforeArtifactId);
        if (existing.isPresent()) {
            response.put("recording", false);
            response.put("artifact", artifactService.toMap(existing.get()));
            return response;
        }

        byte[] pcm = audioRecordSessionManager.stopSession(deviceId);
        if (pcm == null || pcm.length == 0) {
            response.put("recording", false);
            response.put("sent", false);
            response.put("status", "record_stop_failed_no_audio");
            response.put("reason", reason);
            return response;
        }

        byte[] wav = AudioRecordHandler.pcmToWav(pcm,
                AudioRecordHandler.PCM_SAMPLE_RATE,
                AudioRecordHandler.PCM_CHANNELS,
                AudioRecordHandler.PCM_BITS_PER_SAMPLE);
        int durationMs = (int) ((long) pcm.length * 1000
                / (AudioRecordHandler.PCM_SAMPLE_RATE
                * AudioRecordHandler.PCM_CHANNELS
                * AudioRecordHandler.PCM_BITS_PER_SAMPLE / 8));
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("text", "");
        metadata.put("sttText", "");
        metadata.put("pcmSize", pcm.length);
        metadata.put("sampleRate", AudioRecordHandler.PCM_SAMPLE_RATE);
        metadata.put("channels", AudioRecordHandler.PCM_CHANNELS);
        metadata.put("bitsPerSample", AudioRecordHandler.PCM_BITS_PER_SAMPLE);
        metadata.put("durationMs", durationMs);
        metadata.put("forcedFinalize", true);
        metadata.put("reason", reason);
        SduiArtifactEntity artifact = artifactService.save(deviceId,
                SduiArtifactService.AUDIO_RECORDING,
                "audio/wav",
                metadata,
                wav);
        response.put("recording", false);
        response.put("sent", true);
        response.put("status", "forced_finalized");
        response.put("forcedFinalize", true);
        response.put("reason", reason);
        response.put("artifact", artifactService.toMap(artifact));
        return response;
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
            return parts.length >= 2 ? parts[1] : trimmed;
        }
        int slash = trimmed.lastIndexOf('/');
        return slash >= 0 ? trimmed.substring(slash + 1) : trimmed;
    }

    private Map<String, Object> normalizeMap(Map<?, ?> value) {
        Map<String, Object> normalized = new LinkedHashMap<>();
        if (value != null) {
            for (var entry : value.entrySet()) {
                normalized.put(String.valueOf(entry.getKey()), entry.getValue());
            }
        }
        return normalized;
    }

    private String string(Object value) {
        return value == null ? "" : String.valueOf(value);
    }

    private int intParam(Object value, int defaultValue) {
        if (value instanceof Number n) return n.intValue();
        if (value instanceof String s && !s.isBlank()) {
            try {
                return Integer.parseInt(s);
            } catch (NumberFormatException ignored) {
                return defaultValue;
            }
        }
        return defaultValue;
    }
}
