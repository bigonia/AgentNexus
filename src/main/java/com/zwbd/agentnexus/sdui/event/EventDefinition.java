package com.zwbd.agentnexus.sdui.event;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Configuration-backed SDUI event contract.
 *
 * Events are defined by sdui-event-catalog.yml and grouped into two domains:
 * command events and section events. Runtime payloads refer to these IDs; this
 * record describes their schema and constraints.
 */
public record EventDefinition(
        String eventId,
        EventKind kind,
        Direction direction,
        EventCategory category,
        String subtype,
        String displayName,
        String description,
        String sourceCapability,
        TransportInfo transport,
        List<ParamDef> payloadSchema,
        Map<String, Object> constraints,
        String parentEventId
) {

    public enum EventKind {
        COMMAND,
        SECTION
    }

    public enum Direction {
        INBOUND,
        OUTBOUND
    }

    public enum EventCategory {
        /** Outbound command — device action (e.g. reboot, set brightness). */
        COMMAND_DISPATCH("命令动作", false),
        /** Outbound platform capability (e.g. TTS, audio prompt). */
        COMMAND_PLATFORM("平台能力", false),
        /**
         * Internal lifecycle events (command ACK, timeout, audio streaming, STT progress).
         * NOT exposed as state-machine trigger options — purely for internal routing.
         */
        COMMAND_LIFECYCLE("内部生命周期", false),
        /** User-facing section interaction (button click, toggle, list select). */
        USER_INTERACTION("用户交互", true),
        /** System-level timer / cron events — available as state-machine triggers. */
        SYSTEM_EVENT("系统事件", true),
        /** Section render lifecycle (reserved, not currently used). */
        SECTION_RENDER("Section 渲染", false);

        private final String label;
        private final boolean publicTrigger;

        EventCategory(String label, boolean publicTrigger) {
            this.label = label;
            this.publicTrigger = publicTrigger;
        }

        public String label() {
            return label;
        }

        /** Whether events of this category should be shown as state-machine trigger options. */
        public boolean isPublicTrigger() {
            return publicTrigger;
        }

        /** Whether events of this category are internal only (not exposed in public APIs). */
        public boolean isInternal() {
            return !publicTrigger;
        }
    }

    public record TransportInfo(
            String protocol,
            String topic,
            String action,
            Integer msgType,
            Integer eventKind,
            String eventName,
            String nodeId
    ) {
        public Map<String, Object> toMap() {
            Map<String, Object> map = new LinkedHashMap<>();
            putIfPresent(map, "protocol", protocol);
            putIfPresent(map, "topic", topic);
            putIfPresent(map, "action", action);
            putIfPresent(map, "msgType", msgType);
            putIfPresent(map, "eventKind", eventKind);
            putIfPresent(map, "eventName", eventName);
            putIfPresent(map, "nodeId", nodeId);
            return map;
        }
    }

    public record ParamDef(
            String name,
            String type,
            boolean required,
            Object min,
            Object max,
            List<String> values,
            String description
    ) {
        public Map<String, Object> toMap() {
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("name", name);
            map.put("type", type);
            map.put("required", required);
            putIfPresent(map, "min", min);
            putIfPresent(map, "max", max);
            if (values != null && !values.isEmpty()) {
                map.put("values", values);
            }
            map.put("description", description != null ? description : "");
            return map;
        }
    }

    /** True for section events that are user-facing triggers (button click, toggle, etc.). */
    public boolean isSectionInteraction() {
        return kind == EventKind.SECTION && category == EventCategory.USER_INTERACTION;
    }

    /** True for internal command lifecycle events (ACK, timeout, audio streaming, etc.). */
    public boolean isCommandLifecycle() {
        return kind == EventKind.COMMAND && category == EventCategory.COMMAND_LIFECYCLE;
    }

    /** True if this event should be shown as a state-machine trigger option in public APIs. */
    public boolean isPublicTrigger() {
        return category.isPublicTrigger();
    }

    /** True if this event is internal-only (not for public API exposure). */
    public boolean isInternal() {
        return category.isInternal();
    }

    public boolean hasParent() {
        return parentEventId != null && !parentEventId.isBlank();
    }

    public String rootEventId() {
        return hasParent() ? parentEventId : eventId;
    }

    /** Full internal map — includes transport and all protocol details. */
    public Map<String, Object> toMap() {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("eventId", eventId);
        map.put("id", eventId);
        map.put("kind", kind.name());
        map.put("direction", direction.name());
        map.put("category", category.name());
        map.put("categoryLabel", category.label());
        map.put("subtype", subtype != null ? subtype : "");
        map.put("displayName", displayName);
        map.put("description", description != null ? description : "");
        map.put("sourceCapability", sourceCapability);
        map.put("transport", transport != null ? transport.toMap() : Map.of());
        map.put("payloadSchema", payloadSchema.stream().map(ParamDef::toMap).toList());
        map.put("constraints", constraints != null ? constraints : Map.of());
        map.put("parentEventId", parentEventId != null ? parentEventId : "");
        return map;
    }

    /**
     * Public API map — strips internal details (transport, protocol-level fields).
     * Suitable for state-machine editor and frontend consumption.
     */
    public Map<String, Object> toPublicMap() {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("eventId", eventId);
        map.put("kind", kind.name());
        map.put("category", category.name());
        map.put("categoryLabel", category.label());
        map.put("displayName", displayName);
        map.put("description", description != null ? description : "");
        map.put("sourceCapability", sourceCapability);
        map.put("payloadSchema", publicPayloadSchema());
        map.put("constraints", constraints != null ? constraints : Map.of());
        return map;
    }

    /**
     * Payload schema filtered for public consumption: excludes protocol-internal
     * fields ({@code nodeId}, {@code ts}) that have no meaning for state-machine
     * trigger conditions.
     */
    private List<Map<String, Object>> publicPayloadSchema() {
        return payloadSchema.stream()
                .filter(p -> !"nodeId".equals(p.name()) && !"ts".equals(p.name()))
                .map(ParamDef::toMap)
                .toList();
    }

    private static void putIfPresent(Map<String, Object> map, String key, Object value) {
        if (value != null) {
            map.put(key, value);
        }
    }
}
