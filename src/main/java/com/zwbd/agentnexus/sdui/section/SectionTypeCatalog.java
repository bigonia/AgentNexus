package com.zwbd.agentnexus.sdui.section;

import java.util.*;

/**
 * Platform-defined catalog of all Section template types.
 *
 * Each Section type defines:
 * - display fields: what data the platform can send
 * - interaction events: what events the device can emit when user interacts with this section
 * - default constraints: rendering limits
 *
 * This is static because Section types are a protocol contract between platform and device firmware.
 * Which types a specific device supports comes from its capability report.
 *
 * The key addition over the existing SectionData sealed interface is {@code interactionEvents}:
 * they establish the bridge between Section UI interactions and workflow event triggers.
 */
public final class SectionTypeCatalog {

    private SectionTypeCatalog() {}

    public record SectionTypeDef(
            String type,                      // e.g. "hero_section"
            String displayName,               // e.g. "Hero 数据"
            boolean interactive,              // does this section produce events?
            List<SectionFieldDef> displayFields,  // fields with type metadata for dynamic form rendering
            List<InteractionEvent> interactionEvents,  // events this section can emit
            Map<String, Integer> defaultConstraints,   // e.g. {max_actions: 4}
            Set<String> compactHiddenFields   // JSON wire field names omitted in COMPACT mode per §5
    ) {
        /** Convenience constructor with no compact-hidden fields. */
        public SectionTypeDef(String type, String displayName, boolean interactive,
                              List<SectionFieldDef> displayFields,
                              List<InteractionEvent> interactionEvents,
                              Map<String, Integer> defaultConstraints) {
            this(type, displayName, interactive, displayFields, interactionEvents,
                    defaultConstraints, Set.of());
        }

        /**
         * @return true if the given JSON wire field should be rendered in the specified mode.
         * Supports both top-level field names ({@code "subtitle"}) and nested paths
         * ({@code "items[].subtitle"}).
         */
        public boolean isFieldVisible(String jsonFieldName, SectionRenderMode mode) {
            if (mode == SectionRenderMode.RICH) return true;
            return !compactHiddenFields.contains(jsonFieldName);
        }

        /** @return all JSON wire field names hidden in COMPACT mode (camelCase). */
        public Set<String> compactHiddenFields() {
            return compactHiddenFields;
        }
    }

    public record SectionFieldDef(
            String name,        // e.g. "title", "progress", "actions[]"
            String type,        // "string" | "int" | "boolean" | "enum" | "color" | "array" | "object"
            String label,       // human-readable label
            Object defaultValue,
            Integer min,        // for int type
            Integer max,        // for int type
            List<String> options, // for enum type
            List<SectionFieldDef> children  // for array/object types, describes nested fields
    ) {
        // Simple field (string, boolean) with default value
        public SectionFieldDef(String name, String type, String label, Object defaultValue) {
            this(name, type, label, defaultValue, null, null, null, null);
        }
        // Int field with min/max
        public SectionFieldDef(String name, String type, String label, Object defaultValue,
                               Integer min, Integer max) {
            this(name, type, label, defaultValue, min, max, null, null);
        }
        // Enum field — use factory method instead
        // Array/Object field — use factory method instead

        public static SectionFieldDef enumField(String name, String label, String defaultValue,
                                                 List<String> options) {
            return new SectionFieldDef(name, "enum", label, defaultValue, null, null, options, null);
        }
        public static SectionFieldDef arrayField(String name, String label,
                                                  List<SectionFieldDef> children) {
            return new SectionFieldDef(name, "array", label, null, null, null, null, children);
        }
        public static SectionFieldDef objectField(String name, String label,
                                                   List<SectionFieldDef> children) {
            return new SectionFieldDef(name, "object", label, null, null, null, null, children);
        }
    }

    public record InteractionEvent(
            String eventId,                   // e.g. "section.action.button_click"
            String description,
            List<ParamDef> params             // event-specific parameters
    ) {}

    public record ParamDef(
            String name,
            String type,
            String description
    ) {}

    private static final Map<String, SectionTypeDef> CATALOG = new LinkedHashMap<>();

