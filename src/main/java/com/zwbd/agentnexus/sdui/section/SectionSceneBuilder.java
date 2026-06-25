package com.zwbd.agentnexus.sdui.section;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Set;

@Component
public class SectionSceneBuilder {

    private final ObjectMapper mapper;
    private final SectionTypeCatalog catalog;

    public SectionSceneBuilder(ObjectMapper mapper, SectionTypeCatalog catalog) {
        this.mapper = mapper;
        this.catalog = catalog;
    }

    // ── Public API ──

    /** Serialize a scene using its embedded render mode (defaults to RICH). */
    public String buildSceneJson(SectionScene scene) {
        return buildSceneJson(scene, scene.effectiveRenderMode());
    }

    /** Serialize a scene with an explicit render mode. */
    public String buildSceneJson(SectionScene scene, SectionRenderMode mode) {
        ObjectNode root = mapper.createObjectNode();
        root.put("page_id", scene.pageId());
        root.put("layout", scene.layout().wireName());
        if (scene.autoScroll()) {
            root.put("auto_scroll", true);
            root.put("auto_scroll_ms", scene.autoScrollMs());
        }

        ArrayNode sections = root.putArray("sections");
        for (SectionEntry entry : scene.sections()) {
            sections.add(buildSectionNode(entry, mode));
        }
        return root.toString();
    }

    /** Serialize a patch using its embedded render mode (defaults to RICH). */
    public String buildPatchJson(SectionPatch patch) {
        return buildPatchJson(patch, patch.effectiveRenderMode());
    }

    /** Serialize a patch with an explicit render mode. */
    public String buildPatchJson(SectionPatch patch, SectionRenderMode mode) {
        ObjectNode root = mapper.createObjectNode();
        root.put("page_id", patch.pageId());
        ArrayNode patches = root.putArray("patches");
        for (SectionPatch.PatchEntry p : patch.patches()) {
            ObjectNode pn = mapper.createObjectNode();
            pn.put("section_id", p.sectionId());
            pn.put("op", p.op());
            if (p.type() != null) {
                pn.put("type", p.type());
            }
            if (p.data() != null) {
                pn.set("data", buildDataNode(p.data(), p.type(), mode));
            }
            patches.add(pn);
        }
        return root.toString();
    }

    // ── Internal builders ──

    private ObjectNode buildSectionNode(SectionEntry entry, SectionRenderMode mode) {
        ObjectNode n = mapper.createObjectNode();
        n.put("type", entry.type());
        n.put("section_id", entry.sectionId());
        n.set("data", buildDataNode(entry.data(), entry.type(), mode));
        return n;
    }

    private ObjectNode buildDataNode(SectionData data, String sectionType, SectionRenderMode mode) {
        if (data instanceof SectionData.HeroData d) {
            return buildHero(d, mode);
        }
        if (data instanceof SectionData.MetricData d) {
            return buildMetric(d, mode);
        }
        if (data instanceof SectionData.ChartData d) {
            return buildChart(d, mode);
        }
        if (data instanceof SectionData.TimerData d) {
            return buildTimer(d, mode);
        }
        if (data instanceof SectionData.ImageData d) {
            return buildImage(d, mode);
        }
        if (data instanceof SectionData.ActionData d) {
            return buildAction(d, mode);
        }
        if (data instanceof SectionData.ProgressData d) {
            return buildProgress(d, mode);
        }
        if (data instanceof SectionData.TextData d) {
            return buildText(d, mode);
        }
        if (data instanceof SectionData.OverlayData d) {
            return buildOverlay(d, mode);
        }
        if (data instanceof SectionData.ListData d) {
            return buildList(d, mode);
        }
        if (data instanceof SectionData.ToggleData d) {
            return buildToggle(d, mode);
        }
        if (data instanceof SectionData.NavData d) {
            return buildNav(d, mode);
        }
        throw new IllegalArgumentException("Unknown section data type: " + data.getClass());
    }

    // ── Per-type builders with compact field filtering ──

    private ObjectNode buildHero(SectionData.HeroData d, SectionRenderMode mode) {
        Set<String> hidden = compactHidden(sectionType("hero_section"));
        ObjectNode n = mapper.createObjectNode();
        n.put("value", d.value());
        n.put("label", d.label());
        if (isVisible(mode, hidden, "subtitle")) n.put("subtitle", d.subtitle());
        n.put("tone", d.tone());
        if (isVisible(mode, hidden, "icon_src")) n.put("icon_src", d.iconSrc());
        if (isVisible(mode, hidden, "icon_symbol")) n.put("icon_symbol", d.iconSymbol());
        if (isVisible(mode, hidden, "progress")) n.put("progress", d.progress());
        return n;
    }

    private ObjectNode buildMetric(SectionData.MetricData d, SectionRenderMode mode) {
        ObjectNode n = mapper.createObjectNode();
        ArrayNode metrics = n.putArray("metrics");
        for (SectionData.MetricData.MetricEntry m : d.metrics()) {
            ObjectNode mn = metrics.addObject();
            mn.put("label", m.label());
            mn.put("value", m.value());
        }
        return n;
    }

    private ObjectNode buildChart(SectionData.ChartData d, SectionRenderMode mode) {
        Set<String> hidden = compactHidden(sectionType("chart_section"));
        ObjectNode n = mapper.createObjectNode();
        n.put("title", d.title());
        ArrayNode points = n.putArray("points");
        d.points().forEach(points::add);
        if (isVisible(mode, hidden, "progress")) n.put("progress", d.progress());
        return n;
    }

