package com.zwbd.agentnexus.sdui.section;

import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Component
public class SectionDataCodec {

    private final SectionTypeCatalog catalog;

    public SectionDataCodec(SectionTypeCatalog catalog) {
        this.catalog = catalog;
    }

    @SuppressWarnings("unchecked")
    public SectionData buildSectionData(String type, Map<String, Object> fields, String sectionId) {
        Map<String, Object> safeFields = normalizeFields(type, fields);
        return switch (type) {
            case "hero_section" -> new SectionData.HeroData(
                    str(safeFields, "value", ""), str(safeFields, "label", ""),
                    str(safeFields, "subtitle", ""), str(safeFields, "tone", "primary"),
                    str(safeFields, "iconSrc", ""), str(safeFields, "iconSymbol", null),
                    num(safeFields, "progress", 0));
            case "metric_section" -> {
                List<SectionData.MetricData.MetricEntry> metrics = new ArrayList<>();
                Object raw = safeFields.get("metrics");
                if (raw instanceof List<?> list) {
                    for (Object item : list) {
                        if (item instanceof Map<?, ?> map) {
                            Map<String, Object> metric = (Map<String, Object>) map;
                            metrics.add(new SectionData.MetricData.MetricEntry(
                                    str(metric, "label", ""), str(metric, "value", "")));
                        }
                    }
                }
                yield new SectionData.MetricData(metrics);
            }
            case "chart_section" -> {
                List<Integer> points = new ArrayList<>();
                Object pointsObj = safeFields.get("points");
                if (pointsObj instanceof List<?> list) {
                    for (Object item : list) {
                        if (item instanceof Number n) {
                            points.add(n.intValue());
                        }
                    }
                }
                yield new SectionData.ChartData(str(safeFields, "title", ""), points, num(safeFields, "progress", 0));
            }
            case "timer_section" -> {
                Map<String, Object> timer = safeFields.get("timer") instanceof Map<?, ?> map
                        ? (Map<String, Object>) map : Map.of();
                yield new SectionData.TimerData(
                        str(safeFields, "title", ""),
                        num(safeFields, "progress", 0),
                        new SectionData.TimerData.Timer(
                                longNum(timer, "elapsedMs", 0L),
                                bool(timer, "running", false)));
            }
            case "image_section" -> new SectionData.ImageData(
                    str(safeFields, "iconSrc", ""), str(safeFields, "title", ""), str(safeFields, "subtitle", ""));
            case "action_section" -> {
                List<SectionData.ActionData.ActionButton> actions = new ArrayList<>();
                Object rawActions = safeFields.get("actions");
                if (rawActions instanceof List<?> list) {
                    for (Object item : list) {
                        if (item instanceof Map<?, ?> map) {
                            Map<String, Object> action = (Map<String, Object>) map;
                            actions.add(new SectionData.ActionData.ActionButton(
                                    str(action, "id", sectionId + "_action_" + actions.size()),
                                    str(action, "label", ""),
                                    str(action, "tone", "primary"),
                                    bool(action, "enabled", true)));
                        }
                    }
                }
                yield new SectionData.ActionData(actions);
            }
            case "progress_section" -> new SectionData.ProgressData(
                    str(safeFields, "title", ""), num(safeFields, "progress", 0), str(safeFields, "progressText", ""));
            case "text_section" -> new SectionData.TextData(str(safeFields, "title", ""), str(safeFields, "body", ""));
            case "overlay_section" -> new SectionData.OverlayData(
                    str(safeFields, "title", ""), str(safeFields, "body", ""),
                    str(safeFields, "tone", "neutral"), num(safeFields, "unreadCount", 0),
                    num(safeFields, "autoHideMs", 0));
            case "list_section" -> {
                List<SectionData.ListData.ListItem> items = new ArrayList<>();
                Object rawItems = safeFields.get("items");
                if (rawItems instanceof List<?> list) {
                    for (Object item : list) {
                        if (item instanceof Map<?, ?> map) {
                            Map<String, Object> listItem = (Map<String, Object>) map;
                            items.add(new SectionData.ListData.ListItem(
                                    str(listItem, "id", sectionId + "_item_" + items.size()),
                                    str(listItem, "title", ""),
                                    str(listItem, "subtitle", ""),
                                    str(listItem, "tone", "neutral"),
                                    str(listItem, "iconSrc", null)));
                        }
                    }
                }
                yield new SectionData.ListData(items);
            }
            case "toggle_section" -> {
                List<SectionData.ToggleData.ToggleOption> options = new ArrayList<>();
                Object rawOptions = safeFields.get("options");
                if (rawOptions instanceof List<?> list) {
                    for (Object item : list) {
                        if (item instanceof Map<?, ?> map) {
                            Map<String, Object> option = (Map<String, Object>) map;
                            options.add(new SectionData.ToggleData.ToggleOption(
                                    str(option, "id", sectionId + "_opt_" + options.size()),
                                    str(option, "label", ""),
                                    bool(option, "active", false)));
                        }
                    }
                }
                yield new SectionData.ToggleData(options);
            }
            case "nav_section" -> {
                List<SectionData.NavData.NavTab> tabs = new ArrayList<>();
                Object rawTabs = safeFields.get("tabs");
                if (rawTabs instanceof List<?> list) {
                    for (Object item : list) {
                        if (item instanceof Map<?, ?> map) {
                            Map<String, Object> tab = (Map<String, Object>) map;
                            tabs.add(new SectionData.NavData.NavTab(
                                    str(tab, "id", sectionId + "_tab_" + tabs.size()),
                                    str(tab, "label", "")));
                        }
                    }
                }
                yield new SectionData.NavData(tabs, num(safeFields, "activeTab", 0));
            }
            default -> null;
        };
    }

