package com.zwbd.agentnexus.sdui.capability.node;

import com.fasterxml.jackson.annotation.JsonValue;

public enum CapabilityNodeRuntimeMode {
    TRIGGER("trigger"),
    ACTION("action"),
    SESSION("session"),
    UI_PATCH("ui_patch");

    private final String value;

    CapabilityNodeRuntimeMode(String value) {
        this.value = value;
    }

    @JsonValue
    public String value() {
        return value;
    }
}
