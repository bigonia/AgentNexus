package com.zwbd.agentnexus.sdui.v2.capability;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.zwbd.agentnexus.common.web.GlobalContext;
import com.zwbd.agentnexus.sdui.model.SduiDevice;
import com.zwbd.agentnexus.sdui.repo.SduiDeviceRepository;
import com.zwbd.agentnexus.sdui.v2.session.DeviceConnectionRegistry;
import com.zwbd.agentnexus.sdui.v2.sim.SimulatedLcd085Device;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 能力查询的唯一出口。
 *
 * <p>重点固定三件事：租户边界在服务内完成（{@code sdui_device} 是全局表）、在线态只认 v2 连接注册表、
 * 板型是设备聚合而不是独立数据源。</p>
 */
class CapabilityQueryServiceTest {

    private static final String USER = "alice";

    private CapabilityRegistryV2 registry;
    private DeviceConnectionRegistry connections;
    private SduiDeviceRepository devices;
    private CapabilityQueryService service;

    @BeforeEach
    void setUp() {
        GlobalContext.set(GlobalContext.KEY_USER_ID, USER);
        registry = new CapabilityRegistryV2(new ObjectMapper());
        connections = mock(DeviceConnectionRegistry.class);
        devices = mock(SduiDeviceRepository.class);
        service = new CapabilityQueryService(registry, connections, devices);
    }

    @AfterEach
    void tearDown() {
        GlobalContext.clear();
    }

    @Test
    @DisplayName("摘要暴露同步状态、hash 与能力规模")
    void summaryExposesSyncState() {
        syncDevice("dev-a");

        Map<String, Object> summary = service.summary("dev-a");

        assertEquals("SYNCED", summary.get("syncState"));
        assertEquals(SimulatedLcd085Device.SCHEMA.board(), summary.get("board"));
        assertEquals(true, summary.get("schemaSynced"));
        assertEquals(SimulatedLcd085Device.SCHEMA.actions().size(), summary.get("actionCount"));
        assertEquals(SimulatedLcd085Device.SCHEMA.triggers().size(), summary.get("triggerCount"));
        assertNotNull(summary.get("capabilityHash"));

        @SuppressWarnings("unchecked")
        Map<String, Object> surface = (Map<String, Object>) summary.get("surface");
        assertNotNull(surface.get("screen"));
        assertNotNull(surface.get("ui"));
    }

    @Test
    @DisplayName("未同步设备的摘要不谎报能力")
    void unsyncedDeviceHasNoCapabilities() {
        Map<String, Object> summary = service.summary("dev-x");

        assertEquals("PENDING", summary.get("syncState"));
        assertEquals(false, summary.get("schemaSynced"));
        assertNull(summary.get("board"));
        assertEquals(0, summary.get("actionCount"));
        assertNull(summary.get("surface"));
        assertTrue(service.actions("dev-x").isEmpty());
        assertTrue(service.triggers("dev-x").isEmpty());
        assertTrue(service.sectionTypes("dev-x").isEmpty());
    }

    @Test
    @DisplayName("动作目录按 usableIn 区分可达性")
    void actionsExposeUsableIn() {
        syncDevice("dev-a");

        List<Map<String, Object>> actions = service.actions("dev-a");

        Map<String, Object> rgb = find(actions, "rgb.effect.set");
        assertEquals(false, rgb.get("usableInRequest"));
        assertEquals(true, rgb.get("usableInBinding"));

        Map<String, Object> reboot = find(actions, "system.reboot");
        assertEquals(true, reboot.get("usableInRequest"));
        assertEquals(false, reboot.get("usableInBinding"));

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> params = (List<Map<String, Object>>) find(actions, "rgb.effect.set").get("params");
        Map<String, Object> red = params.stream()
                .filter(p -> "r".equals(p.get("name"))).findFirst().orElseThrow();
        assertEquals(0, red.get("min"));
        assertEquals(255, red.get("max"));
        assertEquals(true, red.get("required"));
    }

    @Test
    @DisplayName("触发源目录暴露可配置标记与上限")
    void triggersExposeConfigurable() {
        syncDevice("dev-a");

        Map<String, Object> platform = find(service.triggers("dev-a"), "platform.trigger");

        assertEquals("platform", platform.get("source"));
        assertEquals(true, platform.get("configurable"));
        assertEquals(4, platform.get("maxResponses"));
    }