    public Map<String, Object> normalizeFields(String type, Map<String, Object> fields) {
        if (type == null || type.isBlank()) {
            return fields != null ? new LinkedHashMap<>(fields) : Map.of();
        }
        SectionTypeCatalog.SectionTypeDef def = catalog.get(type).orElse(null);
        if (def == null) {
            return fields != null ? new LinkedHashMap<>(fields) : Map.of();
        }
        return mergeWithDefaults(def.displayFields(), fields != null ? fields : Map.of());
    }

    public Map<String, Object> toFieldMap(SectionData data) {
        Map<String, Object> fields = new LinkedHashMap<>();
        if (data instanceof SectionData.HeroData d) {
            fields.put("value", d.value());
            fields.put("label", d.label());
            fields.put("subtitle", d.subtitle());
            fields.put("tone", d.tone());
            fields.put("iconSrc", d.iconSrc());
            fields.put("iconSymbol", d.iconSymbol());
            fields.put("progress", d.progress());
            return fields;
        }
        if (data instanceof SectionData.MetricData d) {
            List<Map<String, Object>> metrics = new ArrayList<>();
            for (var metric : d.metrics()) {
                metrics.add(Map.of("label", metric.label(), "value", metric.value()));
            }
            fields.put("metrics", metrics);
            return fields;
        }
        if (data instanceof SectionData.ChartData d) {
            fields.put("title", d.title());
            fields.put("points", new ArrayList<>(d.points()));
            fields.put("progress", d.progress());
            return fields;
        }
        if (data instanceof SectionData.TimerData d) {
            fields.put("title", d.title());
            fields.put("progress", d.progress());
            fields.put("timer", Map.of(
                    "elapsedMs", d.timer().elapsedMs(),
                    "running", d.timer().running()
            ));
            return fields;
        }
        if (data instanceof SectionData.ImageData d) {
            fields.put("iconSrc", d.iconSrc());
            fields.put("title", d.title());
            fields.put("subtitle", d.subtitle());
            return fields;
        }
        if (data instanceof SectionData.ActionData d) {
            List<Map<String, Object>> actions = new ArrayList<>();
            for (var action : d.actions()) {
                actions.add(Map.of(
                        "id", action.id(),
                        "label", action.label(),
                        "tone", action.tone(),
                        "enabled", action.enabled()
                ));
            }
            fields.put("actions", actions);
            return fields;
        }
        if (data instanceof SectionData.ProgressData d) {
            fields.put("title", d.title());
            fields.put("progress", d.progress());
            fields.put("progressText", d.progressText());
            return fields;
        }
        if (data instanceof SectionData.TextData d) {
            fields.put("title", d.title());
            fields.put("body", d.body());
            return fields;
        }
        if (data instanceof SectionData.OverlayData d) {
            fields.put("title", d.title());
            fields.put("body", d.body());
            fields.put("tone", d.tone());
            fields.put("unreadCount", d.unreadCount());
            fields.put("autoHideMs", d.autoHideMs());
            return fields;
        }
        if (data instanceof SectionData.ListData d) {
            List<Map<String, Object>> items = new ArrayList<>();
            for (var item : d.items()) {
                Map<String, Object> map = new LinkedHashMap<>();
                map.put("id", item.id());
                map.put("title", item.title());
                map.put("subtitle", item.subtitle());
                map.put("tone", item.tone());
                map.put("iconSrc", item.iconSrc());
                items.add(map);
            }
            fields.put("items", items);
            return fields;
        }
        if (data instanceof SectionData.ToggleData d) {
            List<Map<String, Object>> options = new ArrayList<>();
            for (var option : d.options()) {
                options.add(Map.of(
                        "id", option.id(),
                        "label", option.label(),
                        "active", option.active()
                ));
            }
            fields.put("options", options);
            return fields;
        }
        if (data instanceof SectionData.NavData d) {
            List<Map<String, Object>> tabs = new ArrayList<>();
            for (var tab : d.tabs()) {
                tabs.add(Map.of("id", tab.id(), "label", tab.label()));
            }
            fields.put("tabs", tabs);
            fields.put("activeTab", d.activeTab());
            return fields;
        }
        return fields;
    }

