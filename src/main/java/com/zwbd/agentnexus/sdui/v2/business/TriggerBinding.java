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
 * @param triggerId  例如 {@code button.ok}、{@code platform.trigger}
 * @param source     Trigger 来源
 * @param token      仅 {@link TriggerSource#PLATFORM} 需要，平台生成
 * @param responses  有序响应序列
 * @param contextRef 平台侧上下文引用；终端不感知，{@code null} 表示无业务上下文
 */
public record TriggerBinding(String triggerId, TriggerSource source, String token,
                             List<ResponseStep> responses, String contextRef) {

    public TriggerBinding {
        responses = responses == null ? List.of() : List.copyOf(responses);
    }

    /**
     * 不带平台上下文引用的绑定。
     *
     * <p>01§4 说平台"根据设备和 token 恢复业务上下文"。token 注册表里记录的
     * {@code contextRef} 就是恢复用的抓手：工作流部署时写入 {@code wf:<workflowId>:<triggerNodeId>}，
     * 交互上报回流后不必再去反查"哪次部署用了这个 triggerId"。不参与业务流程的绑定留空。</p>
     */
    public TriggerBinding(String triggerId, TriggerSource source, String token, List<ResponseStep> responses) {
        this(triggerId, source, token, responses, null);
    }

    public boolean isPlatformTrigger() {
        return source == TriggerSource.PLATFORM;
    }

    public boolean hasContext() {
        return contextRef != null && !contextRef.isBlank();
    }

    /**
     * 供终端使用的 wire 形式。
     *
     * <p>刻意不含 {@code contextRef}：它是平台内部用来恢复业务上下文的引用，终端只保存并回传 token，
     * 不感知也不应携带业务语义（01§4）。</p>
     */
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
        return new TriggerBinding(triggerId, source, newToken, responses, contextRef);
    }

    public TriggerBinding withResponses(List<ResponseStep> newResponses) {
        return new TriggerBinding(triggerId, source, token, newResponses, contextRef);
    }
}
