package com.zwbd.agentnexus.sdui.service;

import com.zwbd.agentnexus.sdui.capability.PlatformCapabilityRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Base64;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class PlatformCapabilityRuntimeServiceTest {

    private AudioService audioService;
    private PlatformCapabilityRuntimeService runtimeService;

    @BeforeEach
    void setUp() {
        audioService = mock(AudioService.class);
        PlatformCapabilityRegistry registry = new PlatformCapabilityRegistry(audioService);
        runtimeService = new PlatformCapabilityRuntimeService(registry, audioService);
    }

    @Test
    void routesTtsThroughPlatformRuntimeInsteadOfDeviceCommandDispatcher() {
        when(audioService.isTtsAvailable()).thenReturn(true);
        when(audioService.playTts("dev-1", "hello"))
                .thenReturn(new AudioService.PlayResult(null, 100, 0, true));

        Map<String, Object> result = runtimeService.execute("dev-1", "audio.tts.speak", Map.of("text", "hello"));

        assertEquals("platform", result.get("source"));
        assertEquals("platform.audio.tts", result.get("runtimeHandler"));
        assertEquals("ACKED", result.get("status"));
        verify(audioService).playTts("dev-1", "hello");
    }

    @Test
    void returnsTranscriptionForPlatformStt() {
        when(audioService.isSttAvailable()).thenReturn(true);
        when(audioService.transcribeAudio(any(byte[].class), eq("wav"))).thenReturn("test result");

        String audioB64 = Base64.getEncoder().encodeToString("pcm".getBytes());
        Map<String, Object> result = runtimeService.execute(
                "dev-2",
                "audio.stt.transcribe",
                Map.of("audioData", audioB64, "format", "wav"));

        assertEquals("platform", result.get("source"));
        assertEquals("platform.audio.stt", result.get("runtimeHandler"));
        assertEquals("test result", result.get("transcription"));
    }
}
