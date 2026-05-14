package com.zwbd.agentnexus.sdui.section;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
public class SectionSceneBuilder {

    private final ObjectMapper mapper;

    public SectionSceneBuilder(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    public String buildSceneJson(SectionScene scene) {
        ObjectNode root = mapper.createObjectNode();
        root.put("page_id", scene.pageId());
        root.put("layout", scene.layout().wireName());
        if (scene.autoScroll()) {
            root.put("auto_scroll", true);
            root.put("auto_scroll_ms", scene.autoScrollMs());
        }

        ArrayNode sections = root.putArray("sections");
        for (SectionEntry entry : scene.sections()) {
            sections.add(buildSectionNode(entry));
        }
        return root.toString();
    }

    public String buildPatchJson(SectionPatch patch) {
        ObjectNode root = mapper.createObjectNode();
        root.put("page_id", patch.pageId());
        ArrayNode patches = root.putArray("patches");
        for (SectionPatch.PatchEntry p : patch.patches()) {
            ObjectNode pn = mapper.createObjectNode();
            pn.put("section_id", p.sectionId());
            pn.put("op", p.op());
            pn.set("data", buildDataNode(p.data()));
            patches.add(pn);
        }
        return root.toString();
    }

    private ObjectNode buildSectionNode(SectionEntry entry) {
        ObjectNode n = mapper.createObjectNode();
        n.put("type", entry.type().wireName());
        n.put("section_id", entry.sectionId());
        n.set("data", buildDataNode(entry.data()));
        return n;
    }

    private ObjectNode buildDataNode(SectionData data) {
        if (data instanceof SectionData.HeroData d) {
            ObjectNode n = mapper.createObjectNode();
            n.put("value", d.value());
            n.put("label", d.label());
            n.put("subtitle", d.subtitle());
            n.put("tone", d.tone());
            n.put("icon_src", d.iconSrc());
            n.put("progress", d.progress());
            return n;
        }
        if (data instanceof SectionData.MetricData d) {
            ObjectNode n = mapper.createObjectNode();
            ArrayNode metrics = n.putArray("metrics");
            for (SectionData.MetricData.MetricEntry m : d.metrics()) {
                ObjectNode mn = metrics.addObject();
                mn.put("label", m.label());
                mn.put("value", m.value());
            }
            return n;
        }
        if (data instanceof SectionData.ChartData d) {
            ObjectNode n = mapper.createObjectNode();
            n.put("title", d.title());
            ArrayNode points = n.putArray("points");
            d.points().forEach(points::add);
            n.put("progress", d.progress());
            return n;
        }
        if (data instanceof SectionData.TimerData d) {
            ObjectNode n = mapper.createObjectNode();
            n.put("title", d.title());
            n.put("progress", d.progress());
            ObjectNode timer = n.putObject("timer");
            timer.put("elapsed_ms", d.timer().elapsedMs());
            timer.put("running", d.timer().running());
            return n;
        }
        if (data instanceof SectionData.ImageData d) {
            ObjectNode n = mapper.createObjectNode();
            n.put("icon_src", d.iconSrc());
            n.put("title", d.title());
            n.put("subtitle", d.subtitle());
            return n;
        }
        if (data instanceof SectionData.ActionData d) {
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
        if (data instanceof SectionData.ProgressData d) {
            ObjectNode n = mapper.createObjectNode();
            n.put("title", d.title());
            n.put("progress", d.progress());
            n.put("progress_text", d.progressText());
            return n;
        }
        if (data instanceof SectionData.TextData d) {
            ObjectNode n = mapper.createObjectNode();
            n.put("title", d.title());
            n.put("body", d.body());
            return n;
        }
        if (data instanceof SectionData.OverlayData d) {
            ObjectNode n = mapper.createObjectNode();
            n.put("title", d.title());
            n.put("body", d.body());
            n.put("tone", d.tone());
            n.put("unread_count", d.unreadCount());
            n.put("auto_hide_ms", d.autoHideMs());
            n.put("visible", d.visible());
            return n;
        }
        if (data instanceof SectionData.ListData d) {
            ObjectNode n = mapper.createObjectNode();
            ArrayNode items = n.putArray("items");
            for (SectionData.ListData.ListItem li : d.items()) {
                ObjectNode ln = items.addObject();
                ln.put("id", li.id());
                ln.put("title", li.title());
                ln.put("subtitle", li.subtitle());
                ln.put("tone", li.tone());
            }
            return n;
        }
        if (data instanceof SectionData.ToggleData d) {
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
        if (data instanceof SectionData.NavData d) {
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
        throw new IllegalArgumentException("Unknown section data type: " + data.getClass());
    }
}
