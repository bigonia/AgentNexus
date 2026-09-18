package com.zwbd.agentnexus.sdui.v2.session;

import com.zwbd.agentnexus.sdui.v2.V2TestSupport;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.web.socket.WebSocketSession;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/**
 * 连接注册表：单连接接管与代次校验。
 */
class DeviceConnectionRegistryTest {

    private final ApplicationEventPublisher publisher = mock(ApplicationEventPublisher.class);
    private final DeviceConnectionRegistry registry = new DeviceConnectionRegistry(publisher);

    @Test
    @DisplayName("首次连接：代次为 1，事件标记为首次连接")
    void firstConnection() {
        WebSocketSession session = V2TestSupport.openSession("s-1");

        DeviceConnection connection = registry.attach("dev-1", session,
                V2TestSupport.handshake("dev-1", "hash-a"));

        assertEquals(1L, connection.getGeneration());
        assertTrue(registry.isOnline("dev-1"));
        assertTrue(registry.isCurrent("dev-1", 1L));

        ArgumentCaptor<DeviceConnectionTakenOverEvent> captor =
                ArgumentCaptor.forClass(DeviceConnectionTakenOverEvent.class);
        verify(publisher).publishEvent(captor.capture());
        assertTrue(captor.getValue().isFirstConnection());
        assertEquals(0L, captor.getValue().previousGeneration());
    }

    @Test
    @DisplayName("新连接接管后旧连接消息被忽略，旧会话关闭不影响在线状态")
    void takeoverInvalidatesOldSession() {
        WebSocketSession oldSession = V2TestSupport.openSession("s-old");
        WebSocketSession newSession = V2TestSupport.openSession("s-new");

        registry.attach("dev-1", oldSession, V2TestSupport.handshake("dev-1", "hash-a"));
        DeviceConnection current = registry.attach("dev-1", newSession, V2TestSupport.handshake("dev-1", "hash-a"));

        assertEquals(2L, current.getGeneration());
        assertFalse(registry.isCurrent("dev-1", 1L), "旧连接代次不再有效");
        assertTrue(registry.isCurrent("dev-1", 2L));

        // 旧会话已不再映射到设备，迟到的上行报文无法解析出设备
        assertEquals(null, registry.deviceIdOfSession("s-old"));
        assertEquals("dev-1", registry.deviceIdOfSession("s-new"));

        // 旧会话的关闭事件不得把设备置为离线
        registry.detach(oldSession);
        assertTrue(registry.isOnline("dev-1"));

        // 新会话关闭才真正下线
        registry.detach(newSession);
        assertFalse(registry.isOnline("dev-1"));
        assertEquals(0L, registry.currentGeneration("dev-1"));
    }

    @Test
    @DisplayName("同一会话重复注册是幂等的，不产生新代次、不发布接管事件")
    void repeatedAttachOfSameSessionIsIdempotent() {
        WebSocketSession session = V2TestSupport.openSession("s-1");

        DeviceConnection first = registry.attach("dev-1", session, V2TestSupport.handshake("dev-1", "hash-a"));
        DeviceConnection second = registry.attach("dev-1", session, V2TestSupport.handshake("dev-1", "hash-a"));

        assertEquals(first, second);
        assertEquals(1L, registry.currentGeneration("dev-1"));
        verify(publisher, times(1))
                .publishEvent(org.mockito.ArgumentMatchers.any(DeviceConnectionTakenOverEvent.class));
    }

    @Test
    @DisplayName("不同设备的连接互不影响")
    void isolatesDevices() {
        registry.attach("dev-1", V2TestSupport.openSession("s-1"), V2TestSupport.handshake("dev-1", "h1"));
        registry.attach("dev-2", V2TestSupport.openSession("s-2"), V2TestSupport.handshake("dev-2", "h2"));

        assertTrue(registry.isOnline("dev-1"));
        assertTrue(registry.isOnline("dev-2"));
        assertNotEquals(registry.currentGeneration("dev-1"), registry.currentGeneration("dev-2"));

        registry.detach(V2TestSupport.openSession("s-unknown"));
        assertTrue(registry.isOnline("dev-1"), "未登记的会话不应影响任何设备");
        assertTrue(registry.isOnline("dev-2"));
    }

    @Test
    @DisplayName("能力同步状态保存在连接对象上")
    void tracksCapabilitySyncFlag() {
        DeviceConnection connection = registry.attach("dev-1", V2TestSupport.openSession("s-1"),
                V2TestSupport.handshake("dev-1", "hash-a"));

        assertFalse(connection.isCapabilitySynced());
        connection.markCapabilitySynced();
        assertTrue(connection.isCapabilitySynced());
        assertEquals("hash-a", connection.capabilityHash());
    }

    @Test
    @DisplayName("每次接管都发布事件")
    void publishesEventPerTakeover() {
        registry.attach("dev-1", V2TestSupport.openSession("s-1"), V2TestSupport.handshake("dev-1", "h"));
        registry.attach("dev-1", V2TestSupport.openSession("s-2"), V2TestSupport.handshake("dev-1", "h"));

        ArgumentCaptor<DeviceConnectionTakenOverEvent> captor =
                ArgumentCaptor.forClass(DeviceConnectionTakenOverEvent.class);
        verify(publisher, times(2)).publishEvent(captor.capture());

        List<DeviceConnectionTakenOverEvent> events = captor.getAllValues();
        assertTrue(events.get(0).isFirstConnection());
        assertFalse(events.get(1).isFirstConnection());
        assertEquals(1L, events.get(1).previousGeneration());
        assertEquals(2L, events.get(1).currentGeneration());
    }

    @Test
    @DisplayName("平台主动断开后设备变为离线")
    void disconnectClosesSession() {
        registry.attach("dev-1", V2TestSupport.openSession("s-1"), V2TestSupport.handshake("dev-1", "h"));

        assertTrue(registry.disconnect("dev-1"));
        assertFalse(registry.isOnline("dev-1"));
        assertFalse(registry.disconnect("dev-unknown"), "没有连接时返回 false，不抛错");
    }
}
