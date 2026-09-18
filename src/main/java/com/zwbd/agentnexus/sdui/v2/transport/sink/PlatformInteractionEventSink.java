package com.zwbd.agentnexus.sdui.v2.transport.sink;

import com.zwbd.agentnexus.sdui.v2.business.BusinessConfigService;
import com.zwbd.agentnexus.sdui.v2.protocol.V2Names;
import com.zwbd.agentnexus.sdui.v2.transport.V2Contexts;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 业务交互上报事件接收器。
 *
 * <p>01_INTERACTION_MODEL.md §4 的 {@code platform.interaction} 是平台唯一需要处理的
 * 终端主动业务事件。终端只上报 {@code device_id + token}，平台负责恢复业务上下文。</p>
 *
 * <p>注意与 {@code platform.interaction.report} 的区别：后者是**本地响应序列中的一个动作**，
 * 由平台在下发绑定时配置 token；前者是终端上报到平台的**事件名**。</p>
 */
@Slf4j
@Component
public class PlatformInteractionEventSink implements V2Contexts.EventSink {

    private final BusinessConfigService businessConfigService;

    public PlatformInteractionEventSink(BusinessConfigService businessConfigService) {
        this.businessConfigService = businessConfigService;
    }

    @Override
    public List<String> names() {
        return List.of(V2Names.PLATFORM_INTERACTION);
    }

    @Override
    public void onEvent(V2Contexts.EventContext context) {
        String token = context.body() == null ? null : context.body().path("token").asText(null);
        if (token == null || token.isBlank()) {
            log.warn("业务交互上报缺少 token，已丢弃: device={}", context.deviceId());
            return;
        }
        businessConfigService.handleInteractionReport(context.deviceId(), token);
    }
}
