package com.zwbd.agentnexus.sdui.controller;

import com.zwbd.agentnexus.sdui.artifact.SduiArtifactEntity;
import com.zwbd.agentnexus.sdui.artifact.SduiArtifactService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.Map;
import java.util.Optional;

import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class SduiArtifactControllerTest {

    private SduiArtifactService artifactService;
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        artifactService = mock(SduiArtifactService.class);
        mockMvc = MockMvcBuilders.standaloneSetup(new SduiArtifactController(artifactService)).build();
    }

    @Test
    void returnsArtifactMetadataAndBlob() throws Exception {
        SduiArtifactEntity artifact = artifact();
        when(artifactService.require("art-1")).thenReturn(artifact);
        when(artifactService.toMap(artifact)).thenReturn(Map.of("artifactId", "art-1"));

        mockMvc.perform(get("/api/v1/sdui/artifacts/art-1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.artifactId").value("art-1"));
        mockMvc.perform(get("/api/v1/sdui/artifacts/art-1/blob"))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Type", "audio/wav"))
                .andExpect(content().bytes(new byte[]{1, 2, 3}));
    }

    @Test
    void returnsLatestDeviceArtifact() throws Exception {
        SduiArtifactEntity artifact = artifact();
        when(artifactService.findLatest("dev-1", SduiArtifactService.AUDIO_RECORDING)).thenReturn(Optional.of(artifact));
        when(artifactService.toMap(artifact)).thenReturn(Map.of("artifactId", "art-1"));

        mockMvc.perform(get("/api/v1/sdui/devices/dev-1/artifacts/latest"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.artifactId").value("art-1"));
    }

    private SduiArtifactEntity artifact() {
        SduiArtifactEntity artifact = new SduiArtifactEntity();
        artifact.setArtifactId("art-1");
        artifact.setDeviceId("dev-1");
        artifact.setType(SduiArtifactService.AUDIO_RECORDING);
        artifact.setMimeType("audio/wav");
        artifact.setBlob(new byte[]{1, 2, 3});
        return artifact;
    }
}
