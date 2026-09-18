package com.zwbd.agentnexus.sdui.v2.debug;

import com.zwbd.agentnexus.sdui.v2.business.BusinessClearedEvent;
import com.zwbd.agentnexus.sdui.v2.business.BusinessInteraction;
import com.zwbd.agentnexus.sdui.v2.session.DeviceConnectionTakenOverEvent;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 调试域的请求日志与事件转发。
 *
 * <p>日志只记录调试下达的请求，且是内存环形缓冲——重启即失是有意为之，工作流驱动的执行走
 * {@code /node-workflows} 的运行记录。</p>
 */
class DebugStreamHubTest {

    private final DebugStreamHub hub = new DebugStreamHub();

    @Test
    @DisplayName("日志最新在前，并按 requestId 可查")
    void journalIsNewestFirst() {
        hub.record("dev-1", entry("r1", "system.reboot"));
        hub.record("dev-1", entry("r2", "system.volume.set"));

        var journal = hub.journal("dev-1", 10);

        assertEquals(2, journal.size());
        assertEquals("r2", journal.get(0).get("requestId"));
        assertEquals("r1", journal.get(1).get("requestId"));
        assertEquals("system.volume.set", hub.entry("dev-1", "r2").orElseThrow().get("name"));
        assertTrue(hub.entry("dev-1", "nope").isEmpty());
    }

    @Test
    @DisplayName("日志有上限，且按设备隔离")
    void journalIsBoundedAndPerDevice() {
        for (int i = 0; i < 250; i++) {
            hub.record("dev-1", entry("r" + i, "system.reboot"));
        }
        hub.record("dev-2", entry("other", "system.reboot"));

        var dev1 = hub.journal("dev-1", 500);

        assertEquals(200, dev1.size(), "上限之外的最旧记录应被丢弃");
        assertEquals("r249", dev1.get(0).get("requestId"), "仍应保留最新的");
        assertEquals(1, hub.journal("dev-2", 10).size());
        assertTrue(hub.journal("dev-3", 10).isEmpty());
    }

    @Test
    @DisplayName("没有记录时日志为空")
    void journalWithoutDataIsEmpty() {
        assertTrue(hub.journal("dev-1", 10).isEmpty());
        assertTrue(hub.journal("dev-1", 0).isEmpty(), "limit 会被收敛到合理下限，不应抛错");
    }

    @Test
    @DisplayName("平台事件在无订阅者时安全丢弃")
    void eventsWithoutSubscribersAreNoOps() {
        assertDoesNotThrow(() -> {
            hub.onInteraction(new BusinessInteraction("dev-1", "tok", "button.ok",
                    "wf:wf-1:trigger-1", Instant.parse("2026-09-18T12:00:00Z")));
            hub.onInteraction(new BusinessInteraction("dev-1", "tok", "button.ok", null, null));
            hub.onBusinessCleared(new BusinessClearedEvent("dev-1", BusinessClearedEvent.Reason.RESET_REQUESTED));
            hub.onBusinessCleared(new BusinessClearedEvent("dev-1", null));
            hub.onConnectionTakenOver(new DeviceConnectionTakenOverEvent("dev-1", 0L, 1L));
        });
    }

    @Test
    @DisplayName("订阅接口返回可用的 SSE 发射器")
    void subscribeReturnsEmitter() {
        assertNotNull(hub.subscribeEvents("dev-1"));
        assertNotNull(hub.subscribeRequests("dev-1"));
    }

    private Map<String, Object> entry(String requestId, String name) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("requestId", requestId);
        map.put("name", name);
        map.put("ok", true);
        return map;
    }
}
