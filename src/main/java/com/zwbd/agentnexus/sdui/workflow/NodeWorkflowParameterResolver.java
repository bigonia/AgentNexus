package com.zwbd.agentnexus.sdui.workflow;

import org.springframework.stereotype.Service;

import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
public class NodeWorkflowParameterResolver {

    private static final Pattern FULL_REF = Pattern.compile("^\\$[A-Za-z][A-Za-z0-9_]*(?:\\.[A-Za-z0-9_\\-]+)*$");
    private static final Pattern ANY_REF = Pattern.compile("\\$[A-Za-z][A-Za-z0-9_]*(?:\\.[A-Za-z0-9_\\-]+)*");

    public Object resolve(Object value, Map<String, Object> context) {
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> resolved = new LinkedHashMap<>();
            for (var entry : map.entrySet()) {
                resolved.put(String.valueOf(entry.getKey()), resolve(entry.getValue(), context));
            }
            return resolved;
        }
        if (value instanceof List<?> list) {
            List<Object> resolved = new ArrayList<>();
            for (Object item : list) {
                resolved.add(resolve(item, context));
            }
            return resolved;
        }
        if (value instanceof String text) {
            return resolveString(text, context);
        }
        return value;
    }

    @SuppressWarnings("unchecked")
    public Map<String, Object> resolveParams(Map<String, Object> params, Map<String, Object> context) {
        Object resolved = resolve(params == null ? Map.of() : params, context);
        return resolved instanceof Map<?, ?> map ? (Map<String, Object>) map : Map.of();
    }

    public List<String> validateReferenceSyntax(Object value) {
        List<String> errors = new ArrayList<>();
        collectSyntaxErrors(value, errors);
        return errors;
    }

    private Object resolveString(String text, Map<String, Object> context) {
        if (!text.contains("$")) {
            return text;
        }
        if (FULL_REF.matcher(text).matches()) {
            return resolvePath(text.substring(1), context);
        }
        Matcher matcher = ANY_REF.matcher(text);
        StringBuffer buffer = new StringBuffer();
        while (matcher.find()) {
            Object resolved = resolvePath(matcher.group().substring(1), context);
            matcher.appendReplacement(buffer, Matcher.quoteReplacement(resolved == null ? "" : String.valueOf(resolved)));
        }
        matcher.appendTail(buffer);
        return buffer.toString();
    }

    private Object resolvePath(String path, Map<String, Object> context) {
        String[] parts = path.split("\\.");
        Object current = context;
        StringBuilder seen = new StringBuilder();
        for (String part : parts) {
            if (seen.length() > 0) seen.append('.');
            seen.append(part);
            if (current instanceof Map<?, ?> map && map.containsKey(part)) {
                current = map.get(part);
            } else if (current instanceof List<?> list && isInteger(part)) {
                int index = Integer.parseInt(part);
                if (index < 0 || index >= list.size()) {
                    throw new IllegalArgumentException("missing reference path: $" + seen);
                }
                current = list.get(index);
            } else {
                throw new IllegalArgumentException("missing reference path: $" + seen);
            }
        }
        return current;
    }

    private void collectSyntaxErrors(Object value, List<String> errors) {
        if (value instanceof Map<?, ?> map) {
            for (Object child : map.values()) {
                collectSyntaxErrors(child, errors);
            }
            return;
        }
        if (value instanceof List<?> list) {
            for (Object child : list) {
                collectSyntaxErrors(child, errors);
            }
            return;
        }
        if (!(value instanceof String text) || !text.contains("$")) {
            return;
        }
        int index = text.indexOf('$');
        while (index >= 0) {
            Matcher matcher = ANY_REF.matcher(text.substring(index));
            if (!matcher.lookingAt()) {
                errors.add("invalid reference syntax near: " + text.substring(index));
                return;
            }
            index = text.indexOf('$', index + Math.max(1, matcher.end()));
        }
    }

    private boolean isInteger(String value) {
        if (value == null || value.isBlank()) return false;
        for (int i = 0; i < value.length(); i++) {
            if (!Character.isDigit(value.charAt(i))) return false;
        }
        return true;
    }
}
