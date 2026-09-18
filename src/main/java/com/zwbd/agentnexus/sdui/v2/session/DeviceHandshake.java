package com.zwbd.agentnexus.sdui.v2.session;

/**
 * 连接建立时的轻量握手信息。
 *
 * <p>对应 04_PROTOCOL_MODEL.md §3：除 {@code device_id} 外只需要 {@code protocol_version}
 * 和 {@code capability_hash}。首期不使用 {@code boot_id}。</p>
 *
 * <p>这三个字段的承载位置文档未定（缺口 G1），因此本记录允许任一项为 null，
 * 由接入层决定取连接参数还是首条 {@code device.hello} 请求。</p>
 */
public record DeviceHandshake(String deviceId, String protocolVersion, String capabilityHash) {

    public static DeviceHandshake of(String deviceId, String protocolVersion, String capabilityHash) {
        return new DeviceHandshake(deviceId, protocolVersion, capabilityHash);
    }

    /** 用于把首条 hello 消息补齐到连接参数已解析出的设备标识上。 */
    public DeviceHandshake merge(DeviceHandshake incoming) {
        if (incoming == null) {
            return this;
        }
        return new DeviceHandshake(
                incoming.deviceId() != null ? incoming.deviceId() : deviceId,
                incoming.protocolVersion() != null ? incoming.protocolVersion() : protocolVersion,
                incoming.capabilityHash() != null ? incoming.capabilityHash() : capabilityHash);
    }
}
