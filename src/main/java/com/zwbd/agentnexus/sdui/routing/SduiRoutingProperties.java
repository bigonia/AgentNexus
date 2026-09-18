package com.zwbd.agentnexus.sdui.routing;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * 新旧协议分流配置。
 *
 * <p>对应 12_DESIGN_NOTES.md 未决问题 Q3「灰度策略：新旧协议并存期如何按设备分流」。
 * 终端重构与平台升级并行期间，必须能按设备挑选走哪一套协议。</p>
 *
 * <p>判定优先级：{@link #legacyDevices} → {@link #v2Devices} → {@link #defaultProtocol}。
 * 黑名单优先于白名单，便于临时把一台已切到 v2 的设备拉回旧协议排查问题。</p>
 *
 * <p>回滚提示：本配置段与 {@link DeviceProtocolRouter} 是<b>过渡期专用</b>。旧协议路径删除时
 * （见 10_PLATFORM_UPGRADE.md §10）应一并删除，届时"设备一律走 v2"成为隐含前提。</p>
 */
@Data
@Component
@ConfigurationProperties(prefix = "sdui.routing")
public class SduiRoutingProperties {

    /** 缺省协议：{@code legacy} 表示未显式列出的设备仍走旧协议。 */
    private String defaultProtocol = "legacy";

    /** 显式走 v2 协议的设备白名单。 */
    private List<String> v2Devices = new ArrayList<>();

    /** 强制走旧协议的设备黑名单，优先于白名单。 */
    private List<String> legacyDevices = new ArrayList<>();
}
