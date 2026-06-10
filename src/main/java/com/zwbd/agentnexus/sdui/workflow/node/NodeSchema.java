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
        long timeoutMs,
        Boolean deviceSupported,
        String source,
        String protocol,
        String runtimeHandler,
        Map<String, Object> constraints
) {
    public NodeSchema(String type, String displayName, String description, String category,
                      String icon, List<ParamDef> inputs, List<ParamDef> outputs,
                      boolean suspendable, long timeoutMs) {
        this(type, displayName, description, category, icon, inputs, outputs,
                suspendable, timeoutMs, null, defaultSource(category), null, null, Map.of());
    }

    public NodeSchema(String type, String displayName, String description, String category,
                      String icon, List<ParamDef> inputs, List<ParamDef> outputs,
                      boolean suspendable, long timeoutMs, Boolean deviceSupported) {
        this(type, displayName, description, category, icon, inputs, outputs,
                suspendable, timeoutMs, deviceSupported, defaultSource(category), null, null, Map.of());
    }

    public NodeSchema(String type, String displayName, String description, String category,
                      String icon, List<ParamDef> inputs, List<ParamDef> outputs,
                      boolean suspendable, long timeoutMs, Boolean deviceSupported,
                      String source, String protocol, String runtimeHandler,
                      Map<String, Object> constraints) {
        this.type = type;
        this.displayName = displayName;
        this.description = description;
        this.category = category;
        this.icon = icon;
        this.inputs = inputs;
        this.outputs = outputs;
        this.suspendable = suspendable;
        this.timeoutMs = timeoutMs;
        this.deviceSupported = deviceSupported;
        this.source = source;
        this.protocol = protocol;
        this.runtimeHandler = runtimeHandler;
        this.constraints = constraints != null ? constraints : Map.of();
    }

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

    private static String defaultSource(String category) {
        if ("device".equals(category)) {
            return "device";
        }
        if ("platform".equals(category)) {
            return "platform";
        }
        return "workflow";
    }
}
