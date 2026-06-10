package com.zwbd.agentnexus.sdui.workflow.node.device;

import com.zwbd.agentnexus.sdui.protocol.SduiProtocolConstants;
import com.zwbd.agentnexus.sdui.protocol.SduiRuntimeHandlers;
import com.zwbd.agentnexus.sdui.workflow.WorkflowPageRuntimeService;
import com.zwbd.agentnexus.sdui.workflow.node.CapabilityNode;
import com.zwbd.agentnexus.sdui.workflow.node.NodeContext;
import com.zwbd.agentnexus.sdui.workflow.node.NodeResult;
import com.zwbd.agentnexus.sdui.workflow.node.NodeSchema;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

@Component
@RequiredArgsConstructor
public class SwitchPageNode implements CapabilityNode {

    private final WorkflowPageRuntimeService pageRuntimeService;

    @Override
    public String type() { return "device.page.switch"; }

    @Override
    public NodeSchema schema() {
        return new NodeSchema(type(), "切换页面", "切换设备当前显示的页面",
                "device", "arrow-right-circle",
                List.of(new NodeSchema.ParamDef("page", "string", true, null, "目标页面 ID")),
                List.of(),
                false, 2000, null, "device", SduiProtocolConstants.NodeProtocols.SECTION_SCENE, SduiRuntimeHandlers.DEVICE_UI_PAGE,
                Map.of("renderOnSwitch", true));
    }

    @Override
    public NodeResult execute(NodeContext ctx) {
        String page = (String) ctx.resolvedInputs().get("page");
        if (page == null || page.isBlank()) {
            return NodeResult.error("Missing 'page' input");
        }
        boolean sent = pageRuntimeService.renderPage(ctx.deviceId(), ctx.instance(), page, ctx.triggerPayload(), ctx.env());
        if (!sent) {
            return NodeResult.error("Failed to switch page: " + page);
        }
        return NodeResult.completed(Map.of("page", page, "switched", true));
    }
}
