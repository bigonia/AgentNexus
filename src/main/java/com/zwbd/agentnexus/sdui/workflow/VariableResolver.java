package com.zwbd.agentnexus.sdui.workflow;

import org.springframework.expression.EvaluationContext;
import org.springframework.expression.ExpressionParser;
import org.springframework.expression.spel.standard.SpelExpressionParser;
import org.springframework.expression.spel.support.StandardEvaluationContext;

import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class VariableResolver {

    private static final ExpressionParser spelParser = new SpelExpressionParser();
    private static final Pattern VAR_REF = Pattern.compile(
            "\\$(data|trigger|env)\\.([a-zA-Z_]\\w*(?:\\[\\d+\\])?(?:\\.[a-zA-Z_]\\w*(?:\\[\\d+\\])?)*)");

    private VariableResolver() {}

    // ── Public API ──

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
        if (trimmed.isEmpty()) return trimmed;

        if (trimmed.startsWith("'") && trimmed.endsWith("'") && trimmed.length() >= 2
                && !containsOperator(trimmed.substring(1, trimmed.length() - 1))) {
            return trimmed.substring(1, trimmed.length() - 1);
        }

        if (!trimmed.contains(" ") && !trimmed.contains("?") && !trimmed.contains("(")
                && !trimmed.contains("+") && !trimmed.contains("==") && !trimmed.contains("!=")
                && !trimmed.contains(">") && !trimmed.contains("<")) {
            return resolveSimple(trimmed, data, trigger, env);
        }

        try {
            String spelExpr = translateExpr(trimmed);
            EvaluationContext ctx = buildContext(data, trigger, env);
            return spelParser.parseExpression(spelExpr).getValue(ctx);
        } catch (Exception e) {
            return expr;
        }
    }

    // ── SpEL translation ──

    private static String translateExpr(String expr) {
        Matcher m = VAR_REF.matcher(expr);
        StringBuilder sb = new StringBuilder();
        while (m.find()) {
            m.appendReplacement(sb, Matcher.quoteReplacement(translatePath(m.group(1), m.group(2))));
        }
        m.appendTail(sb);
        String result = sb.toString();
        result = result.replace("min(", "#min(");
        result = result.replace("max(", "#max(");
        return result;
    }

    private static String translatePath(String varName, String path) {
        StringBuilder sb = new StringBuilder("#").append(varName);
        for (String seg : path.split("\\.")) {
            int bi = seg.indexOf('[');
            if (bi > 0) {
                String key = seg.substring(0, bi);
                String idx = seg.substring(bi + 1, seg.indexOf(']', bi));
                sb.append("['").append(key).append("'][").append(idx).append("]");
            } else {
                sb.append("['").append(seg).append("']");
            }
        }
        return sb.toString();
    }

    private static EvaluationContext buildContext(Map<String, Object> data,
                                                   Map<String, Object> trigger,
                                                   Map<String, String> env) {
        StandardEvaluationContext ctx = new StandardEvaluationContext();
        ctx.setVariable("data", data != null ? data : Map.of());
        ctx.setVariable("trigger", trigger != null ? trigger : Map.of());
        ctx.setVariable("env", env != null ? env : Map.of());
        try {
            ctx.registerFunction("min",
                    VariableResolver.class.getDeclaredMethod("min", double.class, double.class));
            ctx.registerFunction("max",
                    VariableResolver.class.getDeclaredMethod("max", double.class, double.class));
        } catch (NoSuchMethodException ignored) {
        }
        return ctx;
    }

    @SuppressWarnings("unused")
    public static double min(double a, double b) { return a <= b ? a : b; }

    @SuppressWarnings("unused")
    public static double max(double a, double b) { return a >= b ? a : b; }

    // ── Fast-path simple resolver ──

    private static Object resolveSimple(String expr,
                                         Map<String, Object> data,
                                         Map<String, Object> trigger,
                                         Map<String, String> env) {
        if (expr.startsWith("$data.")) {
            return getByPath(data, expr.substring(6));
        }
        if (expr.startsWith("$trigger.")) {
            return getByPath(trigger, expr.substring(9));
        }
        if (expr.startsWith("$env.")) {
            String varName = expr.substring(5);
            return env != null ? env.getOrDefault(varName, expr) : expr;
        }
        try {
            return Integer.parseInt(expr);
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

    private static boolean containsOperator(String s) {
        return s.contains(" ") || s.contains("?") || s.contains("(") || s.contains("+");
    }
}
