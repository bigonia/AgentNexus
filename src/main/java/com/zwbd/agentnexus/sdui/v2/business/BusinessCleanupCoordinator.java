package com.zwbd.agentnexus.sdui.v2.business;

import com.zwbd.agentnexus.sdui.v2.audio.AudioStreamService;
import com.zwbd.agentnexus.sdui.v2.display.DisplayCommandService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 业务清理的跨域协调者。
 *
 * <p>01_INTERACTION_MODEL.md §6.1 要求 {@code business.reset} 至少包括：停止录音、音频上传和播放；
 * 结束业务提示音和 RGB 灯效；清除当前业务 UI 和本地导航状态；取消未完成的响应序列和业务定时任务；
 * 清除旧业务尚未发送的交互上报；清除当前绑定和未完成的按钮手势；进入无活动业务的安全状态。</p>
 *
 * <p>其中绝大多数是终端内部动作。平台侧需要同步的只有三项：音频流状态、主视图会话状态、
 * token 与生效配置（后两项由 {@link BusinessConfigService} 自己完成）。</p>
 *
 * <p>§6.1 同时明确：网络连接、设备身份、配网、电源、系统诊断和缓存资源等系统基础状态
 * **不属于**业务清理范围，因此这里不触碰 {@code CapabilityRegistryV2}、连接注册表和系统命令期望值。</p>
 */
@Slf4j
@Component
public class BusinessCleanupCoordinator {

    private final DisplayCommandService displayCommandService;
    private final AudioStreamService audioStreamService;

    public BusinessCleanupCoordinator(DisplayCommandService displayCommandService,
                                      AudioStreamService audioStreamService) {
        this.displayCommandService = displayCommandService;
        this.audioStreamService = audioStreamService;
    }

    @EventListener
    public void onBusinessCleared(BusinessClearedEvent event) {
        String deviceId = event.deviceId();
        displayCommandService.clear(deviceId);
        List<String> endedAudio = audioStreamService.clearAll(deviceId);
        log.info("业务清理已完成平台侧状态同步: device={}, reason={}, endedAudio={}",
                deviceId, event.reason(), endedAudio);
    }
}
