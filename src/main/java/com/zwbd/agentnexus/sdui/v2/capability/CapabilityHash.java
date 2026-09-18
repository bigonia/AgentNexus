package com.zwbd.agentnexus.sdui.v2.capability;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * {@code capability_hash} 计算。
 *
 * <p>04_PROTOCOL_MODEL.md §4 要求"完整 Schema 在构建时生成稳定的 {@code capability_hash}"，
 * §10 又把"生成和编码方式"留到实现阶段确定。这里采用：</p>
 *
 * <pre>
 * 规范化 JSON（对象键排序、剔除 null 字段、数组保持原序）
 *   → UTF-8 字节
 *   → SHA-256
 *   → 十六进制小写前 16 位
 * </pre>
 *
 * <p><b>风险（缺口 G10）</b>：该算法必须与终端实现完全一致，否则每次连接都会触发全量 Schema 同步，
 * 但功能仍可正确工作（只是退化）。终端协议实现完成后需要按此对齐或替换。</p>
 */
public final class CapabilityHash {

    /** hash 的十六进制字符数。 */
    public static final int HASH_LENGTH = 16;

    private CapabilityHash() {}

    public static String compute(CapabilitySchemaV2 schema, ObjectMapper objectMapper) {
        if (schema == null) {
            return null;
        }
        JsonNode tree = objectMapper.valueToTree(schema);
        String canonical = canonicalize(tree);
        return sha256HexPrefix(canonical, HASH_LENGTH);
    }

    /**
     * 生成规范化 JSON 文本：对象键按字典序排列，null 字段被剔除，数组保持原有顺序
     * （数组顺序在 Schema 中具有语义：例如 {@code params} 的声明顺序）。
     */
    static String canonicalize(JsonNode node) {
        StringBuilder out = new StringBuilder();
        writeCanonical(node, out);
        return out.toString();
    }

    private static void writeCanonical(JsonNode node, StringBuilder out) {
        if (node == null || node.isNull()) {
            out.append("null");
            return;
        }
        if (node.isObject()) {
            List<String> names = new ArrayList<>();
            node.fieldNames().forEachRemaining(names::add);
            Collections.sort(names);
            out.append('{');
            boolean first = true;
            for (String name : names) {
                JsonNode child = node.get(name);
                if (child == null || child.isNull()) {
                    continue;
                }
                if (!first) {
                    out.append(',');
                }
                first = false;
                out.append(quote(name)).append(':');
                writeCanonical(child, out);
            }
            out.append('}');
            return;
        }
        if (node.isArray()) {
            out.append('[');
            for (int i = 0; i < node.size(); i++) {
                if (i > 0) {
                    out.append(',');
                }
                writeCanonical(node.get(i), out);
            }
            out.append(']');
            return;
        }
        if (node.isTextual()) {
            out.append(quote(node.asText()));
            return;
        }
        out.append(node.toString());
    }

    private static String quote(String value) {
        StringBuilder sb = new StringBuilder(value.length() + 2);
        sb.append('"');
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                default -> {
                    if (c < 0x20) {
                        sb.append(String.format("\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
                }
            }
        }
        sb.append('"');
        return sb.toString();
    }

    private static String sha256HexPrefix(String text, int length) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hashed = digest.digest(text.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(hashed.length * 2);
            for (byte b : hashed) {
                hex.append(Character.forDigit((b >> 4) & 0xF, 16));
                hex.append(Character.forDigit(b & 0xF, 16));
            }
            return hex.substring(0, Math.min(length, hex.length()));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    /** 便于测试：暴露规范化结果。 */
    public static String canonicalForm(CapabilitySchemaV2 schema, ObjectMapper objectMapper) {
        return canonicalize(objectMapper.valueToTree(schema));
    }
}
