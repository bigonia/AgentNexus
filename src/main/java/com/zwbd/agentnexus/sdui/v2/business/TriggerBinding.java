package com.zwbd.agentnexus.sdui.v2.business;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 一条 Trigger → Response 序列的绑定。
 *
 * <p>01_INTERACTION_MODEL.md §3：云端 Trigger "由平台通过 Socket 请求触发，并携带平台预先配置的
 * 不透明 {@code token}"；token 本身就是云端 Trigger 的控制参数，不再增加额外开关、业务类型或
 * 动态动作参数。</p>
 *
 * @param triggerId 例如 {@code button.ok}、{@code platform.trigger}
 * @param source    Trigger 来源
 * @param token     仅 {@link TriggerSource#PLATFORM} 需要，平台生成
 * @param responses 有序响应序列
 */
public record TriggerBinding(String triggerId, TriggerSource source, String token, List<ResponseStep> responses) {

    public TriggerBinding {
        responses = responses == null ? List.of() : List.copyOf(responses);
    }

    public boolean isPlatformTrigger() {
        return source == TriggerSource.PLATFORM;
    }

    /** 供终端使用的 wire 形式。 */
    public Map<String, Object> toWire() {
        Map<String, Object> wire = new LinkedHashMap<>();
        wire.put("triggerId", triggerId);
        wire.put("source", source != null ? source.wire() : null);
        if (token != null && !token.isBlank()) {
            wire.put("token", token);
        }
        wire.put("responses", responses.stream().map(ResponseStep::toWire).toList());
        return wire;
    }

    public TriggerBinding withToken(String newToken) {
        return new TriggerBinding(triggerId, source, newToken, responses);
    }

    public TriggerBinding withResponses(List<ResponseStep> newResponses) {
        return new TriggerBinding(triggerId, source, token, newResponses);
    }
}
