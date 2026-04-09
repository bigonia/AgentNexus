package com.zwbd.agentnexus.sdui.service;

import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Component
public class SduiScriptValidator {

    private static final List<String> FORBIDDEN_IMPORTS = List.of(
            "import os",
            "import socket",
            "import subprocess",
            "import pathlib",
            "import requests",
            "from os",
            "from socket",
            "from subprocess",
            "from pathlib",
            "open(",
            "__import__("
    );

    private static final List<String> UNSUPPORTED_LAYOUT_DSL = List.of(
            "\"type\": \"column\"",
            "\"type\": \"row\"",
            "\"type\": \"text\"",
            "'type': 'column'",
            "'type': 'row'",
            "'type': 'text'",
            "\"props\": {",
            "'props': {"
    );

    private static final Pattern ACTION_PATTERN = Pattern.compile("[\"']action[\"']\\s*:\\s*[\"']([a-zA-Z_]+)[\"']");
    private static final Pattern ON_CLICK_PATTERN = Pattern.compile("[\"']on_click[\"']\\s*:\\s*[\"']server://[^\"']+[\"']");

    public String validate(String scriptContent) {
        List<String> errors = new ArrayList<>();
        if (scriptContent == null || scriptContent.isBlank()) {
            errors.add("script is empty");
            return String.join("; ", errors);
        }
        if (!scriptContent.contains("def on_start(")) {
            errors.add("missing required function: on_start");
        }
        if (!scriptContent.contains("def on_event(")) {
            errors.add("missing required function: on_event");
        }
        if (!scriptContent.contains("\"children\"") && !scriptContent.contains("'children'")) {
            errors.add("layout payload missing children (root payload should contain children)");
        }
        if (!scriptContent.contains("\"type\":\"container\"")
                && !scriptContent.contains("\"type\": \"container\"")
                && !scriptContent.contains("'type':'container'")
                && !scriptContent.contains("'type': 'container'")) {
            errors.add("missing container-based root layout");
        }
        if (!scriptContent.contains("\"type\":\"label\"")
                && !scriptContent.contains("\"type\": \"label\"")
                && !scriptContent.contains("'type':'label'")
                && !scriptContent.contains("'type': 'label'")) {
            errors.add("missing label component (text hierarchy required)");
        }
        Matcher onClickMatcher = ON_CLICK_PATTERN.matcher(scriptContent);
        if (!onClickMatcher.find()) {
            errors.add("no server on_click action found (interactive button required)");
        }
        if (!scriptContent.contains("\"action\":\"layout\"")
                && !scriptContent.contains("\"action\": \"layout\"")
                && !scriptContent.contains("'action':'layout'")
                && !scriptContent.contains("'action': 'layout'")) {
            errors.add("on_start must return layout action");
        }
        if (!scriptContent.contains("\"action\":\"update\"")
                && !scriptContent.contains("\"action\": \"update\"")
                && !scriptContent.contains("'action':'update'")
                && !scriptContent.contains("'action': 'update'")
                && !scriptContent.contains("\"action\":\"noop\"")
                && !scriptContent.contains("\"action\": \"noop\"")
                && !scriptContent.contains("'action':'noop'")
                && !scriptContent.contains("'action': 'noop'")) {
            errors.add("on_event should return update or noop action");
        }
        if (scriptContent.contains(" import ") || scriptContent.startsWith("import ") || scriptContent.contains("\nimport ")) {
            errors.add("import statements are not allowed in runtime sandbox");
        }
        if (scriptContent.contains("eval(") || scriptContent.contains("exec(")) {
            errors.add("eval/exec are not allowed");
        }
        for (String forbidden : FORBIDDEN_IMPORTS) {
            if (scriptContent.contains(forbidden)) {
                errors.add("forbidden expression detected: " + forbidden);
            }
        }
        for (String unsupportedDsl : UNSUPPORTED_LAYOUT_DSL) {
            if (scriptContent.contains(unsupportedDsl)) {
                errors.add("unsupported layout DSL detected: " + unsupportedDsl + " (use SDUI container/label/button/image with protocol fields)");
            }
        }
        Matcher actionMatcher = ACTION_PATTERN.matcher(scriptContent);
        while (actionMatcher.find()) {
            String action = actionMatcher.group(1);
            if (!"layout".equals(action) && !"update".equals(action) && !"noop".equals(action)) {
                errors.add("unsupported action detected: " + action + " (allowed: layout/update/noop)");
                break;
            }
        }
        return errors.isEmpty() ? "OK" : String.join("; ", errors);
    }
}
