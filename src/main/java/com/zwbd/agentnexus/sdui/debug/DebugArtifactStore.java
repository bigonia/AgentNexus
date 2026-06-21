package com.zwbd.agentnexus.sdui.debug;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Generic debug artifact storage.
 *
 * <p>Replaces the audio-specific {@code AudioRecordResultStore} with a
 * capability-agnostic store. Any capability that produces a result
 * (audio recording → WAV + STT text; future: screenshot → PNG, etc.)
 * stores it here indexed by {@code deviceId:artifactId}.</p>
 *
 * <p>Consumed by the generic artifact debug endpoints
 * ({@code GET /{deviceId}/artifacts/{artifactId}} and {@code .../blob}).</p>
 */
@Slf4j
@Component
public class DebugArtifactStore {

    /** key = "deviceId:artifactId" */
    private final ConcurrentHashMap<String, Artifact> store = new ConcurrentHashMap<>();

    /**
     * A stored debug artifact produced by a capability.
     */
    public record Artifact(
            /** Stable identifier (e.g. "audio-record-latest") */
            String artifactId,
            /** Capability type (e.g. "audio/recording") */
            String type,
            /** MIME type for the blob (e.g. "audio/wav", "image/png") */
            String mimeType,
            /** Arbitrary metadata (sttText, durationMs, sampleRate, ...) */
            Map<String, Object> metadata,
            /** Binary content, or null if the artifact is metadata-only */
            byte[] blob,
            /** Epoch millis when the artifact was created */
            long createdAt
    ) {
        /** Convenience: key used internally for storage */
        String storeKey(String deviceId) {
            return deviceId + ":" + artifactId;
        }
    }

    /** Store an artifact, overwriting any previous one with the same id. */
    public void put(String deviceId, Artifact artifact) {
        store.put(artifact.storeKey(deviceId), artifact);
        log.info("Artifact stored: device={}, artifactId={}, type={}, blobBytes={}",
                deviceId, artifact.artifactId(), artifact.type(),
                artifact.blob() != null ? artifact.blob().length : 0);
    }

    /** Get an artifact by device and id. */
    public Optional<Artifact> get(String deviceId, String artifactId) {
        return Optional.ofNullable(store.get(deviceId + ":" + artifactId));
    }

    /** Remove an artifact. */
    public void remove(String deviceId, String artifactId) {
        String key = deviceId + ":" + artifactId;
        Artifact removed = store.remove(key);
        if (removed != null) {
            log.info("Artifact removed: device={}, artifactId={}", deviceId, artifactId);
        }
    }
}
