package com.zwbd.agentnexus.sdui.workflow;

import java.util.*;

public final class VariableResolver {

    private VariableResolver() {}

    public static Map<String, Object> resolve(Map<String, String> bind,
                                               Map<String, Object> data,
                                               Map<String, Object> trigger,
                                               Map<String, String> env) {
        Map<String, Object> result = new LinkedHashMap<>();
        for (Map.Entry<String, String> e : bind.entrySet()) {
            result.put(e.getKey(), resolveExpression(e.getValue(), data, trigger, env));
        }
        return result;
    }

    public static Object resolveExpression(String expr,
                                            Map<String, Object> data,
                                            Map<String, Object> trigger,
                                            Map<String, String> env) {
        if (expr == null || expr.isEmpty()) return expr;

        String trimmed = expr.trim();

        if (trimmed.startsWith("'") && trimmed.endsWith("'") && trimmed.length() >= 2) {
            return trimmed.substring(1, trimmed.length() - 1);
        }

        if (trimmed.startsWith("$data.")) {
            return getByPath(data, trimmed.substring(6));
        }
        if (trimmed.startsWith("$trigger.")) {
            return getByPath(trigger, trimmed.substring(9));
        }
        if (trimmed.startsWith("$env.")) {
            String varName = trimmed.substring(5);
            return env != null ? env.getOrDefault(varName, expr) : expr;
        }

        try {
            return Integer.parseInt(trimmed);
        } catch (NumberFormatException ignored) {
        }

        return expr;
    }

    @SuppressWarnings("unchecked")
    private static Object getByPath(Map<String, Object> root, String path) {
        if (root == null || path.isEmpty()) return null;

        String[] segments = path.split("\\.");
        Object current = root;
        for (String seg : segments) {
            int bracketIdx = seg.indexOf('[');
            String key;
            Integer index = null;
            if (bracketIdx > 0) {
                key = seg.substring(0, bracketIdx);
                String idxStr = seg.substring(bracketIdx + 1, seg.indexOf(']', bracketIdx));
                index = Integer.parseInt(idxStr);
            } else {
                key = seg;
            }

            if (current instanceof Map) {
                current = ((Map<String, Object>) current).get(key);
            } else {
                return null;
            }

            if (index != null && current instanceof List) {
                List<?> list = (List<?>) current;
                current = index < list.size() ? list.get(index) : null;
            }

            if (current == null) return null;
        }
        return current;
    }
}