    static {
        // ── Display-only sections ──

        register(new SectionTypeDef("hero_section", "Hero 数据", false,
                List.of(
                        new SectionFieldDef("value", "string", "数值", ""),
                        new SectionFieldDef("label", "string", "标签", ""),
                        new SectionFieldDef("subtitle", "string", "副标题", ""),
                        SectionFieldDef.enumField("tone", "色调", "primary",
                                List.of("primary", "success", "warning", "danger", "secondary")),
                        SectionFieldDef.enumField("iconSrc", "图标", "",
                                        List.of("start", "mail", "chat", "file", "love", "droplet", "doubt", "veins", "dollar", "doller")),
                                SectionFieldDef.enumField("iconSymbol", "图标符号", "",
                                        List.of("start", "mail", "chat", "file", "love", "droplet", "doubt", "veins", "dollar", "doller")),
                        new SectionFieldDef("progress", "int", "进度", 0, 0, 100)
                ),
                List.of(), Map.of(),
                Set.of("subtitle", "iconSrc", "iconSymbol", "progress")));  // §5.1 compact

        register(new SectionTypeDef("metric_section", "指标网格", false,
                List.of(
                        SectionFieldDef.arrayField("metrics", "指标列表",
                                List.of(
                                        new SectionFieldDef("label", "string", "标签", ""),
                                        new SectionFieldDef("value", "string", "数值", "")
                                ))
                ),
                List.of(), Map.of()));

        register(new SectionTypeDef("chart_section", "图表", false,
                List.of(
                        new SectionFieldDef("title", "string", "标题", ""),
                        new SectionFieldDef("points", "array", "数据点 (int[])", null),
                        new SectionFieldDef("progress", "int", "进度", 0, 0, 100)
                ),
                List.of(), Map.of(),
                Set.of("progress")));  // §5.3 compact

        register(new SectionTypeDef("progress_section", "进度条", false,
                List.of(
                        new SectionFieldDef("title", "string", "标题", ""),
                        new SectionFieldDef("progress", "int", "进度", 0, 0, 100),
                        new SectionFieldDef("progressText", "string", "进度文本", "")
                ),
                List.of(), Map.of(),
                Set.of("title")));  // §5.7 compact

        register(new SectionTypeDef("text_section", "文本", false,
                List.of(
                        new SectionFieldDef("title", "string", "标题", ""),
                        new SectionFieldDef("body", "string", "正文", "")
                ),
                List.of(), Map.of()));

        register(new SectionTypeDef("timer_section", "计时器", false,
                List.of(
                        new SectionFieldDef("title", "string", "标题", ""),
                        new SectionFieldDef("progress", "int", "进度", 0, 0, 100),
                        SectionFieldDef.objectField("timer", "计时器",
                                List.of(
                                        new SectionFieldDef("elapsedMs", "int", "已过毫秒", 0),
                                        new SectionFieldDef("running", "boolean", "运行中", false)
                                ))
                ),
                List.of(), Map.of(),
                Set.of("title", "progress")));  // §5.4 compact

        register(new SectionTypeDef("image_section", "图片", false,
                List.of(
                        SectionFieldDef.enumField("iconSrc", "图标", "chat",
                                List.of("start", "mail", "chat", "file", "love", "droplet", "doubt", "veins", "dollar", "doller")),
                        new SectionFieldDef("title", "string", "标题", ""),
                        new SectionFieldDef("subtitle", "string", "副标题", "")
                ),
                List.of(), Map.of(),
                Set.of("subtitle")));  // §5.5 compact

        register(new SectionTypeDef("overlay_section", "覆盖层", true,
                List.of(
                        new SectionFieldDef("title", "string", "标题", ""),
                        new SectionFieldDef("body", "string", "正文", ""),
                        SectionFieldDef.enumField("tone", "色调", "warning",
                                List.of("warning", "success", "danger", "primary")),
                        new SectionFieldDef("unreadCount", "int", "未读数", 0, 0, 999),
                        new SectionFieldDef("autoHideMs", "int", "自动隐藏(ms)", 0, 0, 30000)
                ),
                List.of(
                        new InteractionEvent("overlay.confirm", "用户关闭/确认弹窗",
                                List.of(
                                        new ParamDef("sectionId", "string", "触发事件的 Section ID")
                                ))
                ),
                Map.of()));

        // ── Interactive sections ──

        register(new SectionTypeDef("action_section", "操作按钮", true,
                List.of(
                        SectionFieldDef.arrayField("actions", "按钮列表",
                                List.of(
                                        new SectionFieldDef("id", "string", "按钮ID", ""),
                                        new SectionFieldDef("label", "string", "按钮文本", ""),
                                        SectionFieldDef.enumField("tone", "样式", "primary",
                                                List.of("primary", "secondary", "success", "warning", "danger")),
                                        new SectionFieldDef("enabled", "boolean", "启用", true)
                                ))
                ),
                List.of(
                        new InteractionEvent("action.click", "用户点击按钮",
                                List.of(
                                        new ParamDef("sectionId", "string", "触发事件的 Section ID"),
                                        new ParamDef("buttonId", "string", "被点击的按钮 ID"),
                                        new ParamDef("pageId", "string", "所在页面 ID")
                                ))
                ),
                Map.of("max_actions", 4)));

        register(new SectionTypeDef("toggle_section", "开关组", true,
                List.of(
                        SectionFieldDef.arrayField("options", "选项列表",
                                List.of(
                                        new SectionFieldDef("id", "string", "选项ID", ""),
                                        new SectionFieldDef("label", "string", "选项文本", ""),
                                        new SectionFieldDef("active", "boolean", "激活状态", false)
                                ))
                ),
                List.of(
                        new InteractionEvent("toggle.change", "用户切换开关",
                                List.of(
                                        new ParamDef("sectionId", "string", "触发事件的 Section ID"),
                                        new ParamDef("optionId", "string", "被切换的选项 ID"),
                                        new ParamDef("active", "boolean", "切换后的状态")
                                ))
                ),
                Map.of("max_options", 6)));

        register(new SectionTypeDef("list_section", "列表", true,
                List.of(
                        SectionFieldDef.arrayField("items", "列表项",
                                List.of(
                                        new SectionFieldDef("id", "string", "项ID", ""),
                                        new SectionFieldDef("title", "string", "标题", ""),
                                        new SectionFieldDef("subtitle", "string", "副标题", ""),
                                        SectionFieldDef.enumField("tone", "色调", "neutral",
                                                List.of("neutral", "primary", "success", "warning", "danger")),
                                        SectionFieldDef.enumField("iconSrc", "图标", "",
                                                List.of("start", "mail", "chat", "file", "love", "droplet", "doubt", "veins", "dollar", "doller"))
                                ))
                ),
                List.of(
                        new InteractionEvent("list.select", "用户点击列表项",
                                List.of(
                                        new ParamDef("sectionId", "string", "触发事件的 Section ID"),
                                        new ParamDef("itemId", "string", "被点击的列表项 ID")
                                ))
                ),
                Map.of("max_items", 8),
                Set.of("items[].subtitle", "items[].iconSrc")));  // §5.9 compact

        register(new SectionTypeDef("nav_section", "导航标签", false,
                List.of(
                        SectionFieldDef.arrayField("tabs", "标签列表",
                                List.of(
                                        new SectionFieldDef("id", "string", "标签ID", ""),
                                        new SectionFieldDef("label", "string", "标签文本", "")
                                )),
                        new SectionFieldDef("activeTab", "int", "当前激活Tab索引", 0, 0, 10)
                ),
                List.of(),
                Map.of()));
    }

