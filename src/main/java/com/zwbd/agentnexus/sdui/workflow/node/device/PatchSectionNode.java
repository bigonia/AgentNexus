package com.zwbd.agentnexus.sdui.workflow.node.device;

import com.zwbd.agentnexus.sdui.protocol.SduiProtocolConstants;
import com.zwbd.agentnexus.sdui.protocol.SduiRuntimeHandlers;
import com.zwbd.agentnexus.sdui.section.*;
import com.zwbd.agentnexus.sdui.workflow.VariableResolver;
import com.zwbd.agentnexus.sdui.workflow.WorkflowPageRuntimeService;
import com.zwbd.agentnexus.sdui.workflow.node.CapabilityNode;
import com.zwbd.agentnexus.sdui.workflow.node.NodeContext;
import com.zwbd.agentnexus.sdui.workflow.node.NodeResult;
import com.zwbd.agentnexus.sdui.workflow.node.NodeSchema;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.*;

@Slf4j
@Component
@RequiredArgsConstructor
public class PatchSectionNode implements CapabilityNode {

    private final SectionOrchestrationService sectionService;
    private final DebugSectionWorkspaceService workspaceService;
    private final WorkflowPageRuntimeService pageRuntimeService;

    @Override
    public String type() { return "device.section.patch"; }

    @Override
    public NodeSchema schema() {
        return new NodeSchema(type(), "更新 Section", "向设备推送 Section 内容更新",
                "device", "layout-dashboard",
                List.of(
                        new NodeSchema.ParamDef("pageId", "string", false, null, "目标页面 ID；默认使用当前活动页面"),
                        new NodeSchema.ParamDef("sectionId", "string", false, null, "目标 Section ID；add 时可省略，由后端生成"),
                        new NodeSchema.ParamDef("operation", "string", false, "update", "patch 操作类型，默认 update"),
                        new NodeSchema.ParamDef("sectionType", "string", false, null, "Section 类型；add 时必填，update/remove 可省略"),
                        new NodeSchema.ParamDef("data", "object", true, null, "Section 数据内容")
                ),
                List.of(),
                false, 5000, null, "device", SduiProtocolConstants.NodeProtocols.SECTION_PATCH, SduiRuntimeHandlers.DEVICE_UI_SECTION,
                Map.of("requiresSectionType", true));
    }

    @Override
    public NodeResult execute(NodeContext ctx) {
        String pageId = (String) ctx.resolvedInputs().get("pageId");
        String sectionId = (String) ctx.resolvedInputs().get("sectionId");
        String operation = String.valueOf(ctx.resolvedInputs().getOrDefault("operation", "update"));
        String sectionType = (String) ctx.resolvedInputs().get("sectionType");
        Object dataObj = ctx.resolvedInputs().get("data");

        if ((sectionId == null || sectionId.isBlank()) && !"add".equals(operation)) {
            return NodeResult.error("Missing 'sectionId'");
        }

        if ((sectionType == null || sectionType.isBlank()) && !"add".equals(operation)) {
            String resolvedPageId = pageId;
            if (resolvedPageId == null || resolvedPageId.isBlank()) {
                resolvedPageId = workspaceService.getWorkflowActivePageId(ctx.deviceId());
            }
            sectionType = resolvedPageId != null
                    ? workspaceService.findWorkflowSectionType(ctx.deviceId(), resolvedPageId, sectionId)
                    : null;
        }
        if ((sectionType == null || sectionType.isBlank()) && !"remove".equals(operation)) {
            return NodeResult.error("Missing 'sectionType'");
        }

        @SuppressWarnings("unchecked")
        Map<String, Object> data = dataObj instanceof Map ? (Map<String, Object>) dataObj : Map.of();
        data = resolveDataValues(data, ctx);
        try {
            DebugSectionWorkspaceService.WorkflowPatchResult result =
                    workspaceService.applyWorkflowPatch(ctx.deviceId(), pageId, sectionId, operation, sectionType, data);
            sectionService.sendPatch(ctx.deviceId(), new SectionPatch(result.pageId(), List.of(result.patchEntry())));
            log.info("PatchSection sent: device={} page={} section={} type={}",
                    ctx.deviceId(), result.pageId(), result.sectionId(), sectionType);
            return NodeResult.completed(Map.of(
                    "page", result.pageId(),
                    "sectionId", result.sectionId(),
                    "sectionType", sectionType
            ));
        } catch (IllegalArgumentException e) {
            return NodeResult.error(e.getMessage());
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> resolveDataValues(Map<String, Object> data, NodeContext ctx) {
        Map<String, Object> resolved = new LinkedHashMap<>();
        for (var entry : data.entrySet()) {
            Object value = entry.getValue();
            if (value instanceof String s && s.contains("$")) {
                resolved.put(entry.getKey(), VariableResolver.resolveExpression(s,
                        ctx.instance().variablesAsMap(), ctx.triggerPayload(), ctx.env()));
            } else if (value instanceof Map) {
                resolved.put(entry.getKey(), resolveDataValues((Map<String, Object>) value, ctx));
            } else {
                resolved.put(entry.getKey(), value);
            }
        }
        return resolved;
    }
}
