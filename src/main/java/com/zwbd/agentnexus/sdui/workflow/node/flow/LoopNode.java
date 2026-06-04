package com.zwbd.agentnexus.sdui.workflow.node.flow;

import com.zwbd.agentnexus.sdui.workflow.VariableResolver;
import com.zwbd.agentnexus.sdui.workflow.node.CapabilityNode;
import com.zwbd.agentnexus.sdui.workflow.node.NodeContext;
import com.zwbd.agentnexus.sdui.workflow.node.NodeResult;
import com.zwbd.agentnexus.sdui.workflow.node.NodeSchema;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@Component
public class LoopNode implements CapabilityNode {

    @Override
    public String type() { return "flow.loop"; }

    @Override
    public NodeSchema schema() {
        return new NodeSchema(type(), "循环", "重复执行步骤直到满足退出条件",
                "flow_control", "repeat",
                List.of(
                        new NodeSchema.ParamDef("mode", "string", true, "count", "循环模式: count | while | until"),
                        new NodeSchema.ParamDef("count", "number", false, null, "固定次数 (mode=count)"),
                        new NodeSchema.ParamDef("condition", "string", false, null, "条件表达式 (mode=while/until)，支持 $data.xxx"),
                        new NodeSchema.ParamDef("maxIterations", "number", false, "1000", "最大迭代次数防止死循环"),
                        new NodeSchema.ParamDef("steps", "actions[]", true, null, "循环体动作列表")
                ),
                List.of(
                        new NodeSchema.ParamDef("iterations", "number", false, null, "实际执行的迭代次数"),
                        new NodeSchema.ParamDef("results", "object[]", false, null, "每次迭代的输出结果数组")
                ),
                false, 0);
    }

    @Override
    public NodeResult execute(NodeContext ctx) {
        String mode = (String) ctx.resolvedInputs().getOrDefault("mode", "count");
        int maxIterations = getInt(ctx.resolvedInputs(), "maxIterations", 1000);
        List<Object> results = new ArrayList<>();
        int iteration = 0;

        switch (mode) {
            case "count" -> {
                int count = getInt(ctx.resolvedInputs(), "count", 1);
                for (int i = 0; i < count && i < maxIterations; i++) {
                    iteration++;
                    results.add(Map.of("index", i));
                }
            }
            case "while" -> {
                String conditionExpr = (String) ctx.resolvedInputs().get("condition");
                if (conditionExpr == null) {
                    return NodeResult.error("'condition' is required for while loop mode");
                }
                while (evaluateCondition(conditionExpr, ctx) && iteration < maxIterations) {
                    iteration++;
                    results.add(Map.of("index", iteration));
                }
            }
            case "until" -> {
                String conditionExpr = (String) ctx.resolvedInputs().get("condition");
                if (conditionExpr == null) {
                    return NodeResult.error("'condition' is required for until loop mode");
                }
                do {
                    iteration++;
                    results.add(Map.of("index", iteration));
                } while (!evaluateCondition(conditionExpr, ctx) && iteration < maxIterations);
            }
            default -> {
                return NodeResult.error("Unknown loop mode: " + mode);
            }
        }

        return NodeResult.completed(Map.of("iterations", iteration, "results", results));
    }

    private boolean evaluateCondition(String expr, NodeContext ctx) {
        Object resolved = VariableResolver.resolveExpression(expr,
                ctx.instance().variablesAsMap(), ctx.triggerPayload(), ctx.env());
        if (resolved instanceof Boolean b) return b;
        if (resolved instanceof Number n) return n.doubleValue() != 0;
        if (resolved instanceof String s) return !s.isEmpty() && !"false".equalsIgnoreCase(s);
        return resolved != null;
    }

    private int getInt(Map<String, Object> map, String key, int def) {
        Object val = map.get(key);
        if (val instanceof Number n) return n.intValue();
        if (val instanceof String s) {
            try { return Integer.parseInt(s); } catch (NumberFormatException ignored) {}
        }
        return def;
    }
}