    private static void register(SectionTypeDef def) {
        CATALOG.put(def.type(), def);
    }

    // ── Query ──

    public static Optional<SectionTypeDef> get(String type) {
        return Optional.ofNullable(CATALOG.get(type));
    }

    public static SectionTypeDef getOrThrow(String type) {
        SectionTypeDef def = CATALOG.get(type);
        if (def == null) throw new IllegalArgumentException("Unknown section type: " + type);
        return def;
    }

    public static Map<String, SectionTypeDef> all() {
        return Collections.unmodifiableMap(CATALOG);
    }

    public static Set<String> allTypes() {
        return Collections.unmodifiableSet(CATALOG.keySet());
    }

    /** Serialize a list of SectionFieldDef to frontend-friendly maps (all fields, no filtering). */
    public static List<Map<String, Object>> fieldsToMaps(List<SectionFieldDef> fields) {
        return fieldsToMaps(fields, SectionRenderMode.RICH, Set.of());
    }

    public static Map<String, Object> defaultFieldValues(List<SectionFieldDef> fields) {
        Map<String, Object> defaults = new LinkedHashMap<>();
        for (SectionFieldDef field : fields) {
            defaults.put(field.name(), defaultValueFor(field));
        }
        return defaults;
    }

    public static Map<String, Object> defaultFieldValues(String sectionType) {
        SectionTypeDef def = getOrThrow(sectionType);
        return defaultFieldValues(def.displayFields());
    }

    public static Map<String, Object> sampleFieldValues(List<SectionFieldDef> fields) {
        Map<String, Object> sample = new LinkedHashMap<>();
        for (SectionFieldDef field : fields) {
            sample.put(field.name(), sampleValueFor(field));
        }
        return sample;
    }

    /**
     * Serialize fields to frontend-friendly maps, filtering out compact-hidden fields
     * when the render mode is COMPACT.
     *
     * @param fields              the field definitions to serialize
     * @param mode                the device's render mode
     * @param compactHiddenFields field names (camelCase) hidden in COMPACT mode;
     *                            nested children use {@code "parent[].child"} notation
     */
    public static List<Map<String, Object>> fieldsToMaps(List<SectionFieldDef> fields,
                                                          SectionRenderMode mode,
                                                          Set<String> compactHiddenFields) {
        if (mode == SectionRenderMode.RICH || compactHiddenFields.isEmpty()) {
            return fieldsToMapsUnfiltered(fields);
        }
        return fieldsToMapsFiltered(fields, compactHiddenFields, "");
    }