    private static String str(Map<String, Object> map, String key, String defaultValue) {
        Object value = map.get(key);
        return value != null ? String.valueOf(value) : defaultValue;
    }

    private static int num(Map<String, Object> map, String key, int defaultValue) {
        Object value = map.get(key);
        return value instanceof Number n ? n.intValue() : defaultValue;
    }

    private static long longNum(Map<String, Object> map, String key, long defaultValue) {
        Object value = map.get(key);
        return value instanceof Number n ? n.longValue() : defaultValue;
    }

    private static boolean bool(Map<String, Object> map, String key, boolean defaultValue) {
        Object value = map.get(key);
        return value instanceof Boolean b ? b : defaultValue;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> mergeWithDefaults(List<SectionTypeCatalog.SectionFieldDef> defs, Map<String, Object> rawFields) {
        Map<String, Object> normalized = new LinkedHashMap<>(catalog.defaultFieldValues(defs));
        for (SectionTypeCatalog.SectionFieldDef def : defs) {
            Object rawValue = rawFields.get(def.name());
            if (rawValue == null) {
                continue;
            }
            if ("object".equals(def.type()) && rawValue instanceof Map<?, ?> map && def.children() != null) {
                normalized.put(def.name(), mergeWithDefaults(def.children(), (Map<String, Object>) map));
                continue;
            }
            if ("array".equals(def.type()) && rawValue instanceof List<?> list && def.children() != null) {
                List<Object> items = new ArrayList<>();
                for (Object item : list) {
                    if (item instanceof Map<?, ?> map) {
                        items.add(mergeWithDefaults(def.children(), (Map<String, Object>) map));
                    } else {
                        items.add(item);
                    }
                }
                normalized.put(def.name(), items);
                continue;
            }
            normalized.put(def.name(), rawValue);
        }
        for (Map.Entry<String, Object> entry : rawFields.entrySet()) {
            normalized.putIfAbsent(entry.getKey(), entry.getValue());
        }
        return normalized;
    }
}