    private ObjectNode buildTimer(SectionData.TimerData d, SectionRenderMode mode) {
        Set<String> hidden = compactHidden(sectionType("timer_section"));
        ObjectNode n = mapper.createObjectNode();
        if (isVisible(mode, hidden, "title")) n.put("title", d.title());
        if (isVisible(mode, hidden, "progress")) n.put("progress", d.progress());
        ObjectNode timer = n.putObject("timer");
        timer.put("elapsed_ms", d.timer().elapsedMs());
        timer.put("running", d.timer().running());
        return n;
    }

    private ObjectNode buildImage(SectionData.ImageData d, SectionRenderMode mode) {
        Set<String> hidden = compactHidden(sectionType("image_section"));
        ObjectNode n = mapper.createObjectNode();
        // §5.5: image_section accepts both icon_src and image_src as aliases
        String icon = d.iconSrc();
        n.put("icon_src", icon);
        if (icon != null && !icon.isEmpty()) {
            n.put("image_src", icon);  // alias for firmware compatibility
        }
        n.put("title", d.title());
        if (isVisible(mode, hidden, "subtitle")) n.put("subtitle", d.subtitle());
        return n;
    }

    private ObjectNode buildAction(SectionData.ActionData d, SectionRenderMode mode) {
        ObjectNode n = mapper.createObjectNode();
        ArrayNode actions = n.putArray("actions");
        for (SectionData.ActionData.ActionButton a : d.actions()) {
            ObjectNode an = actions.addObject();
            an.put("id", a.id());
            an.put("label", a.label());
            an.put("tone", a.tone());
            an.put("enabled", a.enabled());
        }
        return n;
    }

    private ObjectNode buildProgress(SectionData.ProgressData d, SectionRenderMode mode) {
        Set<String> hidden = compactHidden(sectionType("progress_section"));
        ObjectNode n = mapper.createObjectNode();
        if (isVisible(mode, hidden, "title")) n.put("title", d.title());
        n.put("progress", d.progress());
        n.put("progress_text", d.progressText());
        return n;
    }

    private ObjectNode buildText(SectionData.TextData d, SectionRenderMode mode) {
        ObjectNode n = mapper.createObjectNode();
        n.put("title", d.title());
        n.put("body", d.body());
        return n;
    }

    private ObjectNode buildOverlay(SectionData.OverlayData d, SectionRenderMode mode) {
        ObjectNode n = mapper.createObjectNode();
        n.put("title", d.title());
        n.put("body", d.body());
        n.put("tone", d.tone());
        n.put("unread_count", d.unreadCount());
        n.put("auto_hide_ms", d.autoHideMs());
        // Note: 'visible' removed per SECTION_SCHEMA.md §5.11 — overlay is always shown when sent
        return n;
    }

    private ObjectNode buildList(SectionData.ListData d, SectionRenderMode mode) {
        Set<String> hidden = compactHidden(sectionType("list_section"));
        boolean hideSubtitle = mode == SectionRenderMode.COMPACT && hidden.contains("items[].subtitle");
        boolean hideIconSrc = mode == SectionRenderMode.COMPACT && hidden.contains("items[].iconSrc");

        ObjectNode n = mapper.createObjectNode();
        ArrayNode items = n.putArray("items");
        for (SectionData.ListData.ListItem li : d.items()) {
            ObjectNode ln = items.addObject();
            ln.put("id", li.id());
            ln.put("title", li.title());
            if (!hideSubtitle) ln.put("subtitle", li.subtitle());
            ln.put("tone", li.tone());
            if (!hideIconSrc) ln.put("icon_src", li.iconSrc());
        }
        return n;
    }

    private ObjectNode buildToggle(SectionData.ToggleData d, SectionRenderMode mode) {
        ObjectNode n = mapper.createObjectNode();
        ArrayNode options = n.putArray("options");
        for (SectionData.ToggleData.ToggleOption opt : d.options()) {
            ObjectNode on = options.addObject();
            on.put("id", opt.id());
            on.put("label", opt.label());
            on.put("active", opt.active());
        }
        return n;
    }

    private ObjectNode buildNav(SectionData.NavData d, SectionRenderMode mode) {
        ObjectNode n = mapper.createObjectNode();
        ArrayNode tabs = n.putArray("tabs");
        for (SectionData.NavData.NavTab tab : d.tabs()) {
            ObjectNode tn = tabs.addObject();
            tn.put("id", tab.id());
            tn.put("label", tab.label());
        }
        n.put("active_tab", d.activeTab());
        return n;
    }

    // ── Helpers ──

    private static String sectionType(String wireName) {
        return wireName;
    }

    private Set<String> compactHidden(String sectionType) {
        return catalog.get(sectionType)
                .map(SectionTypeCatalog.SectionTypeDef::compactHiddenFields)
                .orElse(Set.of());
    }

    /**
     * Check whether a JSON wire field (snake_case) should be visible.
     * The hidden set uses camelCase field names, so we convert before checking.
     */
    private static boolean isVisible(SectionRenderMode mode, Set<String> hidden, String jsonField) {
        if (mode == SectionRenderMode.RICH) return true;
        return !hidden.contains(snakeToCamel(jsonField));
    }

    /** Convert snake_case to camelCase (e.g. "icon_src" → "iconSrc"). */
    private static String snakeToCamel(String snake) {
        StringBuilder sb = new StringBuilder(snake.length());
        boolean upper = false;
        for (int i = 0; i < snake.length(); i++) {
            char c = snake.charAt(i);
            if (c == '_') {
                upper = true;
            } else if (upper) {
                sb.append(Character.toUpperCase(c));
                upper = false;
            } else {
                sb.append(c);
            }
        }
        return sb.toString();
    }
}