    private static List<Map<String, Object>> fieldsToMapsUnfiltered(List<SectionFieldDef> fields) {
        List<Map<String, Object>> result = new ArrayList<>();
        for (SectionFieldDef f : fields) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("name", f.name());
            m.put("type", f.type());
            m.put("label", f.label());
            if (f.defaultValue() != null) m.put("default", f.defaultValue());
            if (f.min() != null) m.put("min", f.min());
            if (f.max() != null) m.put("max", f.max());
            if (f.options() != null) m.put("options", f.options());
            if (f.children() != null) m.put("children", fieldsToMapsUnfiltered(f.children()));
            result.add(m);
        }
        return result;
    }

    private static List<Map<String, Object>> fieldsToMapsFiltered(List<SectionFieldDef> fields,
                                                                   Set<String> compactHiddenFields,
                                                                   String parentPath) {
        List<Map<String, Object>> result = new ArrayList<>();
        for (SectionFieldDef f : fields) {
            // Top-level: check field name directly
            // Nested (inside array/object): check "parent[].childName" notation
            if (!parentPath.isEmpty()) {
                String nestedPath = parentPath + "[]." + f.name();
                if (compactHiddenFields.contains(nestedPath)) continue;
            } else {
                if (compactHiddenFields.contains(f.name())) continue;
            }

            Map<String, Object> m = new LinkedHashMap<>();
            m.put("name", f.name());
            m.put("type", f.type());
            m.put("label", f.label());
            if (f.defaultValue() != null) m.put("default", f.defaultValue());
            if (f.min() != null) m.put("min", f.min());
            if (f.max() != null) m.put("max", f.max());
            if (f.options() != null) m.put("options", f.options());
            if (f.children() != null) {
                m.put("children", fieldsToMapsFiltered(f.children(), compactHiddenFields, f.name()));
            }
            result.add(m);
        }
        return result;
    }

    private static Object defaultValueFor(SectionFieldDef field) {
        if ("array".equals(field.type())) {
            return List.of();
        }
        if ("object".equals(field.type())) {
            return field.children() != null ? defaultFieldValues(field.children()) : Map.of();
        }
        if (field.defaultValue() != null) {
            return field.defaultValue();
        }
        return switch (field.type()) {
            case "string", "color", "enum" -> "";
            case "int" -> 0;
            case "boolean" -> false;
            default -> null;
        };
    }

    private static Object sampleValueFor(SectionFieldDef field) {
        if ("array".equals(field.type())) {
            if (field.children() == null || field.children().isEmpty()) {
                return List.of();
            }
            return List.of(sampleFieldValues(field.children()));
        }
        if ("object".equals(field.type())) {
            return field.children() != null ? sampleFieldValues(field.children()) : Map.of();
        }
        if ("enum".equals(field.type()) && field.options() != null && !field.options().isEmpty()) {
            return field.options().get(0);
        }
        if ("string".equals(field.type()) || "color".equals(field.type())) {
            return field.label() + "示例";
        }
        if ("int".equals(field.type())) {
            if (field.defaultValue() instanceof Number n) {
                return n.intValue();
            }
            return field.min() != null ? field.min() : 1;
        }
        if ("boolean".equals(field.type())) {
            return Boolean.TRUE.equals(field.defaultValue()) ? Boolean.TRUE : Boolean.FALSE;
        }
        return defaultValueFor(field);
    }

    /** All interaction event IDs across all section types. */
    public static Set<String> allInteractionEventIds() {
        Set<String> ids = new LinkedHashSet<>();
        for (SectionTypeDef def : CATALOG.values()) {
            for (InteractionEvent evt : def.interactionEvents()) {
                ids.add(evt.eventId());
            }
        }
        return Collections.unmodifiableSet(ids);
    }

    /** Interaction events emitted by a specific section type. */
    public static List<InteractionEvent> getInteractionEvents(String sectionType) {
        SectionTypeDef def = CATALOG.get(sectionType);
        return def != null ? def.interactionEvents() : List.of();
    }

    /** Which section types emit a given interaction event. */
    public static List<String> getSectionTypesForEvent(String eventId) {
        List<String> types = new ArrayList<>();
        for (SectionTypeDef def : CATALOG.values()) {
            for (InteractionEvent evt : def.interactionEvents()) {
                if (evt.eventId().equals(eventId)) {
                    types.add(def.type());
                    break;
                }
            }
        }
        return types;
    }

    /** All interactive section types (ones that produce events). */
    public static List<SectionTypeDef> interactiveTypes() {
        return CATALOG.values().stream()
                .filter(SectionTypeDef::interactive)
                .toList();
    }

    /** Display-only section types (no interaction events). */
    public static List<SectionTypeDef> displayOnlyTypes() {
        return CATALOG.values().stream()
                .filter(d -> !d.interactive())
                .toList();
    }

    // ── Wire name mapping ──

    public static String toWireName(String type) {
        SectionTypeDef def = CATALOG.get(type);
        return def != null ? def.type() : type;
    }
}
