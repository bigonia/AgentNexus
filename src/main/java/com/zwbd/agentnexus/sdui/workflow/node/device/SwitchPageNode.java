package com.zwbd.agentnexus.sdui.workflow.node.device;

import com.zwbd.agentnexus.sdui.workflow.VariableResolver;
import com.zwbd.agentnexus.sdui.workflow.node.CapabilityNode;
import com.zwbd.agentnexus.sdui.workflow.node.NodeContext;
import com.zwbd.agentnexus.sdui.workflow.node.NodeResult;
import com.zwbd.agentnexus.sdui.workflow.node.NodeSchema;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

@Component
public class SwitchPageNode implements CapabilityNode {

    @Override
    public String type() { return "device.switch_page"; }

    @Override
    public NodeSchema schema() {
        return new NodeSchema(type(), "切换页面", "切换设备当前显示的页面",
                "device", "arrow-right-circle",
                List.of(new NodeSchema.ParamDef("page", "string", true, null, "目标页面 ID")),
                List.of(),
                false, 2000);
    }

    @Override
    public NodeResult execute(NodeContext ctx) {
        String page = (String) ctx.resolvedInputs().get("page");
        ctx.instance().activePage(page);
        return NodeResult.completed(Map.of());
    }
}
