package com.zwbd.agentnexus.sdui.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.zwbd.agentnexus.sdui.model.SduiDeviceCommand;
import com.zwbd.agentnexus.sdui.repo.SduiDeviceCommandRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

class CommandServiceTest {

    private SduiDeviceCommandRepository commandRepository;
    private CommandDispatcher dispatcher;
    private AudioService audioService;
    private CommandResultStreamService commandResultStreamService;
    private CommandService commandService;

    @BeforeEach
    void setUp() {
        commandRepository = mock(SduiDeviceCommandRepository.class);
        dispatcher = mock(CommandDispatcher.class);
        audioService = mock(AudioService.class);
        commandResultStreamService = mock(CommandResultStreamService.class);
        commandService = new CommandService(commandRepository, dispatcher, audioService,
                commandResultStreamService, new ObjectMapper());
    }

    @Test
    void rgbEffectSetUsesRgbSetForSolidMode() {
        when(dispatcher.dispatchWithAction(eq("dev-1"), eq("rgb.effect.set"), eq("rgb_set"), any()))
                .thenReturn(new CommandDispatcher.DispatchResult("cmd-1", "cmd/control", "rgb_set",
                        "{\"cmd_id\":\"cmd-1\",\"action\":\"rgb_set\",\"r\":22,\"g\":119,\"b\":255,\"brightness\":120}", true));

        var result = commandService.dispatchCommand("dev-1", "rgb.effect.set",
                Map.of("mode", "solid", "r", 22, "g", 119, "b", 255, "brightness", 120));

        assertTrue(result.sent());
        assertEquals("rgb_set", result.action());
        verify(dispatcher).dispatchWithAction(eq("dev-1"), eq("rgb.effect.set"), eq("rgb_set"),
                eq(Map.of("mode", "solid", "r", 22, "g", 119, "b", 255, "brightness", 120)));
        ArgumentCaptor<SduiDeviceCommand> captor = ArgumentCaptor.forClass(SduiDeviceCommand.class);
        verify(commandRepository).save(captor.capture());
        assertEquals("rgb.effect.set", captor.getValue().getCommand());
        assertEquals("rgb_set", captor.getValue().getAction());
        verify(commandResultStreamService).publishCommand(any(SduiDeviceCommand.class), eq("dispatch"));
    }

    @Test
    void rgbEffectSetUsesRgbPolicyForDynamicMode() {
        when(dispatcher.dispatchWithAction(eq("dev-1"), eq("rgb.effect.set"), eq("rgb_policy"), any()))
                .thenReturn(new CommandDispatcher.DispatchResult("cmd-2", "cmd/control", "rgb_policy",
                        "{\"cmd_id\":\"cmd-2\",\"action\":\"rgb_policy\",\"mode\":\"breathe\",\"r\":22,\"g\":119,\"b\":255,\"brightness\":120,\"period_ms\":1200,\"step_ms\":40}", true));

        var result = commandService.dispatchCommand("dev-1", "rgb.effect.set",
                Map.of("mode", "breathe", "r", 22, "g", 119, "b", 255, "brightness", 120, "period_ms", 1200, "step_ms", 40));

        assertTrue(result.sent());
        assertEquals("rgb_policy", result.action());
        verify(dispatcher).dispatchWithAction(eq("dev-1"), eq("rgb.effect.set"), eq("rgb_policy"),
                eq(Map.of("mode", "breathe", "r", 22, "g", 119, "b", 255, "brightness", 120, "period_ms", 1200, "step_ms", 40)));
        ArgumentCaptor<SduiDeviceCommand> captor = ArgumentCaptor.forClass(SduiDeviceCommand.class);
        verify(commandRepository).save(captor.capture());
        assertEquals("rgb.effect.set", captor.getValue().getCommand());
        assertEquals("rgb_policy", captor.getValue().getAction());
        verify(commandResultStreamService).publishCommand(any(SduiDeviceCommand.class), eq("dispatch"));
    }
}
