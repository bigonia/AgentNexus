package com.zwbd.agentnexus.sdui.v2.debug;

import com.zwbd.agentnexus.sdui.v2.audio.AudioCommandService;
import com.zwbd.agentnexus.sdui.v2.business.BusinessConfigService;
import com.zwbd.agentnexus.sdui.v2.capability.CapabilityQueryService;
import com.zwbd.agentnexus.sdui.v2.display.DisplayCommandService;
import com.zwbd.agentnexus.sdui.v2.protocol.ProtocolErrors;
import com.zwbd.agentnexus.sdui.v2.protocol.V2Names;
import com.zwbd.agentnexus.sdui.v2.sim.SimulatedLcd085Device;
import com.zwbd.agentnexus.sdui.v2.system.SystemCommandService;
import com.zwbd.agentnexus.sdui.v2.transport.PlatformRequestService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * 调试域下行出口。
 *
 * <p>固定两条约定：调试与业务同路（都走 {@code PlatformRequestService} 与同一套状态化服务），
 * 以及可达性由设备 Schema 的 {@code usableIn} 决定——平台发不出请求的动作必须如实返回，不能伪造下行。
 */
class PlatformRequestDispatcherTest {

    private static final String DEVICE = "dev-1";

    private PlatformRequestService requests;
    private DisplayCommandService display;
    private AudioCommandService audio;
    private SystemCommandService system;
    private BusinessConfigService business;
    private CapabilityQueryService capabilities;
    private DebugStreamHub hub;
    private PlatformRequestDispatcher dispatcher;

    @BeforeEach
    void setUp() {
        requests = mock(PlatformRequestService.class);
        display = mock(DisplayCommandService.class);
        audio = mock(AudioCommandService.class);
        system = mock(SystemCommandService.class);
        business = mock(BusinessConfigService.class);
        capabilities = mock(CapabilityQueryService.class);
        hub = mock(DebugStreamHub.class);

        when(capabilities.online(DEVICE)).thenReturn(true);
        when(capabilities.schemaOf(DEVICE)).thenReturn(Optional.of(SimulatedLcd085Device.SCHEMA));
        when(requests.send(anyString(), anyString(), any()))
                .thenReturn(CompletableFuture.completedFuture(PlatformRequestService.Outcome.success()));
        when(system.setVolume(anyString(), anyInt()))
                .thenReturn(CompletableFuture.completedFuture(PlatformRequestService.Outcome.success()));
        when(system.setBrightness(anyString(), anyInt()))
                .thenReturn(CompletableFuture.completedFuture(PlatformRequestService.Outcome.success()));

        dispatcher = new PlatformRequestDispatcher(requests, display, audio, system, business, capabilities, hub);
    }

    @Test
    @DisplayName("系统命令路由到系统服务，而不是通用下发")
    void routesSystemRequest() {
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("value", 40);

        PlatformRequestDispatcher.Result result = dispatcher.dispatch(DEVICE, V2Names.SYSTEM_VOLUME_SET, params);

        assertTrue(result.ok());
        assertTrue(result.declared());
        assertTrue(result.reachable());
        verify(system).setVolume(DEVICE, 40);
        verify(requests, never()).send(anyString(), anyString(), any());
    }

    @Test
    @DisplayName("未登记的请求名走通用下发，但如实标注 declared=false")
    void unknownNameFallsBackToGenericSend() {
        PlatformRequestDispatcher.Result result =
                dispatcher.dispatch(DEVICE, "vendor.custom.thing", Map.of());

        assertTrue(result.ok());
        assertFalse(result.declared(), "设备 Schema 未声明该动作，必须如实标注");
        verify(requests).send(eq(DEVICE), eq("vendor.custom.thing"), any());
    }

    @Test
    @DisplayName("只声明可用于本地绑定的动作不能作为平台请求下发")
    void bindingOnlyActionIsRefused() {
        // rgb.effect.set 在模拟固件里只声明了 binding
        PlatformRequestDispatcher.Result result =
                dispatcher.dispatch(DEVICE, "rgb.effect.set", Map.of("r", 1, "g", 2, "b", 3));

        assertFalse(result.ok());
        assertEquals(ProtocolErrors.UNSUPPORTED, result.error());
        assertFalse(result.reachable());
        assertTrue(String.valueOf(result.extra().get("reason")).contains("usableIn"));
        verify(requests, never()).send(anyString(), anyString(), any());
        verify(hub).record(eq(DEVICE), any());
    }

    @Test
    @DisplayName("设备离线时不下发")
    void offlineDeviceIsRefused() {
        when(capabilities.online(DEVICE)).thenReturn(false);

        PlatformRequestDispatcher.Result result = dispatcher.dispatch(DEVICE, V2Names.SYSTEM_REBOOT, Map.of());

        assertFalse(result.ok());
        assertEquals(ProtocolErrors.NOT_CONNECTED, result.error());
        verify(system, never()).reboot(anyString());
    }

    @Test
    @DisplayName("请求名为空被拒绝")
    void blankNameIsRefused() {
        PlatformRequestDispatcher.Result result = dispatcher.dispatch(DEVICE, "  ", Map.of());

        assertFalse(result.ok());
        assertEquals(ProtocolErrors.INVALID_VALUE, result.error());
    }

    @Test
    @DisplayName("view 的 section 模式走显示服务并整段下发")
    void publishViewSectionUsesDisplayService() {
        Map<String, Object> section = Map.of("sectionId", "s1", "sectionType", "text_section");
        when(display.sendSection(eq(DEVICE), any()))
                .thenReturn(CompletableFuture.completedFuture(PlatformRequestService.Outcome.success()));

        PlatformRequestDispatcher.Result result =
                dispatcher.publishView(DEVICE, Map.of("mode", "section", "section", section));

        assertTrue(result.ok());
        assertEquals(V2Names.DISPLAY_SECTION, result.name());
        ArgumentCaptor<Object> captor = ArgumentCaptor.forClass(Object.class);
        verify(display).sendSection(eq(DEVICE), captor.capture());
        assertEquals(section, captor.getValue());
        verify(hub).record(eq(DEVICE), any());
    }

    @Test
    @DisplayName("view 的 section 模式缺少 section 时被拒绝")
    void publishViewSectionWithoutBodyIsRefused() {
        PlatformRequestDispatcher.Result result = dispatcher.publishView(DEVICE, Map.of("mode", "section"));

        assertFalse(result.ok());
        assertEquals(ProtocolErrors.INVALID_VALUE, result.error());
        verify(display, never()).sendSection(anyString(), any());
    }

    @Test
    @DisplayName("未知 view 模式被拒绝")
    void publishViewUnknownModeIsRefused() {
        PlatformRequestDispatcher.Result result = dispatcher.publishView(DEVICE, Map.of("mode", "patch"));

        assertFalse(result.ok());
        assertEquals(ProtocolErrors.INVALID_VALUE, result.error());
    }

    @Test
    @DisplayName("记录进请求日志的条目带 requestId 与结果")
    void journalEntryCarriesOutcome() {
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("value", 10);
        dispatcher.dispatch(DEVICE, V2Names.SYSTEM_BRIGHTNESS_SET, params);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> captor = ArgumentCaptor.forClass(Map.class);
        verify(hub).record(eq(DEVICE), captor.capture());
        Map<String, Object> entry = captor.getValue();

        assertNotNull(entry.get("requestId"));
        assertEquals(V2Names.SYSTEM_BRIGHTNESS_SET, entry.get("name"));
        assertEquals(true, entry.get("ok"));
        assertEquals(params, entry.get("params"));
        assertNotNull(entry.get("at"));
    }
}
