package com.zwbd.agentnexus.sdui.event;

import com.zwbd.agentnexus.sdui.protocol.BinaryProtocolCodec;
import com.zwbd.agentnexus.sdui.protocol.BinaryProtocolCodec.DecodedFrame;
import com.zwbd.agentnexus.sdui.protocol.BinaryProtocolCodec.TlvEntry;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Function;

/**
 * Runtime event instance — a concrete occurrence of an {@link EventDefinition}.
 *
 * Replaces the raw {@code Map<String, Object>} that was previously used for
 * trigger payloads throughout the workflow system. Provides typed access to
 * all contextual fields that events may carry.
 *
 * <h3>Key fields</h3>
 * <ul>
 *   <li>{@code pageId} — the page on which the event occurred (NEW)</li>
 *   <li>{@code sectionId} — the section that emitted the event (NEW, was missing)</li>
 *   <li>{@code value} — event-specific data like toggle state (NEW)</li>
 * </ul>
 *
 * <h3>Factory methods</h3>
 * Use {@link #fromBinaryInput} for UI3 binary events (msgType=9). It extracts
 * raw protocol fields only; {@link EventRegistry} resolves the configured event ID.
 * Use {@link #fromJsonTopic} for JSON-topic events such as audio.
 * Use {@link #minimal} when only deviceId and eventId are known.
 */
