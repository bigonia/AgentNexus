package com.zwbd.agentnexus.sdui.workflow;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.zwbd.agentnexus.sdui.section.*;
import com.zwbd.agentnexus.sdui.service.AudioService;
import com.zwbd.agentnexus.sdui.service.CommandDispatcher;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

import java.util.*;

@Slf4j
@Component
@RequiredArgsConstructor
public class ActionExecutor {

    private final SectionOrchestrationService sectionService;
    private final CommandDispatcher dispatcher;
    private final AudioService audioService;
    private final ObjectMapper objectMapper;
    private final RestTemplate restTemplate = new RestTemplate();

    public void execute(List<ActionDef> actions, WorkflowInstance instance,
                        Map<String, Object> triggerPayload, Map<String, String> env) {
        for (ActionDef action : actions) {
            Set<String> changedVars = new LinkedHashSet<>();
            try {
                dispatch(action, instance, triggerPayload, env, changedVars);
            } catch (Exception e) {
                log.error("Action execution failed for device {}: action type={}, error={}",
                        instance.deviceId(), action.getClass().getSimpleName(), e.getMessage());
            }
            if (!changedVars.isEmpty() && !instance.watcher().isEmpty()) {
                autoRebind(instance, changedVars, triggerPayload, env);
            }
        }
    }

    private void dispatch(ActionDef action, WorkflowInstance instance,
                          Map<String, Object> triggerPayload, Map<String, String> env,
                          Set<String> changedVars) {
        if (action instanceof ActionDef.FetchAction a) {
            String url = resolveString(a.url(), triggerPayload, instance.variablesAsMap(), env);
            log.info("Fetch: {} -> {}", a.save(), url);
            try {
                String response = restTemplate.getForObject(url, String.class);
                Object parsed = objectMapper.readValue(response, Object.class);
                instance.putVariable(a.save(), parsed);
                changedVars.add(a.save());
                log.info("Fetch saved to $data.{}: {} bytes", a.save(),
                        response != null ? response.length() : 0);
            } catch (Exception e) {
                log.error("Fetch failed for {}: {}", url, e.getMessage());
            }
        } else if (action instanceof ActionDef.SetVariableAction a) {
            String varName = a.variable();
            Object resolved = VariableResolver.resolveExpression(a.value(),
                    instance.variablesAsMap(), triggerPayload, env);
            instance.putVariable(varName, resolved);
            changedVars.add(varName);
            log.info("SetVariable: $data.{} = {}", varName, resolved);
        } else if (action instanceof ActionDef.ConditionAction a) {
            Object cond = VariableResolver.resolveExpression(a.variable(),
                    instance.variablesAsMap(), triggerPayload, env);
            boolean match = evaluateCondition(cond, a.operator(), a.value(),
                    instance.variablesAsMap(), triggerPayload, env);
            log.info("Condition: {} {} {} → {}", a.variable(), a.operator(), a.value(), match);
            List<ActionDef> branch = match && a.thenActions() != null
                    ? a.thenActions()
                    : a.elseActions() != null ? a.elseActions() : List.of();
            for (ActionDef sub : branch) {
                dispatch(sub, instance, triggerPayload, env, changedVars);
            }
        } else if (action instanceof ActionDef.SequenceAction a) {
            for (ActionDef step : a.steps()) {
                dispatch(step, instance, triggerPayload, env, changedVars);
            }
        } else if (action instanceof ActionDef.UpdatePageAction a) {
            instance.activePage(a.page());
        } else if (action instanceof ActionDef.PatchSectionAction a) {
            Map<String, String> bind = new LinkedHashMap<>();
            bind.put("data", a.bind());
            SectionData data = buildSectionData("hero_section",
                    VariableResolver.resolve(bind, instance.variablesAsMap(), triggerPayload, env));
            SectionPatch patch = new SectionPatch(instance.workflowId(), List.of(
                    new SectionPatch.PatchEntry(a.sectionId(), "update", data)));
            sectionService.sendPatch(instance.deviceId(), patch);
        } else if (action instanceof ActionDef.PlayAudioAction a) {
            audioService.playPreset(instance.deviceId(), a.preset());
        } else if (action instanceof ActionDef.TtsAction a) {
            String text = resolveString(a.text(), triggerPayload, instance.variablesAsMap(), env);
            audioService.playTts(instance.deviceId(), text);
        } else if (action instanceof ActionDef.SwitchPageAction a) {
            instance.activePage(a.page());
        } else if (action instanceof ActionDef.ControlAction a) {
            String value = resolveString(a.value(), triggerPayload, instance.variablesAsMap(), env);
            dispatcher.dispatch(instance.deviceId(), a.command(), value);
        }
    }

    // ── Auto-rebinding: when variables change, re-resolve dependent sections ──

