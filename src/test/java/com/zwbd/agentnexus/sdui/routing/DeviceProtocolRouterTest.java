package com.zwbd.agentnexus.sdui.routing;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 新旧协议分流的判定优先级。
 *
 * <p>这台判定是新旧协议并存期唯一的按设备切换手段（12_DESIGN_NOTES.md 未决问题 Q3），
 * 优先级一旦被改错，可能把已经切到 v2 的设备推回旧协议，所以逐条固定。</p>
 */
class DeviceProtocolRouterTest {

    private static DeviceProtocolRouter router(String defaultProtocol,
                                               List<String> v2Devices,
                                               List<String> legacyDevices) {
        SduiRoutingProperties properties = new SduiRoutingProperties();
        properties.setDefaultProtocol(defaultProtocol);
        properties.setV2Devices(v2Devices);
        properties.setLegacyDevices(legacyDevices);
        return new DeviceProtocolRouter(properties);
    }

    @Test
    @DisplayName("缺省协议为 legacy 时未列出的设备走旧协议")
    void defaultsToLegacy() {
        DeviceProtocolRouter router = router("legacy", List.of(), List.of());
        assertEquals(DeviceProtocolRouter.Protocol.LEGACY, router.protocolOf("dev-1"));
        assertTrue(router.isLegacy("dev-1"));
        assertFalse(router.isV2("dev-1"));
    }

    @Test
    @DisplayName("白名单中的设备走 v2")
    void allowListSelectsV2() {
        DeviceProtocolRouter router = router("legacy", List.of("dev-1"), List.of());
        assertTrue(router.isV2("dev-1"));
        assertTrue(router.isLegacy("dev-2"));
    }

    @Test
    @DisplayName("黑名单优先于白名单，可把设备临时拉回旧协议")
    void legacyListWinsOverV2List() {
        DeviceProtocolRouter router = router("legacy", List.of("dev-1"), List.of("dev-1"));
        assertTrue(router.isLegacy("dev-1"));
    }

    @Test
    @DisplayName("缺省协议可整体切到 v2，黑名单仍能单独豁免")
    void defaultV2WithExemptions() {
        DeviceProtocolRouter router = router("v2", List.of(), List.of("dev-legacy"));
        assertTrue(router.isV2("dev-1"));
        assertTrue(router.isV2("dev-2"));
        assertTrue(router.isLegacy("dev-legacy"));
    }

    @Test
    @DisplayName("设备标识为空时按旧协议处理，避免误判为 v2")
    void blankDeviceIdIsLegacy() {
        DeviceProtocolRouter router = router("v2", List.of(), List.of());
        assertEquals(DeviceProtocolRouter.Protocol.LEGACY, router.protocolOf(null));
        assertEquals(DeviceProtocolRouter.Protocol.LEGACY, router.protocolOf("  "));
    }

    @Test
    @DisplayName("分流快照包含三要素，便于部署响应与排查")
    void describeIncludesAllKnobs() {
        String described = router("v2", List.of("dev-1"), List.of("dev-2")).describe();
        assertTrue(described.contains("default=v2"));
        assertTrue(described.contains("dev-1"));
        assertTrue(described.contains("dev-2"));
    }
}
