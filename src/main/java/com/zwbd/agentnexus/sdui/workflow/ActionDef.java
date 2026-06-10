package com.zwbd.agentnexus.sdui.workflow;

import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;

import java.util.List;
import java.util.Map;

/**
 * Sealed action hierarchy.
 *
 * NodeActionDef is the unified output node — it references a CapabilityNode type
 * and carries typed parameters. The frontend renders dropdown selections for
 * nodeType and form fields for params based on the node's NodeSchema.
 */
@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "type")
@JsonSubTypes({
        @JsonSubTypes.Type(value = ActionDef.NodeActionDef.class, name = "node"),
        @JsonSubTypes.Type(value = ActionDef.FetchAction.class, name = "fetch"),
        @JsonSubTypes.Type(value = ActionDef.ConditionAction.class, name = "condition"),
        @JsonSubTypes.Type(value = ActionDef.SequenceAction.class, name = "sequence"),
        @JsonSubTypes.Type(value = ActionDef.SetVariableAction.class, name = "set_variable")
})
public sealed interface ActionDef permits ActionDef.NodeActionDef,
        ActionDef.FetchAction, ActionDef.ConditionAction,
        ActionDef.SequenceAction, ActionDef.SetVariableAction {

    /**
     * Unified device output node. References a CapabilityNode type and its params.
     * Frontend renders nodeType as a dropdown, params as form fields from NodeSchema.
     *
     * Example: {"type":"node", "nodeType":"device.control", "params":{"command":"rgb.effect.set"}}
     */
    record NodeActionDef(String nodeType, Map<String, Object> params) implements ActionDef {}

    record FetchAction(String url, String method, String body, String save) implements ActionDef {}

    record ConditionAction(
            String variable,
            String operator,
            String value,
            List<ActionDef> thenActions,
            List<ActionDef> elseActions
    ) implements ActionDef {}

    record SequenceAction(List<ActionDef> steps) implements ActionDef {}

    record SetVariableAction(String variable, String value) implements ActionDef {}
}