    private void autoRebind(WorkflowInstance instance, Set<String> changedVars,
                            Map<String, Object> triggerPayload, Map<String, String> env) {
        VariableWatcher watcher = instance.watcher();
        Set<String> allSections = new LinkedHashSet<>();
        for (String var : changedVars) {
            allSections.addAll(watcher.getAffectedSections(var));
        }
        if (allSections.isEmpty()) return;

        Map<String, Object> vars = instance.variablesAsMap();
        for (String sectionId : allSections) {
            Map<String, String> bindings = watcher.getBindings(sectionId);
            if (bindings.isEmpty()) continue;
            Map<String, Object> resolved = VariableResolver.resolve(bindings, vars, triggerPayload, env);
            // Infer section type from the binding content or stored metadata
            String sectionType = inferSectionType(resolved);
            if (sectionType == null) continue;
            SectionData data = buildSectionData(sectionType, resolved);
            if (data == null) continue;
            SectionPatch patch = new SectionPatch(instance.workflowId(), List.of(
                    new SectionPatch.PatchEntry(sectionId, "update", data)));
            sectionService.sendPatch(instance.deviceId(), patch);
            log.debug("Auto-rebound section {} for device {}", sectionId, instance.deviceId());
        }
    }

    private String inferSectionType(Map<String, Object> vals) {
        // based on the binding field patterns, infer the section type
        if (vals.containsKey("actions")) return "action_section";
        if (vals.containsKey("metrics")) return "metric_section";
        if (vals.containsKey("options")) return "toggle_section";
        if (vals.containsKey("tabs")) return "nav_section";
        if (vals.containsKey("progress") && vals.containsKey("title")) return "progress_section";
        if (vals.containsKey("items")) return "list_section";
        if (vals.containsKey("points")) return "chart_section";
        if (vals.containsKey("iconSrc")) return "image_section";
        if (vals.containsKey("body") && vals.containsKey("title")) return "overlay_section";
        if (vals.containsKey("value") && vals.containsKey("label")) return "hero_section";
        if (vals.containsKey("body")) return "text_section";
        if (vals.containsKey("elapsedMs")) return "timer_section";
        return null;
    }

    // ── Condition evaluation ──

    private boolean evaluateCondition(Object left, String operator, String rightExpr,
                                       Map<String, Object> data,
                                       Map<String, Object> triggerPayload,
                                       Map<String, String> env) {
        Object right = VariableResolver.resolveExpression(rightExpr, data, triggerPayload, env);
        if (left == null && right == null && "eq".equals(operator)) return true;
        if (left == null && right == null && "neq".equals(operator)) return false;
        if (left == null) return "neq".equals(operator);
        if (right == null) return "neq".equals(operator);

        if ("isEmpty".equals(operator)) {
            if (left instanceof String s) return s.isEmpty();
            if (left instanceof List<?> l) return l.isEmpty();
            if (left instanceof Map<?, ?> m) return m.isEmpty();
            return false;
        }

        if (left instanceof Number nl && right instanceof Number nr) {
            double l = nl.doubleValue();
            double r = nr.doubleValue();
            return switch (operator) {
                case "eq" -> l == r;
                case "neq" -> l != r;
                case "gt" -> l > r;
                case "gte" -> l >= r;
                case "lt" -> l < r;
                case "lte" -> l <= r;
                default -> false;
            };
        }

        String ls = left.toString();
        String rs = right.toString();
        return switch (operator) {
            case "eq" -> ls.equals(rs);
            case "neq" -> !ls.equals(rs);
            case "contains" -> ls.contains(rs);
            default -> false;
        };
    }

    // ── Page building ──

    public SectionScene buildPageScene(PageDef page, Map<String, Object> data,
                                        Map<String, Object> triggerPayload, Map<String, String> env) {
        List<SectionEntry> entries = new ArrayList<>();
        for (SectionBindDef s : page.sections()) {
            Map<String, Object> resolved = VariableResolver.resolve(s.bind(), data, triggerPayload, env);
            SectionData sectionData = buildSectionData(s.type(), resolved);
            if (sectionData == null) {
                log.warn("Cannot build section data for type={} id={}", s.type(), s.id());
                continue;
            }
            SectionType type = SectionType.fromWireName(s.type());
            if (type == null) {
                log.warn("Unknown section type: {}", s.type());
                continue;
            }
            entries.add(new SectionEntry(type, s.id(), sectionData));
        }
        SectionLayout layout = SectionLayout.fromWireName(page.layout());
        return new SectionScene(page.id(), layout, page.autoScroll(), page.autoScrollMs(), entries);
    }

    /**
     * Build page scene AND register section bindings for VariableWatcher.
     */
    public SectionScene buildPageSceneWithBindings(PageDef page, Map<String, Object> data,
                                                    Map<String, Object> triggerPayload, Map<String, String> env,
                                                    VariableWatcher watcher) {
        if (watcher != null) {
            watcher.registerPage(page);
        }
        return buildPageScene(page, data, triggerPayload, env);
    }

    // ── Section data builders ──

