package com.zwbd.agentnexus.sdui.protocol.catalog;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public record FieldSpec(
        String name,
        String type,
        String label,
        Object defaultValue,
        Integer min,
        Integer max,
        List<String> options,
        String description,
        boolean required,
        List<FieldSpec> children
) {
    /** Minimal constructor for command params / event payload (name + type only). */
    public FieldSpec(String name, String type) {
        this(name, type, null, null, null, null, null, null, false, List.of());
    }

    /** Convenience constructor for simple fields without constraints. */
    public FieldSpec(String name, String type, String label, Object defaultValue) {
        this(name, type, label, defaultValue, null, null, null, null, false, List.of());
    }

    /** Legacy constructor — name + type + children only. */
    public FieldSpec(String name, String type, List<FieldSpec> children) {
        this(name, type, null, null, null, null, null, null, false, children);
    }

    public Map<String, Object> toMap() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("name", name);
        m.put("type", type);
        if (label != null) m.put("label", label);
        if (defaultValue != null) m.put("default", defaultValue);
        m.put("required", required);
        if (min != null) m.put("min", min);
        if (max != null) m.put("max", max);
        if (options != null && !options.isEmpty()) m.put("options", options);
        if (description != null) m.put("description", description);
        if (children != null) m.put("children", children.stream().map(FieldSpec::toMap).toList());
        else m.put("children", List.of());
        return m;
    }
}
