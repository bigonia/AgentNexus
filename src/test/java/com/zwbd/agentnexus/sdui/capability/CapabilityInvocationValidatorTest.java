package com.zwbd.agentnexus.sdui.capability;

import com.zwbd.agentnexus.sdui.service.AudioService;
import com.zwbd.agentnexus.sdui.service.CommandSchemaRegistry;
import com.zwbd.agentnexus.sdui.service.SduiCapabilityService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class CapabilityInvocationValidatorTest {

    private CommandSchemaRegistry commandSchemaRegistry;
    private SduiCapabilityService capabilityService;
    private PlatformCapabilityRegistry platformCapabilityRegistry;
    private CapabilityInvocationValidator validator;

    @BeforeEach
    void setUp() {
        commandSchemaRegistry = mock(CommandSchemaRegistry.class);
        capabilityService = mock(SduiCapabilityService.class);
        AudioService audioService = mock(AudioService.class);
        when(audioService.getPresets()).thenReturn(List.of(Map.of("id", "notification")));
        when(audioService.isTtsAvailable()).thenReturn(true);
        when(audioService.isSttAvailable()).thenReturn(true);
        platformCapabilityRegistry = new PlatformCapabilityRegistry(audioService);
        validator = new CapabilityInvocationValidator(
                commandSchemaRegistry,
                capabilityService,
                platformCapabilityRegistry
        );
    }

    @Test
    void validatesPlatformDebugInvocationAndAppliesDefaults() {
        CapabilityInvocationValidator.ValidationResult result =
                validator.validateDebugInvocation("dev-1", "audio.prompt.play", Map.of());

        assertTrue(result.valid());
        assertEquals("notification", result.normalizedParams().get("preset"));
    }
}