public record EventPayload(
        /** The namespaced event ID (e.g. "ui:action.click"). May be null for legacy events. */
        String eventId,

        /** Device that produced this event. */
        String deviceId,

        /** Page on which the event occurred. May be empty if not applicable. */
        String pageId,

        /** Section that emitted the event. May be empty for device-level events. */
        String sectionId,

        /** Interactive element ID (button ID, list item ID, toggle option ID). */
        String nodeId,

        /** Numeric event kind from the binary protocol (TLV 120). */
        int kind,

        /** Event-specific value (toggle state, input text, etc.). */
        Object value,

        /** Timestamp in milliseconds. */
        long ts,

        /** Additional raw fields for forward compatibility. */
        Map<String, Object> rawFields
) {

    // TLV type constants are defined in BinaryProtocolCodec (authoritative source).
    // Re-exported here for convenience:
    public static final int TLV_KIND      = BinaryProtocolCodec.TLV_EVENT_KIND;
    public static final int TLV_NODE_ID   = BinaryProtocolCodec.TLV_NODE_ID;
    public static final int TLV_EVENT_NAME = BinaryProtocolCodec.TLV_EVENT_NAME;
    public static final int TLV_SECTION_ID = BinaryProtocolCodec.TLV_SECTION_ID;
    public static final int TLV_PAGE_ID   = BinaryProtocolCodec.TLV_PAGE_ID;
    public static final int TLV_TS        = BinaryProtocolCodec.TLV_TS;
    public static final int TLV_VALUE     = BinaryProtocolCodec.TLV_EVENT_VALUE;

    // ── Factory methods ──

    /**
     * Build an EventPayload from a UI3 binary EVENT_INPUT frame (msgType=9).
     * Extracts standard TLVs and any new ones present in the frame.
     *
     * @param deviceId resolved device ID from the WebSocket session
     * @param frame    the decoded binary frame
     * @return a fully populated EventPayload
     */
    public static EventPayload fromBinaryInput(String deviceId, DecodedFrame frame) {
        int kind = findTlvInt(frame, TLV_KIND, 0);
        String nodeId = findTlv(frame, TLV_NODE_ID, TlvEntry::asString);
        String eventName = findTlv(frame, TLV_EVENT_NAME, TlvEntry::asString);
        String sectionId = findTlv(frame, TLV_SECTION_ID, TlvEntry::asString);
        String pageId = findTlv(frame, TLV_PAGE_ID, TlvEntry::asString);
        long ts = findTlvLong(frame, TLV_TS, System.currentTimeMillis());
        Object value = extractValue(frame, TLV_VALUE);

        // Collect any additional TLVs not covered above as raw fields
        Map<String, Object> rawFields = new LinkedHashMap<>();
        for (TlvEntry tlv : frame.tlvs()) {
            int type = tlv.type();
            if (type != TLV_KIND && type != TLV_NODE_ID && type != TLV_EVENT_NAME
                    && type != TLV_SECTION_ID && type != TLV_PAGE_ID
                    && type != TLV_TS && type != TLV_VALUE) {
                rawFields.put("tlv_" + type, tlvValueToString(tlv));
            }
        }
        if (nodeId != null) rawFields.put("nodeId", nodeId);
        if (eventName != null) rawFields.put("eventName", eventName);

        return new EventPayload(
                eventName,
                deviceId,
                pageId != null ? pageId : "",
                sectionId != null ? sectionId : "",
                nodeId != null ? nodeId : "",
                kind,
                value,
                ts,
                rawFields
        );
    }

    /**
     * Build an EventPayload from a JSON-topic message.
     *
     * @param eventId the resolved event ID
     * @param deviceId the device
     * @param payload the JSON payload map
     */
    public static EventPayload fromJsonTopic(String eventId, String deviceId,
                                              Map<String, Object> payload) {
        String sectionId = strVal(payload, "sectionId", "");
        String nodeId = strVal(payload, "nodeId", "");
        long ts = longVal(payload, "ts", System.currentTimeMillis());
        Object value = payload.get("value");
        int kind = intVal(payload, "kind", 0);
        String pageId = strVal(payload, "pageId", "");

        return new EventPayload(eventId, deviceId, pageId, sectionId,
                nodeId, kind, value, ts, new LinkedHashMap<>(payload));
    }

    /**
     * Minimal payload for events where only deviceId and eventId are known.
     */
    public static EventPayload minimal(String eventId, String deviceId) {
        return new EventPayload(eventId, deviceId, "", "", "", 0, null,
                System.currentTimeMillis(), Map.of());
    }

    /**
     * Create a legacy-compatible payload from a raw map (for backward compat
     * with existing callers that still use Map-based trigger payloads).
     */
    public static EventPayload fromLegacyMap(String deviceId, String eventName,
                                              Map<String, Object> payload) {
        return new EventPayload(
                eventName,
                deviceId,
                strVal(payload, "pageId", ""),
                strVal(payload, "sectionId", ""),
                strVal(payload, "nodeId", ""),
                intVal(payload, "kind", 0),
                payload.get("value"),
                longVal(payload, "ts", System.currentTimeMillis()),
                new LinkedHashMap<>(payload)
        );
    }

    // ── Conversion ──

    /**
     * Convert to a legacy {@code Map<String, Object>} for backward compatibility
     * with existing code that expects trigger payloads as maps.
     */
    public Map<String, Object> toLegacyMap() {
        Map<String, Object> m = new LinkedHashMap<>(rawFields);
        m.putIfAbsent("eventName", eventId);
        m.putIfAbsent("nodeId", nodeId);
        m.putIfAbsent("kind", kind);
        m.putIfAbsent("ts", ts);
        if (!pageId.isEmpty()) m.put("pageId", pageId);
        if (!sectionId.isEmpty()) m.put("sectionId", sectionId);
        if (value != null) m.put("value", value);
        return m;
    }

    public EventPayload withEventId(String resolvedEventId) {
        return new EventPayload(
                resolvedEventId,
                deviceId,
                pageId,
                sectionId,
                nodeId,
                kind,
                value,
                ts,
                rawFields
        );
    }

    // ── Query helpers ──

    /** @return true if this event has section-level context. */
    public boolean hasSectionContext() {
        return !sectionId.isEmpty();
    }

    /** @return true if this event carries a value payload. */
    public boolean hasValue() {
        return value != null;
    }

    /** Get value as a string, or null. */
    public String valueAsString() {
        return value != null ? value.toString() : null;
    }

    /** Get value as a boolean (for toggle events). */
    public boolean valueAsBoolean() {
        if (value instanceof Boolean b) return b;
        if (value instanceof String s) return Boolean.parseBoolean(s);
        if (value instanceof Number n) return n.intValue() != 0;
        return false;
    }

    // ── Internal helpers ──

    private static int findTlvInt(DecodedFrame frame, int type, int defaultValue) {
        return frame.tlvs().stream()
                .filter(t -> t.type() == type)
                .findFirst()
                .map(t -> {
                    if (t.value().length == 1) return t.asU8();
                    if (t.value().length == 2) return t.asU16();
                    return (int) t.asU32();
                })
                .orElse(defaultValue);
    }

    private static long findTlvLong(DecodedFrame frame, int type, long defaultValue) {
        return frame.tlvs().stream()
                .filter(t -> t.type() == type)
                .findFirst()
                .map(TlvEntry::asU32)
                .map(Long::valueOf)
                .orElse(defaultValue);
    }

    private static <T> T findTlv(DecodedFrame frame, int type, Function<TlvEntry, T> extractor) {
        return frame.tlvs().stream()
                .filter(t -> t.type() == type)
                .findFirst().map(extractor).orElse(null);
    }

    private static Object extractValue(DecodedFrame frame, int type) {
        return frame.tlvs().stream()
                .filter(t -> t.type() == type)
                .findFirst()
                .map(t -> {
                    if (t.value().length == 1) return t.asU8();
                    if (t.value().length == 2) return t.asU16();
                    if (t.value().length <= 4) return (int) t.asU32();
                    return t.asString();
                })
                .orElse(null);
    }

    private static String tlvValueToString(TlvEntry tlv) {
        try {
            return tlv.asString();
        } catch (Exception e) {
            return "bytes[" + tlv.value().length + "]";
        }
    }

    private static String strVal(Map<String, Object> m, String key, String def) {
        Object v = m.get(key);
        return v instanceof String s && !s.isEmpty() ? s : def;
    }

    private static int intVal(Map<String, Object> m, String key, int def) {
        Object v = m.get(key);
        if (v instanceof Number n) return n.intValue();
        if (v instanceof String s) {
            try { return Integer.parseInt(s); } catch (NumberFormatException ignored) {}
        }
        return def;
    }

    private static long longVal(Map<String, Object> m, String key, long def) {
        Object v = m.get(key);
        if (v instanceof Number n) return n.longValue();
        if (v instanceof String s) {
            try { return Long.parseLong(s); } catch (NumberFormatException ignored) {}
        }
        return def;
    }
}
