package com.zwbd.agentnexus.sdui.workflow;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.zwbd.agentnexus.sdui.section.*;
import com.zwbd.agentnexus.sdui.service.SduiProtocolService;
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
    private final SduiProtocolService protocolService;
    private final ObjectMapper objectMapper;
    private final RestTemplate restTemplate = new RestTemplate();

    public void execute(List<ActionDef> actions, WorkflowInstance instance,
                        Map<String, Object> triggerPayload, Map<String, String> env) {
        for (ActionDef action : actions) {
            try {
                dispatch(action, instance, triggerPayload, env);
            } catch (Exception e) {
                log.error("Action execution failed for device {}: action type={}, error={}",
                        instance.deviceId(), action.getClass().getSimpleName(), e.getMessage());
            }
        }
    }

    private void dispatch(ActionDef action, WorkflowInstance instance,
                          Map<String, Object> triggerPayload, Map<String, String> env) {
        if (action instanceof ActionDef.FetchAction a) {
            String url = resolveString(a.url(), triggerPayload, instance.variablesAsMap(), env);
            log.info("Fetch: {} -> {}", a.save(), url);
            try {
                String response = restTemplate.getForObject(url, String.class);
                Object parsed = objectMapper.readValue(response, Object.class);
                instance.putVariable(a.save(), parsed);
                log.info("Fetch saved to $data.{}: {} bytes", a.save(),
                        response != null ? response.length() : 0);
            } catch (Exception e) {
                log.error("Fetch failed for {}: {}", url, e.getMessage());
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
            String preset = a.preset() != null ? a.preset() : "notification";
            protocolService.sendActuatorCmd(instance.deviceId(), "audio.prompt.play",
                    "{\"preset\":\"" + preset + "\"}");
        } else if (action instanceof ActionDef.TtsAction a) {
            String text = resolveString(a.text(), triggerPayload, instance.variablesAsMap(), env);
            log.info("TTS requested (not yet implemented): {}", text);
        } else if (action instanceof ActionDef.SwitchPageAction a) {
            instance.activePage(a.page());
        } else if (action instanceof ActionDef.ControlAction a) {
            String value = resolveString(a.value(), triggerPayload, instance.variablesAsMap(), env);
            String params = value != null ? "{\"value\":\"" + value + "\"}" : "{}";
            protocolService.sendActuatorCmd(instance.deviceId(), a.command(), params);
        }
    }

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
