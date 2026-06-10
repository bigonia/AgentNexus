package com.zwbd.agentnexus.sdui.event;

import java.util.List;
import java.util.Map;

/**
 * Unified event type definition for the SDUI event system.
 *
 * Replaces the previously scattered string-based event definitions
 * (capability-catalog.yml strings, SectionTypeCatalog.InteractionEvent,
 *  CapabilityRegistry.knownEvents Set) with a single typed contract.
 *
 * <h3>Event ID naming convention</h3>
 * All event IDs follow: {@code <category>:<capability>.<specific-event>}
 * <pre>
 *   ui:action.click             — action_section button click
 *   input:buttons.pwr.single_click
 *   input:motion.imu.shake
 *   input:audio.record.audio.record.data
 *   display:brightness.set
 *   rgb:effect.set
 * </pre>
 *
 * <h3>Direction</h3>
 * <ul>
 *   <li><b>INBOUND</b> — device → server (button press, sensor, audio, section interaction)</li>
 *   <li><b>OUTBOUND</b> — server → device (command, section render, audio play, RGB control)</li>
 * </ul>
 *
 * <h3>EventCategory</h3>
 * Groups events by their source domain for structured display in the
 * workflow editor and organized lookup in {@link EventRegistry}.
 */
public record EventDefinition(
        /** Globally unique, namespaced event ID (e.g. "ui:action.click"). */
        String eventId,

        /** Direction of event flow. */
        Direction direction,

        /** Domain category for grouping and display. */
        EventCategory category,

        /** Human-readable short name (e.g. "按钮点击", "RGB 设置"). */
        String displayName,

        /** Human-readable explanation of what this event represents. */
        String description,

        /** Capability name this event originates from or targets
         *  (e.g. "buttons.pwr", "action_section", "rgb.effect"). */
        String sourceCapability,

        /** How this event travels over the wire. */
        TransportInfo transport,

        /** For INBOUND: fields the event carries.
         *  For OUTBOUND: parameters the command requires. */
        List<ParamDef> payloadSchema,

        /** For SECTION-level events that contain sub-events at ELEMENT level,
         *  this references the parent eventId. Null for top-level events. */
        String parentEventId
) {

    // ── Nested types ──

    public enum Direction {
        INBOUND,
        OUTBOUND
    }

    public enum EventCategory {
        /** Physical button events (pwr, plus, etc.) */
        HARDWARE_BUTTON("物理按钮"),
        /** IMU / motion sensor events */
        HARDWARE_SENSOR("运动传感器"),
        /** Section UI interaction events (click, select, toggle, confirm) */
        SECTION_INTERACTION("Section 交互"),
        /** Audio recording / STT result events */
        AUDIO_INPUT("音频输入"),
        /** Device lifecycle events (connect, disconnect, heartbeat) */
        SYSTEM("系统状态"),
        /** Display commands (render, patch, brightness) */
        DISPLAY("显示控制"),
        /** Audio output commands (prompt, TTS, volume) */
        AUDIO_OUTPUT("音频输出"),
        /** RGB lighting commands */
        LIGHTING("灯光控制");

        private final String label;

        EventCategory(String label) { this.label = label; }

        public String label() { return label; }
    }

    /**
     * Describes how an event travels between device and server.
     */
    public record TransportInfo(
            /** "ui3_binary" | "json_topic" | "internal" */
            String protocol,
            /** For json_topic: the topic string (e.g. "cmd/control", "motion") */
            String topic,
            /** For json_topic: the action field within the payload */
            String action,
            /** For ui3_binary: the msgType byte in the binary frame header */
            Integer msgType,
            /** For ui3_binary EVENT_INPUT: the eventKind value in TLV 120 */
            Integer eventKind
    ) {
        public static TransportInfo ui3Binary(int msgType, int eventKind) {
            return new TransportInfo("ui3_binary", null, null, msgType, eventKind);
        }

        public static TransportInfo ui3Binary(int msgType) {
            return new TransportInfo("ui3_binary", null, null, msgType, null);
        }

        public static TransportInfo jsonTopic(String topic) {
            return new TransportInfo("json_topic", topic, null, null, null);
        }

        public static TransportInfo jsonTopic(String topic, String action) {
            return new TransportInfo("json_topic", topic, action, null, null);
        }

        public static TransportInfo internal() {
            return new TransportInfo("internal", null, null, null, null);
        }

        public static TransportInfo serverSide() {
            return new TransportInfo("server", null, null, null, null);
        }
    }

    /**
     * Parameter/field definition for payload schemas.
     * Mirrors the pattern from SectionTypeCatalog.ParamDef.
     */
    public record ParamDef(
            String name,
            String type,
            boolean required,
            String description
    ) {
        public ParamDef(String name, String type, String description) {
            this(name, type, false, description);
        }

        /** Serialize to a frontend-friendly map. */
        public Map<String, Object> toMap() {
            return Map.of(
                    "name", name,
                    "type", type,
                    "required", required,
                    "description", description != null ? description : ""
            );
        }
    }

    // ── Factory methods ──

    /**
     * Create an inbound event definition from a capability catalog input entry.
     */
    public static EventDefinition inbound(
            String eventId, EventCategory category, String displayName, String description,
            String sourceCapability, TransportInfo transport, List<ParamDef> payloadSchema) {
        return new EventDefinition(eventId, Direction.INBOUND, category,
                displayName, description, sourceCapability, transport, payloadSchema, null);
    }

    /**
     * Create an inbound event with a parent event (ELEMENT-level under a SECTION-level event).
     */
    public static EventDefinition inboundChild(
            String eventId, EventCategory category, String displayName, String description,
            String sourceCapability, TransportInfo transport, List<ParamDef> payloadSchema,
            String parentEventId) {
        return new EventDefinition(eventId, Direction.INBOUND, category,
                displayName, description, sourceCapability, transport, payloadSchema, parentEventId);
    }

    /**
     * Create an outbound event definition (command).
     */
    public static EventDefinition outbound(
            String eventId, EventCategory category, String displayName, String description,
            String sourceCapability, TransportInfo transport, List<ParamDef> payloadSchema) {
        return new EventDefinition(eventId, Direction.OUTBOUND, category,
                displayName, description, sourceCapability, transport, payloadSchema, null);
    }

    // ── Helpers ──

    /** @return true if this is a section interaction event. */
    public boolean isSectionInteraction() {
        return category == EventCategory.SECTION_INTERACTION;
    }

    /** @return true if this event has sub-events at ELEMENT level. */
    public boolean hasParent() {
        return parentEventId != null && !parentEventId.isBlank();
    }

    /** @return the top-level parent event ID, or this eventId if already top-level. */
    public String rootEventId() {
        return hasParent() ? parentEventId : eventId;
    }

    /** Serialize to a frontend-friendly map for the workflow editor. */
    public Map<String, Object> toMap() {
        return Map.of(
                "eventId", eventId,
                "direction", direction.name(),
                "category", category.name(),
                "displayName", displayName,
                "description", description != null ? description : "",
                "sourceCapability", sourceCapability,
                "transport", transport.protocol(),
                "payloadSchema", payloadSchema.stream().map(ParamDef::toMap).toList(),
                "parentEventId", parentEventId != null ? parentEventId : ""
        );
    }
}
