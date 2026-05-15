package com.zwbd.agentnexus.sdui.workflow;

import java.util.*;

/**
 * Tracks variable-to-section dependencies so that when a variable changes,
 * affected sections can be re-resolved and patched automatically.
 *
 * Usage:
 *   1. registerSection() when a page is first built
 *   2. getAffectedSections(variableName) after a variable change
 *   3. getBindings(sectionId) to retrieve the original bindings for re-resolution
 *   4. unregisterAll() when the workflow is unloaded
 */
public class VariableWatcher {

    private final Map<String, Map<String, String>> sectionBindings = new LinkedHashMap<>();
    private final Map<String, Set<String>> variableIndex = new LinkedHashMap<>();

    public void registerSection(String sectionId, Map<String, String> bindings) {
        if (bindings == null || bindings.isEmpty()) return;

        sectionBindings.put(sectionId, new LinkedHashMap<>(bindings));

        for (String expr : bindings.values()) {
            Set<String> vars = extractVariables(expr);
            for (String var : vars) {
                variableIndex.computeIfAbsent(var, k -> new LinkedHashSet<>()).add(sectionId);
            }
        }
    }

    public void registerPage(PageDef page) {
        if (page == null || page.sections() == null) return;
        for (SectionBindDef s : page.sections()) {
            if (s.bind() != null) {
                registerSection(s.id(), s.bind());
            }
        }
    }

    public Set<String> getAffectedSections(String variableName) {
        Set<String> result = new LinkedHashSet<>();
        // exact match
        Set<String> exact = variableIndex.get(variableName);
        if (exact != null) result.addAll(exact);
        // prefix match for nested paths
        String prefix = variableName + ".";
        for (Map.Entry<String, Set<String>> e : variableIndex.entrySet()) {
            if (e.getKey().startsWith(prefix)) {
                result.addAll(e.getValue());
            }
        }
        return result;
    }

    public Map<String, String> getBindings(String sectionId) {
        Map<String, String> bindings = sectionBindings.get(sectionId);
        return bindings != null ? bindings : Map.of();
    }

    public void unregisterAll() {
        sectionBindings.clear();
        variableIndex.clear();
    }

    public boolean isEmpty() {
        return sectionBindings.isEmpty();
    }

    private static Set<String> extractVariables(String expr) {
        Set<String> vars = new LinkedHashSet<>();
        if (expr == null) return vars;
        int i = 0;
        while (i < expr.length()) {
            int d = expr.indexOf("$data.", i);
            if (d < 0) break;

            int start = d + 6; // after "$data."
            int end = start;
            while (end < expr.length() && isIdentChar(expr.charAt(end))) {
                end++;
            }
            // handle bracket access: $data.recent[0].title
            while (end < expr.length() && (expr.charAt(end) == '[' || expr.charAt(end) == '.')) {
                if (expr.charAt(end) == '[') {
                    int close = expr.indexOf(']', end);
                    if (close < 0) break;
                    end = close + 1;
                } else {
                    end++;
                    while (end < expr.length() && isIdentChar(expr.charAt(end))) {
                        end++;
                    }
                }
            }

            if (end > start) {
                vars.add(expr.substring(start, end));
            }
            i = end;
        }
        return vars;
    }

    private static boolean isIdentChar(char c) {
        return Character.isLetterOrDigit(c) || c == '_';
    }
}
