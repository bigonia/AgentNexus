package com.zwbd.agentnexus.sdui.v2.business;

/**
 * 业务已被清空的事件（{@code business.reset} 成功后发布）。
 *
 * <p>01_INTERACTION_MODEL.md §6.1 定义了业务清理的范围，其中大部分由终端执行。平台侧需要同步清理的
 * 只有平台自己持有的那部分状态：音频流状态、主视图会话、请求登记、token 与生效配置。</p>
 *
 * <p>用事件而不是在 {@link BusinessConfigService} 里直接调用，是为了避免业务配置域同时依赖
 * 音频域和显示域——清理的"范围"由一个协调者集中表达，各域只需声明自己对清理的反应。</p>
 */
public record BusinessClearedEvent(String deviceId, Reason reason) {

    /** 清理原因，便于区分是主动切换还是异常兜底。 */
    public enum Reason {
        /** 平台主动下发 {@code business.reset} 且终端确认成功。 */
        RESET_REQUESTED,
        /** 设备注销或平台侧强制遗忘。 */
        DEVICE_FORGOTTEN
    }
}
