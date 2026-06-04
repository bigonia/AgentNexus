package com.zwbd.agentnexus.sdui.workflow.node.device;

import com.zwbd.agentnexus.sdui.workflow.node.CapabilityNode;
import com.zwbd.agentnexus.sdui.workflow.node.NodeContext;
import com.zwbd.agentnexus.sdui.workflow.node.NodeResult;
import com.zwbd.agentnexus.sdui.workflow.node.NodeSchema;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

@Component
public class UpdatePageNode implements CapabilityNode {

    @Override
    public String type() { return "device.page.update"; }

    @Override
    public NodeSchema schema() {
        return new NodeSchema(type(), "更新页面", "向设备下发完整页面渲染内容",
                "device", "layout",
                List.of(new NodeSchema.ParamDef("page", "string", true, null, "页面 ID")),
                List.of(),
                false, 5000);
    }

    @Override
    public NodeResult execute(NodeContext ctx) {
        String page = (String) ctx.resolvedInputs().get("page");
        // Page rendering is handled by ActionExecutor.buildPageScene
        // This node primarily registers the action type for the editor
        ctx.instance().activePage(page);
        return NodeResult.completed(Map.of());
    }
}
