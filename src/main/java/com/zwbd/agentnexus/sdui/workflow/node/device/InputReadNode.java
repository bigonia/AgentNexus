package com.zwbd.agentnexus.sdui.workflow.node.device;

import com.zwbd.agentnexus.sdui.event.EventPayload;
import com.zwbd.agentnexus.sdui.workflow.node.CapabilityNode;
import com.zwbd.agentnexus.sdui.workflow.node.NodeContext;
import com.zwbd.agentnexus.sdui.workflow.node.NodeResult;
import com.zwbd.agentnexus.sdui.workflow.node.NodeSchema;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Reads input data from a device event trigger payload.
 *
 * <h3>Data extraction order</h3>
 * <ol>
 *   <li>If {@link EventPayload} is available: reads {@code value} field first,
 *       then falls back to named fields via {@code field} param</li>
 *   <li>If only legacy triggerPayload Map is available: reads by {@code field} key,
 *       falls back to {@code virtualInputId} as key</li>
 * </ol>
 *
 * <h3>Input parameters</h3>
 * <ul>
 *   <li>{@code virtualInputId} — the virtual input ID from workflow definition</li>
 *   <li>{@code field} — specific field name to read from the payload
 *       (e.g. "value", "nodeId", "sectionId", "pageId")</li>
 *   <li>{@code save} — variable name to store (as {@code $data.<name>})</li>
 *   <li>{@code defaultValue} — fallback value when no data available</li>
 * </ul>
 */
@Slf4j
@Component
public class InputReadNode implements CapabilityNode {

    @Override
    public String type() { return "device.input.read"; }

    @Override
    public NodeSchema schema() {
        return new NodeSchema(type(), "读取输入", "从设备事件中读取输入数据并存储为变量",
                "device", "mouse-pointer-click",
                List.of(
                        new NodeSchema.ParamDef("virtualInputId", "string", true, null,
                                "虚拟输入 ID，对应终端上报的 input module"),
                        new NodeSchema.ParamDef("field", "string", false, null,
                                "读取事件 payload 中的字段名（如 value, nodeId, sectionId, pageId），默认读取 value"),
                        new NodeSchema.ParamDef("save", "string", true, null,
                                "结果保存到 $data.<name>"),
                        new NodeSchema.ParamDef("defaultValue", "string", false, null,
                                "默认值（事件数据不存在时使用）")
                ),
                List.of(new NodeSchema.ParamDef("value", "object", false, null, "读取到的值")),
                false, 3000);
    }

    @Override
    public NodeResult execute(NodeContext ctx) {
        String virtualInputId = (String) ctx.resolvedInputs().get("virtualInputId");
        String field = (String) ctx.resolvedInputs().get("field");
        String save = (String) ctx.resolvedInputs().get("save");
        String defaultValue = (String) ctx.resolvedInputs().get("defaultValue");

        if (save == null || save.isBlank()) {
            return NodeResult.error("'save' is required");
        }

        Object value = null;

        // Prefer structured EventPayload when available
        if (ctx.hasEventPayload()) {
            EventPayload ep = ctx.eventPayload();
            String lookupField = field != null ? field : "value";

            value = switch (lookupField) {
                case "value"     -> ep.value();
                case "nodeId"    -> ep.nodeId();
                case "sectionId" -> ep.sectionId();
                case "pageId"    -> ep.pageId();
                case "eventId"   -> ep.eventId();
                case "kind"      -> ep.kind();
                case "ts"        -> ep.ts();
                case "deviceId"  -> ep.deviceId();
                default          -> ep.rawFields().get(lookupField);
            };

            // If still null, try virtualInputId as a field name
            if (value == null && virtualInputId != null) {
                value = ep.rawFields().get(virtualInputId);
            }
        }

        // Fall back to legacy triggerPayload Map
        if (value == null) {
            Map<String, Object> payload = ctx.triggerPayload();
            if (payload != null && !payload.isEmpty()) {
                String lookupKey = field != null ? field : "value";
                value = payload.get(lookupKey);
                if (value == null && virtualInputId != null) {
                    value = payload.get(virtualInputId);
                }
            }
        }

        // Apply default
        if (value == null) {
            value = defaultValue;
        }

        ctx.instance().putVariable(save, value);
        log.info("InputRead: virtualInput={} field={} -> $data.{} = {}  (from {}payload)",
                virtualInputId, field, save, value,
                ctx.hasEventPayload() ? "EventPayload " : "legacy ");
        return NodeResult.completed(Map.of("value", value), Set.of(save));
    }
}
