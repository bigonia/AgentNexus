package com.zwbd.agentnexus.sdui.workflow.node.device;

import com.zwbd.agentnexus.sdui.workflow.DeviceMessage;
import com.zwbd.agentnexus.sdui.workflow.VariableResolver;
import com.zwbd.agentnexus.sdui.workflow.WorkflowService;
import com.zwbd.agentnexus.sdui.workflow.node.CapabilityNode;
import com.zwbd.agentnexus.sdui.workflow.node.NodeContext;
import com.zwbd.agentnexus.sdui.workflow.node.NodeResult;
import com.zwbd.agentnexus.sdui.workflow.node.NodeSchema;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

@Component
public class MessageSendNode implements CapabilityNode {

    private final WorkflowService workflowService;

    public MessageSendNode(@Lazy WorkflowService workflowService) {
        this.workflowService = workflowService;
    }

    @Override
    public String type() { return "device.message.send"; }

    @Override
    public NodeSchema schema() {
        return new NodeSchema(type(), "发送消息", "向目标设备发送消息，触发其 device_message 工作流",
                "device", "send",
                List.of(
                        new NodeSchema.ParamDef("target", "string", true, null, "目标设备 ID"),
                        new NodeSchema.ParamDef("messageType", "string", false, "text", "消息类型: text | voice | alert | command"),
                        new NodeSchema.ParamDef("payload", "object", false, null, "消息内容，支持 $data.xxx / $trigger.xxx")
                ),
                List.of(),
                false, 5000);
    }

    @SuppressWarnings("unchecked")
    @Override
    public NodeResult execute(NodeContext ctx) {
        String target = (String) ctx.resolvedInputs().get("target");
        if (target == null || target.isEmpty()) {
            return NodeResult.error("target device ID is required");
        }

        String messageType = (String) ctx.resolvedInputs().getOrDefault("messageType", "text");
        Object rawPayload = ctx.resolvedInputs().get("payload");
        Map<String, Object> payload;
        if (rawPayload instanceof Map<?, ?> m) {
            payload = (Map<String, Object>) m;
        } else {
            payload = Map.of("text", rawPayload != null ? rawPayload.toString() : "");
        }

        Map<String, Object> resolvedPayload = new java.util.LinkedHashMap<>();
        for (var entry : payload.entrySet()) {
            Object val = entry.getValue();
            if (val instanceof String s) {
                Object resolved = VariableResolver.resolveExpression(s,
                        ctx.instance().variablesAsMap(), ctx.triggerPayload(), ctx.env());
                resolvedPayload.put(entry.getKey(), resolved != null ? resolved : s);
            } else {
                resolvedPayload.put(entry.getKey(), val);
            }
        }

        DeviceMessage message = new DeviceMessage(messageType, "device",
                ctx.deviceId(), target, resolvedPayload);
        Map<String, Object> result = workflowService.receiveMessage(target, message);
        return NodeResult.completed(result);
    }
}
