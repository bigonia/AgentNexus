package com.zwbd.agentnexus.sdui.v2.display;

import com.zwbd.agentnexus.sdui.section.SectionPatch;
import com.zwbd.agentnexus.sdui.section.SectionScene;
import com.zwbd.agentnexus.sdui.v2.V2ProtocolProperties;
import com.zwbd.agentnexus.sdui.v2.transport.PlatformRequestService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * 主视图发布入口：把平台侧的 UI 产物收敛成单个 Section 并通过 {@code display.section} 下发。
 *
 * <p>业务 UI 只有这一条出口。旧实现里每个下发点自己调用
 * {@code SectionOrchestrationService.sendScene/sendPatch}，于是"一次下发什么"这件事散在多个调用点；
 * 现在收敛规则归 {@link SectionViewResolver}，下发归 {@link DisplayCommandService}，本类只负责把两者
 * 接起来并处理补丁缺失基底时的回退。</p>
 *
 * <p>方法刻意返回 {@code boolean}：调用方（主视图设置、模板预览、UI 变量更新）本就是同步语义，
 * 返回发布是否成功比返回 future 更贴合它们的用法。内部按协议请求超时等待。</p>
 */
@Slf4j
@Service
public class PrimaryViewPublisher {

    private final DisplayCommandService display;
    private final SectionViewResolver resolver;
    private final V2ProtocolProperties properties;

    public PrimaryViewPublisher(DisplayCommandService display,
                                SectionViewResolver resolver,
                                V2ProtocolProperties properties) {
        this.display = display;
        this.resolver = resolver;
        this.properties = properties;
    }

    /**
     * 把 Scene 的首页 Section 作为主视图下发。
     *
     * @return 是否发布成功
     */
    public boolean publish(String deviceId, SectionScene scene) {
        return resolver.primaryOf(scene)
                .map(section -> publishSection(deviceId, section))
                .orElseGet(() -> {
                    log.warn("Scene 没有可发布的 Section: device={}", deviceId);
                    return false;
                });
    }

    /**
     * 把 Patch 合成为完整 Section 后下发。
     *
     * <p>v2 没有 Section 级增量更新。平台侧缺少当前主视图快照时，补丁无从合成，此时回退为下发
     * {@code fallbackScene} 的首页 Section——比静默失败或报错更接近调用方想要的结果（把界面切过去）。</p>
     *
     * @param fallbackScene 合成失败时的完整主视图来源；为 {@code null} 时直接失败
     */
    public boolean publishPatch(String deviceId, SectionPatch patch, SectionScene fallbackScene) {
        return resolver.applyPatch(deviceId, patch)
                .map(section -> publishSection(deviceId, section))
                .orElseGet(() -> {
                    if (fallbackScene == null) {
                        log.warn("无可用的主视图基底，Patch 无法下发: device={}", deviceId);
                        return false;
                    }
                    log.info("平台侧无主视图快照，Patch 回退为完整 Section 下发: device={}", deviceId);
                    return publish(deviceId, fallbackScene);
                });
    }

    /** 直接发布一个已收敛的 Section。 */
    public boolean publishSection(String deviceId, Map<String, Object> section) {
        PlatformRequestService.Outcome outcome = await(display.sendSection(deviceId, section));
        if (!outcome.ok()) {
            log.warn("主视图下发失败: device={}, error={}", deviceId, outcome.error());
            return false;
        }
        resolver.remember(deviceId, section);
        return true;
    }

    /** 设备断开或业务清理时丢弃主视图快照。 */
    public void forget(String deviceId) {
        resolver.forget(deviceId);
    }

    private PlatformRequestService.Outcome await(CompletableFuture<PlatformRequestService.Outcome> future) {
        try {
            return future.get(properties.getRequestTimeoutMs(), TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            future.cancel(true);
            return PlatformRequestService.Outcome.failure("timeout");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return PlatformRequestService.Outcome.failure("interrupted");
        } catch (ExecutionException e) {
            Throwable cause = e.getCause() != null ? e.getCause() : e;
            return PlatformRequestService.Outcome.failure(String.valueOf(cause.getMessage()));
        }
    }
}
