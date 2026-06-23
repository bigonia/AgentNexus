package com.zwbd.agentnexus.sdui.artifact;

import jakarta.transaction.Transactional;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.ZoneId;
import java.util.*;

@Service
@Slf4j
public class SduiArtifactService {

    public static final String AUDIO_RECORDING = "audio/recording";
    public static final String AUDIO_RECORD_LATEST = "audio-record-latest";

    private final SduiArtifactRepository repository;

    public SduiArtifactService(SduiArtifactRepository repository) {
        this.repository = repository;
    }

    @Transactional
    public SduiArtifactEntity save(String deviceId, String type, String mimeType,
                                   Map<String, Object> metadata, byte[] blob) {
        SduiArtifactEntity artifact = new SduiArtifactEntity();
        artifact.setArtifactId(UUID.randomUUID().toString());
        artifact.setDeviceId(deviceId);
        artifact.setType(type);
        artifact.setMimeType(mimeType);
        artifact.setMetadata(metadata != null ? new LinkedHashMap<>(metadata) : new LinkedHashMap<>());
        artifact.setBlob(blob);
        SduiArtifactEntity saved = repository.save(artifact);
        log.info("SDUI artifact stored: artifactId={}, device={}, type={}, mimeType={}, blobBytes={}",
                saved.getArtifactId(), deviceId, type, mimeType, blob != null ? blob.length : 0);
        return saved;
    }

    public SduiArtifactEntity require(String artifactId) {
        return repository.findById(artifactId)
                .orElseThrow(() -> new IllegalArgumentException("artifact not found: " + artifactId));
    }

    public Optional<SduiArtifactEntity> findLatest(String deviceId, String type) {
        return repository.findFirstByDeviceIdAndTypeOrderByCreatedAtDesc(deviceId, type);
    }

    public List<Map<String, Object>> listByRun(String workflowId, String runId) {
        return repository.findByWorkflowIdAndRunIdOrderByCreatedAtDesc(workflowId, runId).stream()
                .map(this::toMap)
                .toList();
    }

    public ResolvedArtifact resolve(String deviceId, String artifactRef) {
        String artifactId = normalizeArtifactId(artifactRef);
        Optional<SduiArtifactEntity> artifact = AUDIO_RECORD_LATEST.equals(artifactId)
                ? findLatest(deviceId, AUDIO_RECORDING)
                : repository.findById(artifactId);
        return artifact.map(value -> new ResolvedArtifact(artifactId, value)).orElse(null);
    }

    @Transactional
    public void attachToRun(String workflowId, String runId, String nodeId, Map<String, Object> artifactMap) {
        String artifactId = artifactIdFromMap(artifactMap);
        if (artifactId == null || artifactId.isBlank()) {
            return;
        }
        repository.findById(artifactId).ifPresent(artifact -> {
            artifact.setWorkflowId(workflowId);
            artifact.setRunId(runId);
            artifact.setNodeId(nodeId);
            repository.save(artifact);
        });
    }

    public Map<String, Object> toMap(SduiArtifactEntity artifact) {
        Map<String, Object> data = new LinkedHashMap<>();
        Map<String, Object> metadata = artifact.getMetadata() != null
                ? new LinkedHashMap<>(artifact.getMetadata()) : new LinkedHashMap<>();
        data.put("artifactId", artifact.getArtifactId());
        data.put("artifactRef", "artifact:" + artifact.getArtifactId());
        data.put("blobUrl", "/api/v1/sdui/artifacts/" + artifact.getArtifactId() + "/blob");
        data.put("deviceId", artifact.getDeviceId());
        data.put("workflowId", artifact.getWorkflowId());
        data.put("runId", artifact.getRunId());
        data.put("nodeId", artifact.getNodeId());
        data.put("type", artifact.getType());
        data.put("mimeType", artifact.getMimeType());
        data.put("metadata", metadata);
        data.put("text", string(metadata.getOrDefault("text", metadata.getOrDefault("sttText", ""))));
        data.put("durationMs", metadata.get("durationMs"));
        data.put("sampleRate", metadata.get("sampleRate"));
        data.put("blobBytes", artifact.getBlob() != null ? artifact.getBlob().length : 0);
        if (artifact.getCreatedAt() != null) {
            data.put("createdAt", artifact.getCreatedAt().toString());
            data.put("createdAtIso", artifact.getCreatedAt().atZone(ZoneId.systemDefault()).toInstant().toString());
        }
        return data;
    }

    private String artifactIdFromMap(Map<String, Object> artifactMap) {
        if (artifactMap == null) return "";
        Object artifactId = artifactMap.get("artifactId");
        if (artifactId != null && !String.valueOf(artifactId).isBlank()) {
            return String.valueOf(artifactId);
        }
        Object ref = artifactMap.get("artifactRef");
        return ref != null ? normalizeArtifactId(String.valueOf(ref)) : "";
    }

    private String normalizeArtifactId(String value) {
        if (value == null || value.isBlank()) {
            return AUDIO_RECORD_LATEST;
        }
        String trimmed = value.trim();
        if (trimmed.startsWith("artifact:")) {
            String[] parts = trimmed.split(":");
            return parts.length >= 2 ? parts[1] : trimmed;
        }
        int slash = trimmed.lastIndexOf('/');
        return slash >= 0 ? trimmed.substring(slash + 1) : trimmed;
    }

    private String string(Object value) {
        return value == null ? "" : String.valueOf(value);
    }

    public record ResolvedArtifact(String requestedArtifactId, SduiArtifactEntity artifact) {}
}
