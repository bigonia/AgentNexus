package com.zwbd.agentnexus.sdui.v2.session;

import lombok.Getter;
import org.springframework.web.socket.WebSocketSession;

import java.time.Instant;

/**
 * 一个设备的当前有效连接。
 *
 * <p>{@code generation} 是连接代次，每次新连接接管时自增。上行报文只有在
 * {@code generation} 与当前值一致时才被处理，用于实现 04_PROTOCOL_MODEL.md §2
 * "旧连接后续消息全部忽略"。</p>
 */
@Getter
public class DeviceConnection {

    private final String deviceId;
    private final long generation;
    private final WebSocketSession session;
    private final Instant connectedAt;

    private DeviceHandshake handshake;

    /** 平台是否已确认该设备的完整能力 Schema。 */
    private volatile boolean capabilitySynced;

    public DeviceConnection(String deviceId, long generation, WebSocketSession session, DeviceHandshake handshake) {
        this.deviceId = deviceId;
        this.generation = generation;
        this.session = session;
        this.handshake = handshake;
        this.connectedAt = Instant.now();
        this.capabilitySynced = false;
    }

    public void updateHandshake(DeviceHandshake merged) {
        this.handshake = merged;
    }

    public void markCapabilitySynced() {
        this.capabilitySynced = true;
    }

    public String protocolVersion() {
        return handshake != null ? handshake.protocolVersion() : null;
    }

    public String capabilityHash() {
        return handshake != null ? handshake.capabilityHash() : null;
    }

    public boolean isOpen() {
        return session != null && session.isOpen();
    }

    public String sessionId() {
        return session != null ? session.getId() : null;
    }
}
