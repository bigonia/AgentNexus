package com.zwbd.agentnexus.sdui.v2.protocol;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * v2 信封编解码器。
 *
 * <p>判定顺序是显式的，避免 {@code name} 与 {@code id} 同时出现时的歧义：</p>
 *
 * <pre>
 * id + ok    → Result
 * id + name  → Request
 * name       → Event
 * 其他        → 拒绝
 * </pre>
 *
 * <p>{@code body} 省略时按空对象处理。空 body 在序列化时可以省略。</p>
 */
public class EnvelopeCodec {

    private static final String FIELD_ID = "id";
    private static final String FIELD_NAME = "name";
    private static final String FIELD_BODY = "body";
    private static final String FIELD_OK = "ok";
    private static final String FIELD_ERROR = "error";

    private final ObjectMapper objectMapper;

    public EnvelopeCodec(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    // ── decode ──────────────────────────────────────────────────────────────

    public Envelope decode(String json) {
        try {
            return decode(objectMapper.readTree(json));
        } catch (JsonProcessingException e) {
            throw new ProtocolException(ProtocolErrors.INVALID_ENVELOPE, "malformed json", e);
        }
    }

    public Envelope decode(JsonNode node) {
        if (node == null || !node.isObject()) {
            throw new ProtocolException(ProtocolErrors.INVALID_ENVELOPE, "envelope must be a json object");
        }

        String id = textOrNull(node, FIELD_ID);
        String name = textOrNull(node, FIELD_NAME);
        JsonNode ok = node.get(FIELD_OK);

        if (id != null && ok != null && ok.isBoolean()) {
            String error = textOrNull(node, FIELD_ERROR);
            if (ok.asBoolean() && error != null) {
                throw new ProtocolException(ProtocolErrors.INVALID_ENVELOPE, "successful result must not carry error");
            }
            if (!ok.asBoolean() && error == null) {
                throw new ProtocolException(ProtocolErrors.INVALID_ENVELOPE, "failed result must carry error");
            }
            return new Envelope.Result(id, ok.asBoolean(), error);
        }

        if (id != null && name != null) {
            return new Envelope.Request(id, name, bodyOf(node));
        }

        if (ok != null) {
            throw new ProtocolException(ProtocolErrors.INVALID_ENVELOPE, "result must carry id");
        }

        if (name != null) {
            return new Envelope.Event(name, bodyOf(node));
        }

        throw new ProtocolException(ProtocolErrors.INVALID_ENVELOPE, "cannot classify envelope");
    }

    // ── encode ──────────────────────────────────────────────────────────────

    public ObjectNode encodeRequest(String id, String name, Object body) {
        ObjectNode node = objectMapper.createObjectNode();
        node.put(FIELD_ID, id);
        node.put(FIELD_NAME, name);
        attachBody(node, body);
        return node;
    }

    public ObjectNode encodeResult(String id, boolean ok, String error) {
        ObjectNode node = objectMapper.createObjectNode();
        node.put(FIELD_ID, id);
        node.put(FIELD_OK, ok);
        if (!ok) {
            node.put(FIELD_ERROR, error != null ? error : ProtocolErrors.UNSUPPORTED);
        }
        return node;
    }

    public ObjectNode encodeEvent(String name, Object body) {
        ObjectNode node = objectMapper.createObjectNode();
        node.put(FIELD_NAME, name);
        attachBody(node, body);
        return node;
    }

    public String toJson(JsonNode node) {
        try {
            return objectMapper.writeValueAsString(node);
        } catch (JsonProcessingException e) {
            throw new ProtocolException(ProtocolErrors.INVALID_ENVELOPE, "failed to serialize envelope", e);
        }
    }

    public ObjectMapper objectMapper() {
        return objectMapper;
    }

    // ── helpers ─────────────────────────────────────────────────────────────

    private void attachBody(ObjectNode node, Object body) {
        if (body == null) {
            return;
        }
        node.set(FIELD_BODY, objectMapper.valueToTree(body));
    }

    private static JsonNode bodyOf(JsonNode node) {
        JsonNode body = node.get(FIELD_BODY);
        if (body == null || body.isNull()) {
            return null;
        }
        return body;
    }

    private static String textOrNull(JsonNode node, String field) {
        JsonNode value = node.get(field);
        if (value == null || !value.isTextual()) {
            return null;
        }
        String text = value.asText();
        return text.isBlank() ? null : text;
    }
}