    @SuppressWarnings("unchecked")
    private SectionData buildSectionData(String type, Map<String, Object> vals) {
        return switch (type) {
            case "hero_section" -> new SectionData.HeroData(
                    str(vals, "value", ""), str(vals, "label", ""), str(vals, "subtitle", ""),
                    str(vals, "tone", "primary"), str(vals, "iconSrc", ""), num(vals, "progress", 0));
            case "metric_section" -> {
                List<SectionData.MetricData.MetricEntry> metrics = new ArrayList<>();
                Object m = vals.get("metrics");
                if (m instanceof List<?> list) {
                    for (Object item : list) {
                        if (item instanceof Map<?, ?> entry) {
                            metrics.add(new SectionData.MetricData.MetricEntry(
                                    str((Map<String, Object>) entry, "label", ""),
                                    str((Map<String, Object>) entry, "value", "")));
                        }
                    }
                }
                yield new SectionData.MetricData(metrics);
            }
            case "chart_section" -> {
                List<Integer> points = new ArrayList<>();
                Object p = vals.get("points");
                if (p instanceof List<?> list) {
                    for (Object item : list) {
                        if (item instanceof Number n) points.add(n.intValue());
                    }
                }
                yield new SectionData.ChartData(str(vals, "title", ""), points, num(vals, "progress", 0));
            }
            case "progress_section" -> new SectionData.ProgressData(
                    str(vals, "title", ""), num(vals, "progress", 0), str(vals, "progressText", ""));
            case "text_section" -> new SectionData.TextData(str(vals, "title", ""), str(vals, "body", ""));
            case "list_section" -> {
                List<SectionData.ListData.ListItem> items = new ArrayList<>();
                Object l = vals.get("items");
                if (l instanceof List<?> list) {
                    for (Object item : list) {
                        if (item instanceof Map<?, ?> entry) {
                            Map<String, Object> e = (Map<String, Object>) entry;
                            items.add(new SectionData.ListData.ListItem(
                                    str(e, "id", ""), str(e, "title", ""),
                                    str(e, "subtitle", ""), str(e, "tone", "primary")));
                        }
                    }
                }
                yield new SectionData.ListData(items);
            }
            case "action_section" -> {
                List<SectionData.ActionData.ActionButton> buttons = new ArrayList<>();
                Object a = vals.get("actions");
                if (a instanceof List<?> list) {
                    for (Object item : list) {
                        if (item instanceof Map<?, ?> entry) {
                            Map<String, Object> e = (Map<String, Object>) entry;
                            buttons.add(new SectionData.ActionData.ActionButton(
                                    str(e, "id", ""), str(e, "label", ""),
                                    str(e, "tone", "primary"), true));
                        }
                    }
                }
                yield new SectionData.ActionData(buttons);
            }
            case "timer_section" -> new SectionData.TimerData(str(vals, "title", ""), num(vals, "progress", 0),
                    new SectionData.TimerData.Timer(((Number) vals.getOrDefault("elapsedMs", 0)).longValue(),
                            Boolean.TRUE.equals(vals.get("running"))));
            case "image_section" -> new SectionData.ImageData(
                    str(vals, "iconSrc", ""), str(vals, "title", ""), str(vals, "subtitle", ""));
            case "toggle_section" -> {
                List<SectionData.ToggleData.ToggleOption> options = new ArrayList<>();
                Object t = vals.get("options");
                if (t instanceof List<?> list) {
                    for (Object item : list) {
                        if (item instanceof Map<?, ?> entry) {
                            Map<String, Object> e = (Map<String, Object>) entry;
                            options.add(new SectionData.ToggleData.ToggleOption(
                                    str(e, "id", ""), str(e, "label", ""),
                                    Boolean.TRUE.equals(e.get("active"))));
                        }
                    }
                }
                yield new SectionData.ToggleData(options);
            }
            case "nav_section" -> {
                List<SectionData.NavData.NavTab> tabs = new ArrayList<>();
                Object n = vals.get("tabs");
                if (n instanceof List<?> list) {
                    for (Object item : list) {
                        if (item instanceof Map<?, ?> entry) {
                            Map<String, Object> e = (Map<String, Object>) entry;
                            tabs.add(new SectionData.NavData.NavTab(str(e, "id", ""), str(e, "label", "")));
                        }
                    }
                }
                yield new SectionData.NavData(tabs, num(vals, "activeTab", 0));
            }
            case "overlay_section" -> new SectionData.OverlayData(
                    str(vals, "title", ""), str(vals, "body", ""),
                    str(vals, "tone", "primary"), num(vals, "unreadCount", 0),
                    num(vals, "autoHideMs", 5000), Boolean.TRUE.equals(vals.get("visible")));
            default -> null;
        };
    }

    private String resolveString(String expr, Map<String, Object> trigger,
                                  Map<String, Object> data, Map<String, String> env) {
        if (expr == null) return null;
        Object resolved = VariableResolver.resolveExpression(expr, data, trigger, env);
        return resolved != null ? resolved.toString() : expr;
    }

    private static String str(Map<String, Object> m, String key, String def) {
        Object v = m.get(key);
        return v != null ? v.toString() : def;
    }

    private static int num(Map<String, Object> m, String key, int def) {
        Object v = m.get(key);
        if (v instanceof Number n) return n.intValue();
        if (v instanceof String s) {
            try { return Integer.parseInt(s); } catch (NumberFormatException ignored) {}
        }
        return def;
    }
}
