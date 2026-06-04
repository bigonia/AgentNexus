package com.zwbd.agentnexus.sdui.workflow.node.device;

import com.zwbd.agentnexus.sdui.section.*;
import com.zwbd.agentnexus.sdui.workflow.VariableResolver;
import com.zwbd.agentnexus.sdui.workflow.node.CapabilityNode;
import com.zwbd.agentnexus.sdui.workflow.node.NodeContext;
import com.zwbd.agentnexus.sdui.workflow.node.NodeResult;
import com.zwbd.agentnexus.sdui.workflow.node.NodeSchema;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.*;

@Slf4j
@Component
@RequiredArgsConstructor
public class PatchSectionNode implements CapabilityNode {

    private final SectionOrchestrationService sectionService;

    @Override
    public String type() { return "device.section.push"; }

    @Override
    public NodeSchema schema() {
        return new NodeSchema(type(), "更新 Section", "向设备推送 Section 内容更新",
                "device", "layout-dashboard",
                List.of(
                        new NodeSchema.ParamDef("slotBinding", "string", true, null, "Section 槽位绑定，格式: pageId/sectionId"),
                        new NodeSchema.ParamDef("mode", "string", false, "patch", "patch / scene"),
                        new NodeSchema.ParamDef("data", "object", true, null, "Section 数据内容")
                ),
                List.of(),
                false, 5000);
    }

    @Override
    public NodeResult execute(NodeContext ctx) {
        String slotBinding = (String) ctx.resolvedInputs().get("slotBinding");
        Object dataObj = ctx.resolvedInputs().get("data");

        if (slotBinding == null || slotBinding.isEmpty()) {
            return NodeResult.error("Missing 'slotBinding'");
        }

        // Parse slotBinding: "pageId/sectionId"
        String sectionId;
        String pageId;
        int slash = slotBinding.indexOf('/');
        if (slash > 0) {
            pageId = slotBinding.substring(0, slash);
            sectionId = slotBinding.substring(slash + 1);
        } else {
            pageId = ctx.instance().activePage() != null ? ctx.instance().activePage() : "home";
            sectionId = slotBinding;
        }

        @SuppressWarnings("unchecked")
        Map<String, Object> data = dataObj instanceof Map ? (Map<String, Object>) dataObj : Map.of();
        data = resolveDataValues(data, ctx);

        String sectionType = inferSectionType(data);
        SectionData sectionData = buildSectionData(sectionType, data);

        SectionPatch patch = new SectionPatch(pageId, List.of(
                new SectionPatch.PatchEntry(sectionId, "update", null, sectionData)));
        sectionService.sendPatch(ctx.deviceId(), patch);
        log.info("PatchSection sent: device={} page={} section={} type={}", ctx.deviceId(), pageId, sectionId, sectionType);
        return NodeResult.completed(Map.of());
    }

    private String inferSectionType(Map<String, Object> vals) {
        if (vals.containsKey("actions")) return "action_section";
        if (vals.containsKey("metrics")) return "metric_section";
        if (vals.containsKey("options")) return "toggle_section";
        if (vals.containsKey("tabs")) return "nav_section";
        if (vals.containsKey("progress") && vals.containsKey("title")) return "progress_section";
        if (vals.containsKey("items")) return "list_section";
        if (vals.containsKey("points")) return "chart_section";
        if (vals.containsKey("iconSrc")) return "image_section";
        if (vals.containsKey("elapsedMs")) return "timer_section";
        if (vals.containsKey("body") && vals.containsKey("title") && vals.containsKey("tone")) return "overlay_section";
        if (vals.containsKey("body") && vals.containsKey("title")) return "text_section";
        if (vals.containsKey("value") && vals.containsKey("label")) return "hero_section";
        if (vals.containsKey("body")) return "text_section";
        return "hero_section";
    }

    @SuppressWarnings("unchecked")
    private SectionData buildSectionData(String type, Map<String, Object> vals) {
        return switch (type) {
            case "hero_section" -> new SectionData.HeroData(
                    str(vals, "value", ""), str(vals, "label", ""), str(vals, "subtitle", ""),
                    str(vals, "tone", "primary"), str(vals, "iconSrc", ""), str(vals, "iconSymbol", null),
                    num(vals, "progress", 0));
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
                                    str(e, "subtitle", ""), str(e, "tone", "primary"),
                                    str(e, "iconSrc", null)));
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
                    num(vals, "autoHideMs", 5000));
            default -> new SectionData.TextData(str(vals, "title", ""), str(vals, "body", ""));
        };
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

    @SuppressWarnings("unchecked")
    private Map<String, Object> resolveDataValues(Map<String, Object> data, NodeContext ctx) {
        Map<String, Object> resolved = new LinkedHashMap<>();
        for (var entry : data.entrySet()) {
            Object value = entry.getValue();
            if (value instanceof String s && s.contains("$")) {
                resolved.put(entry.getKey(), VariableResolver.resolveExpression(s,
                        ctx.instance().variablesAsMap(), ctx.triggerPayload(), ctx.env()));
            } else if (value instanceof Map) {
                resolved.put(entry.getKey(), resolveDataValues((Map<String, Object>) value, ctx));
            } else {
                resolved.put(entry.getKey(), value);
            }
        }
        return resolved;
    }
}
