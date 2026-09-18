package com.zwbd.agentnexus.sdui.v2.business;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 设备的完整业务配置（声明式绑定表）。
 *
 * <p>对应 01_INTERACTION_MODEL.md §6.2：平台通过 {@code business.update} 下发完整目标配置，
 * 终端先完整解析和校验再原子替换；首期不提供绑定级增删改等增量命令。</p>
 *
 * <p>{@code configVersion} 由平台自增维护。缺口 G16：文档未规定终端是否回显版本号，
 * 平台侧首期只用它做本地漂移检测与日志关联，不作为协议确认依据。</p>
 *
 * <p>字段名与嵌套结构属于平台侧临时假设（缺口 G13），终端实现完成后需要对齐。</p>
 *
 * @param deviceId      仅平台侧使用，下发时不进入 body（04_PROTOCOL_MODEL.md §2：消息不重复携带设备标识）
 * @param configVersion 平台自增版本号
 * @param triggers      绑定表
 */
public record BusinessConfig(String deviceId, long configVersion, List<TriggerBinding> triggers) {

    public BusinessConfig {
        triggers = triggers == null ? List.of() : List.copyOf(triggers);
    }

    public static BusinessConfig empty(String deviceId, long configVersion) {
        return new BusinessConfig(deviceId, configVersion, List.of());
    }

    /** 绑定数量为 0 也表示一份合法配置，与"无活动业务"是两个不同概念（01§6）。 */
    public boolean isEmpty() {
        return triggers.isEmpty();
    }

    public TriggerBinding findBinding(String triggerId) {
        return triggers.stream()
                .filter(binding -> binding.triggerId().equals(triggerId))
                .findFirst()
                .orElse(null);
    }

    /** 按云端 Trigger token 查找绑定。 */
    public TriggerBinding findBindingByToken(String token) {
        if (token == null) {
            return null;
        }
        return triggers.stream()
                .filter(TriggerBinding::isPlatformTrigger)
                .filter(binding -> token.equals(binding.token()))
                .findFirst()
                .orElse(null);
    }

    /** 替换绑定表，用于准备阶段注入 token。 */
    public BusinessConfig withTriggers(List<TriggerBinding> newTriggers) {
        return new BusinessConfig(deviceId, configVersion, newTriggers);
    }

    public BusinessConfig withVersion(long newVersion) {
        return new BusinessConfig(deviceId, newVersion, triggers);
    }

    /**
     * {@code business.update} 的 body。不含 {@code deviceId}。
     *
     * <p>在 01§6.2 的顺序要求下，终端需要"校验并暂存新配置 → 暂停分发 → 等待当前序列结束 →
     * 原子替换"，因此 body 是完整快照而非增量。</p>
     */
    public Map<String, Object> toWireBody() {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("configVersion", configVersion);
        body.put("triggers", triggers.stream().map(TriggerBinding::toWire).toList());
        return body;
    }
}