    @Test
    @DisplayName("同步进度给出业务准入判定")
    void syncReportsBusinessAllowed() {
        syncDevice("dev-a");

        Map<String, Object> sync = service.sync("dev-a");

        assertEquals("SYNCED", sync.get("state"));
        assertEquals(true, sync.get("hashKnown"));
        assertEquals(false, sync.get("needsSchemaUpload"));
        assertEquals(true, sync.get("businessAllowed"));
    }

    @Test
    @DisplayName("板型是设备聚合：按板型分组，未同步设备单独计数")
    void boardsAggregateDevices() {
        syncDevice("dev-a");
        SduiDevice unsynced = device("dev-b");
        when(devices.findByOwnerUserId(USER)).thenReturn(List.of(device("dev-a"), unsynced));
        when(connections.isOnline("dev-a")).thenReturn(true);

        List<Map<String, Object>> boards = service.boards();

        Map<String, Object> lcd085 = boards.stream()
                .filter(b -> SimulatedLcd085Device.SCHEMA.board().equals(b.get("board")))
                .findFirst().orElseThrow();
        assertEquals(1, lcd085.get("deviceCount"));
        assertEquals(1, lcd085.get("onlineCount"));

        Map<String, Object> unclassified = boards.stream()
                .filter(b -> b.get("board") == null)
                .findFirst().orElseThrow();
        assertEquals(1, unclassified.get("deviceCount"));
        assertNotNull(unclassified.get("label"));
    }

    @Test
    @DisplayName("代表设备优先取在线的那台")
    void representativeDevicePrefersOnline() {
        syncDevice("dev-a");
        syncDevice("dev-b");
        when(devices.findByOwnerUserId(USER)).thenReturn(List.of(device("dev-a"), device("dev-b")));
        when(connections.isOnline("dev-b")).thenReturn(true);

        assertEquals(Optional.of("dev-b"),
                service.representativeDevice(SimulatedLcd085Device.SCHEMA.board()));
    }

    @Test
    @DisplayName("租户边界在服务内完成")
    void tenantBoundaryIsEnforced() {
        when(devices.findById("dev-a")).thenReturn(Optional.of(device("dev-a", "bob")));

        assertTrue(service.device("dev-a").isEmpty(), "不能读到别的租户的设备");
        assertTrue(service.representativeDevice(null).isEmpty());
    }

    @Test
    @DisplayName("概览统计已同步设备数")
    void overviewCountsSyncedDevices() {
        syncDevice("dev-a");
        when(devices.findByOwnerUserId(USER)).thenReturn(List.of(device("dev-a"), device("dev-b")));

        Map<String, Object> overview = service.overview();

        assertEquals(2, overview.get("deviceCount"));
        assertEquals(1L, overview.get("syncedCount"));
    }

    @Test
    @DisplayName("Section 类型门禁读设备声明的 surface.ui，不并读旧能力快照")
    void sectionTypesComeFromSchema() {
        syncDevice("dev-a");

        Set<String> supported = service.sectionTypes("dev-a");

        assertEquals(new LinkedHashSet<>(SimulatedLcd085Device.SCHEMA.surface().ui().sectionTypes()), supported);
        assertTrue(supported.contains("text_section"));
        assertFalse(supported.contains("map_section"), "设备没声明的类型不能凭空出现");
    }

    // ── 辅助 ───────────────────────────────────────────────────────────────

    /** 把模拟固件的 Schema 入缓存并让设备以该 hash 完成握手，效果等价于一次成功的能力同步。 */
    private void syncDevice(String deviceId) {
        String hash = registry.cache(SimulatedLcd085Device.SCHEMA);
        registry.onHandshake(deviceId, hash);
    }

    private static SduiDevice device(String deviceId) {
        return device(deviceId, USER);
    }

    private static SduiDevice device(String deviceId, String owner) {
        SduiDevice device = new SduiDevice();
        device.setDeviceId(deviceId);
        device.setName(deviceId);
        device.setOwnerUserId(owner);
        return device;
    }

    private static Map<String, Object> find(List<Map<String, Object>> items, String name) {
        return items.stream()
                .filter(item -> name.equals(item.get("name")) || name.equals(item.get("id")))
                .findFirst()
                .orElseThrow(() -> new AssertionError("未找到条目: " + name));
    }
}
