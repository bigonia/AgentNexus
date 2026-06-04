package com.zwbd.agentnexus.sdui.workflow;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Parses command input strings in the format: [@target] /command [args...]
 * Handles comma-separated device IDs, @all broadcast, and named parameter extraction.
 */
public class CommandParser {

    // Matches: @target1,target2 /command args...
    private static final Pattern SLASH_PATTERN =
            Pattern.compile("^(?:@([\\w,\\-*]+)\\s+)?(?:/(\\w+)(?:\\s+(.*))?)?$");

    // Matches named params in args: --name=value or --name value
    private static final Pattern NAMED_PARAM =
            Pattern.compile("--(\\w+)(?:=(\\S+))?");

    public record ParsedCommand(
            List<String> targets,
            String command,
            String rawText,
            Map<String, String> namedParams,
            boolean isSlashCommand
    ) {}

    /**
     * Parse input text. Returns null if no slash command pattern is detected.
     */
    public static ParsedCommand parse(String input) {
        if (input == null || input.isBlank()) return null;

        Matcher m = SLASH_PATTERN.matcher(input.trim());
        if (!m.matches()) return null;

        String targetsPart = m.group(1);
        String command = m.group(2);
        String args = m.group(3);

        List<String> targets = new ArrayList<>();
        if (targetsPart != null && !targetsPart.isEmpty()) {
            if ("all".equals(targetsPart) || "*".equals(targetsPart)) {
                targets.add("*");
            } else {
                for (String t : targetsPart.split(",")) {
                    String trimmed = t.trim();
                    if (!trimmed.isEmpty()) {
                        targets.add(trimmed);
                    }
                }
            }
        }

        Map<String, String> namedParams = new LinkedHashMap<>();
        String rawText = args;
        if (args != null && !args.isEmpty()) {
            // Extract named params: --name=value or --name value
            Matcher pm = NAMED_PARAM.matcher(args);
            StringBuffer sb = new StringBuffer();
            while (pm.find()) {
                String name = pm.group(1);
                String value = pm.group(2);
                if (value == null) {
                    value = "true";
                }
                namedParams.put(name, value);
                pm.appendReplacement(sb, "");
            }
            pm.appendTail(sb);
            rawText = sb.toString().trim();
            if (rawText.isEmpty()) rawText = null;
        }

        boolean isSlash = command != null && !command.isEmpty();
        return new ParsedCommand(targets, isSlash ? "/" + command : null, rawText, namedParams, isSlash);
    }

    /**
     * Simple check if input contains a slash command pattern.
     */
    public static boolean looksLikeSlashCommand(String input) {
        if (input == null || input.isBlank()) return false;
        return input.trim().matches("^(?:@[\\w,\\-*]+\\s+)?/\\w+.*");
    }
}
