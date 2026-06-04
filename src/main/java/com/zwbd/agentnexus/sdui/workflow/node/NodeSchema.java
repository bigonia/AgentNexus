package com.zwbd.agentnexus.sdui.workflow.node;

import java.util.List;
import java.util.Map;

/**
 * Input/output contract for a capability node.
 * Describes what parameters the node accepts and what outputs it produces,
 * so the frontend editor can render configuration panels and the runtime can validate bindings.
 */
public record NodeSchema(
        String type,
        String displayName,
        String description,
        String category,
        String icon,
        List<ParamDef> inputs,
        List<ParamDef> outputs,
        boolean suspendable,
        long timeoutMs
) {
    public record ParamDef(
            String name,
            String type,
            boolean required,
            Object defaultValue,
            String description,
            Map<String, Object> constraints
    ) {
        public ParamDef(String name, String type, boolean required, Object defaultValue, String description) {
            this(name, type, required, defaultValue, description, Map.of());
        }
    }

    public boolean isSuspendable() { return suspendable; }
    public long timeoutMs() { return timeoutMs; }
}
