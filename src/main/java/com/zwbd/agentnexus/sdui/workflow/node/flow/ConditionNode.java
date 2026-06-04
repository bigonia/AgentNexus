package com.zwbd.agentnexus.sdui.workflow.node.flow;

import com.zwbd.agentnexus.sdui.workflow.VariableResolver;
import com.zwbd.agentnexus.sdui.workflow.node.CapabilityNode;
import com.zwbd.agentnexus.sdui.workflow.node.NodeContext;
import com.zwbd.agentnexus.sdui.workflow.node.NodeResult;
import com.zwbd.agentnexus.sdui.workflow.node.NodeSchema;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

@Slf4j
@Component
public class ConditionNode implements CapabilityNode {

    @Override
    public String type() { return "flow.condition"; }

    @Override
    public NodeSchema schema() {
        return new NodeSchema(type(), "条件分支", "根据条件判断选择执行分支",
                "flow_control", "git-branch",
                List.of(
                        new NodeSchema.ParamDef("variable", "string", true, null, "判断变量"),
                        new NodeSchema.ParamDef("operator", "string", true, null, "运算符: eq/neq/gt/gte/lt/lte/contains/isEmpty"),
                        new NodeSchema.ParamDef("value", "string", false, null, "比较值"),
                        new NodeSchema.ParamDef("thenActions", "actions[]", false, null, "条件成立时执行的动作列表"),
                        new NodeSchema.ParamDef("elseActions", "actions[]", false, null, "条件不成立时执行的动作列表")
                ),
                List.of(new NodeSchema.ParamDef("result", "boolean", false, null, "条件判断结果")),
                false, 5000);
    }

    @Override
    public NodeResult execute(NodeContext ctx) {
        Object variable = ctx.resolvedInputs().get("variable");
        String operator = (String) ctx.resolvedInputs().get("operator");
        Object value = ctx.resolvedInputs().get("value");

        Object cond = VariableResolver.resolveExpression(
                variable != null ? variable.toString() : "",
                ctx.instance().variablesAsMap(), ctx.triggerPayload(), ctx.env());
        boolean match = evaluateCondition(cond, operator, value != null ? value.toString() : "",
                ctx.instance().variablesAsMap(), ctx.triggerPayload(), ctx.env());
        log.info("Condition: {} {} {} → {}", variable, operator, value, match);
        return NodeResult.completed(Map.of("result", match));
    }

    private boolean evaluateCondition(Object left, String operator, String rightExpr,
                                       Map<String, Object> data, Map<String, Object> trigger, Map<String, String> env) {
        Object right = VariableResolver.resolveExpression(rightExpr, data, trigger, env);
        if (left == null && right == null && "eq".equals(operator)) return true;
        if (left == null && right == null && "neq".equals(operator)) return false;
        if (left == null) return "neq".equals(operator);
        if (right == null) return "neq".equals(operator);

        if ("isEmpty".equals(operator)) {
            if (left instanceof String s) return s.isEmpty();
            if (left instanceof List<?> l) return l.isEmpty();
            if (left instanceof Map<?,?> m) return m.isEmpty();
            return false;
        }

        if (left instanceof Number nl && right instanceof Number nr) {
            double l = nl.doubleValue();
            double r = nr.doubleValue();
            return switch (operator) {
                case "eq" -> l == r;
                case "neq" -> l != r;
                case "gt" -> l > r;
                case "gte" -> l >= r;
                case "lt" -> l < r;
                case "lte" -> l <= r;
                default -> false;
            };
        }

        String ls = left.toString();
        String rs = right.toString();
        return switch (operator) {
            case "eq" -> ls.equals(rs);
            case "neq" -> !ls.equals(rs);
            case "contains" -> ls.contains(rs);
            default -> false;
        };
    }
}
