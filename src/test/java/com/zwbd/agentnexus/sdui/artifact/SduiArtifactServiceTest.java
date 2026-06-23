package com.zwbd.agentnexus.sdui.artifact;

import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class SduiArtifactServiceTest {

    @Test
    void savesAndMapsArtifact() {
        SduiArtifactRepository repository = mock(SduiArtifactRepository.class);
        when(repository.save(any())).thenAnswer(invocation -> {
            SduiArtifactEntity artifact = invocation.getArgument(0);
            artifact.setCreatedAt(LocalDateTime.parse("2026-06-23T09:00:00"));
            return artifact;
        });
        SduiArtifactService service = new SduiArtifactService(repository);

        SduiArtifactEntity artifact = service.save("dev-1", SduiArtifactService.AUDIO_RECORDING, "audio/wav",
                Map.of("text", "hello", "durationMs", 100), new byte[]{1, 2, 3});
        Map<String, Object> mapped = service.toMap(artifact);

        assertEquals("dev-1", mapped.get("deviceId"));
        assertEquals("artifact:" + artifact.getArtifactId(), mapped.get("artifactRef"));
        assertEquals("/api/v1/sdui/artifacts/" + artifact.getArtifactId() + "/blob", mapped.get("blobUrl"));
        assertEquals("hello", mapped.get("text"));
        assertEquals(3, mapped.get("blobBytes"));
    }

    @Test
    void resolvesLatestAlias() {
        SduiArtifactRepository repository = mock(SduiArtifactRepository.class);
        SduiArtifactEntity artifact = new SduiArtifactEntity();
        artifact.setArtifactId("art-1");
        artifact.setDeviceId("dev-1");
        artifact.setType(SduiArtifactService.AUDIO_RECORDING);
        artifact.setMimeType("audio/wav");
        when(repository.findFirstByDeviceIdAndTypeOrderByCreatedAtDesc("dev-1", SduiArtifactService.AUDIO_RECORDING))
                .thenReturn(Optional.of(artifact));
        SduiArtifactService service = new SduiArtifactService(repository);

        SduiArtifactService.ResolvedArtifact resolved = service.resolve("dev-1", "audio-record-latest");

        assertNotNull(resolved);
        assertEquals("art-1", resolved.artifact().getArtifactId());
    }
}
