package com.zwbd.agentnexus.sdui.routing;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 设备协议分流器：判断一台设备当前使用旧协议还是 v2 协议。
 *
 * <h2>为什么需要</h2>
 * <p>终端固件不会一夜之间全部升级。平台侧同时保留两套协议入口，就必须能回答"这台设备该走哪套"。
 * 旧实现里没有这个概念——所有设备都走旧协议。这是 12_DESIGN_NOTES.md 未决问题 Q3 的落地。</p>
 *
 * <h2>它不是什么</h2>
 * <p>它不判断设备是否在线，也不判断能力是否已同步。那些分别由 {@code DeviceSessionManager} 与
 * {@code CapabilityRegistryV2} 负责。本类只回答协议归属。</p>
 *
 * <h2>回滚提示</h2>
 * <p>本类是新旧协议并存期的过渡组件。旧协议路径删除时一并删除，
 * 见 10_PLATFORM_UPGRADE.md §10 与 12_DESIGN_NOTES.md 的"待清除模块清单"。</p>
 */
@Slf4j
@Service
public class DeviceProtocolRouter {

    /** 设备可用的协议。 */
    public enum Protocol {
        LEGACY, V2
    }

    private final SduiRoutingProperties properties;

    public DeviceProtocolRouter(SduiRoutingProperties properties) {
        this.properties = properties;
    }

    public Protocol protocolOf(String deviceId) {
        if (deviceId == null || deviceId.isBlank()) {
            return Protocol.LEGACY;
        }
        if (contains(properties.getLegacyDevices(), deviceId)) {
            return Protocol.LEGACY;
        }
        if (contains(properties.getV2Devices(), deviceId)) {
            return Protocol.V2;
        }
        return "v2".equalsIgnoreCase(properties.getDefaultProtocol()) ? Protocol.V2 : Protocol.LEGACY;
    }

    public boolean isV2(String deviceId) {
        return protocolOf(deviceId) == Protocol.V2;
    }

    public boolean isLegacy(String deviceId) {
        return protocolOf(deviceId) == Protocol.LEGACY;
    }

    /** 便于部署编排记录：当前分流形态的快照。 */
    public String describe() {
        return "default=" + properties.getDefaultProtocol()
                + ", v2Devices=" + properties.getV2Devices()
                + ", legacyDevices=" + properties.getLegacyDevices();
    }

    private static boolean contains(List<String> devices, String deviceId) {
        return devices != null && devices.stream().anyMatch(deviceId::equals);
    }
}
