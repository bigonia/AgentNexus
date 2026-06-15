package com.zwbd.agentnexus.sdui.workflow.service;

import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
public class WorkflowExpressionService {

    public Object resolve(Object value, Map<String, Object> context) {
        if (value instanceof String s && s.startsWith("$")) {
            return readPath(context, s.substring(1));
        }
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> resolved = new LinkedHashMap<>();
            for (var entry : map.entrySet()) {
                resolved.put(String.valueOf(entry.getKey()), resolve(entry.getValue(), context));
            }
            return resolved;
        }
        if (value instanceof List<?> list) {
            return list.stream().map(item -> resolve(item, context)).toList();
        }
        return value;
    }

    public boolean evaluateCondition(String expression, Map<String, Object> context) {
        if (expression == null || expression.isBlank()) {
            return true;
        }
        String expr = expression.trim();
        if (expr.contains("==")) {
            String[] parts = expr.split("==", 2);
            Object left = resolve(parts[0].trim(), context);
            String right = stripQuotes(parts[1].trim());
            return left != null && right.equals(String.valueOf(left));
        }
        if (expr.contains("!=")) {
            String[] parts = expr.split("!=", 2);
            Object left = resolve(parts[0].trim(), context);
            String right = stripQuotes(parts[1].trim());
            return left == null || !right.equals(String.valueOf(left));
        }
        Object value = resolve(expr, context);
        if (value instanceof Boolean b) return b;
        if (value instanceof Number n) return n.intValue() != 0;
        return value != null && Boolean.parseBoolean(String.valueOf(value));
    }

    @SuppressWarnings("unchecked")
    private Object readPath(Map<String, Object> context, String path) {
        Object current = context;
        for (String part : path.split("\\.")) {
            if (!(current instanceof Map<?, ?> map)) {
                return null;
            }
            current = ((Map<String, Object>) map).get(part);
        }
        return current;
    }

    private String stripQuotes(String value) {
        if ((value.startsWith("\"") && value.endsWith("\"")) || (value.startsWith("'") && value.endsWith("'"))) {
            return value.substring(1, value.length() - 1);
        }
        return value;
    }
}
