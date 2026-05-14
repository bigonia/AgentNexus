package com.zwbd.agentnexus.sdui.section;

import com.zwbd.agentnexus.sdui.protocol.CapabilitySchema;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.*;

@Slf4j
@Component
public class SectionCapabilityAdapter {

    static final Map<String, Integer> DEFAULT_MAX_METRICS = Map.of(
            "SMALL", 4, "MEDIUM", 6, "LARGE", 8
    );
    static final Map<String, Integer> DEFAULT_MAX_POINTS = Map.of(
            "SMALL", 8, "MEDIUM", 16, "LARGE", 32
    );
    static final Map<String, Integer> DEFAULT_MAX_ACTIONS = Map.of(
            "SMALL", 2, "MEDIUM", 3, "LARGE", 4
    );
    static final Map<String, Integer> DEFAULT_MAX_LIST = Map.of(
            "SMALL", 3, "MEDIUM", 5, "LARGE", 8
    );
    static final Map<String, Integer> DEFAULT_MAX_TOGGLES = Map.of(
            "SMALL", 3, "MEDIUM", 5, "LARGE", 6
    );
    static final Map<String, Integer> DEFAULT_MAX_TABS = Map.of(
            "SMALL", 4, "MEDIUM", 5, "LARGE", 6
    );
    static final Map<String, Integer> DEFAULT_MAX_TEXT = Map.of(
            "SMALL", 0, "MEDIUM", 80, "LARGE", 300
    );
    static final Map<String, Integer> DEFAULT_MAX_OVERLAY_BODY = Map.of(
            "SMALL", 0, "MEDIUM", 100, "LARGE", 300
    );

    public SectionScene adapt(SectionScene scene, CapabilitySchema.CapabilitySnapshot caps) {
        CapabilitySchema.SectionCapability sec = caps.section();
        if (sec == null || !sec.enabled()) {
            log.warn("Device does not support section rendering, returning original scene");
            return scene;
        }

        Set<String> supportedTypes = new LinkedHashSet<>(sec.supportedSectionTypes());
        String sizeLabel = sizeLabel(caps.deviceProfile());

        List<SectionEntry> adapted = new ArrayList<>();
        for (SectionEntry entry : scene.sections()) {
            if (!supportedTypes.contains(entry.type().wireName())) {
                log.info("Skipping unsupported section type: {}", entry.type().wireName());
                continue;
            }
            SectionData adaptedData = truncateData(entry.data(), sec, sizeLabel);
            if (adaptedData != null) {
                adapted.add(new SectionEntry(entry.type(), entry.sectionId(), adaptedData));
            }
        }

        int maxPerPage = sec.getLimit("max_sections_per_page");
        if (maxPerPage > 0 && adapted.size() > maxPerPage) {
            adapted = adapted.subList(0, maxPerPage);
        }

        SectionLayout layout = selectLayout(scene.layout(), sec);
        return new SectionScene(scene.pageId(), layout, scene.autoScroll(), scene.autoScrollMs(), adapted);
    }

    private SectionData truncateData(SectionData data, CapabilitySchema.SectionCapability sec, String size) {
        if (data instanceof SectionData.MetricData d) {
            int max = sec.getLimit("max_metrics");
            if (max <= 0) max = DEFAULT_MAX_METRICS.getOrDefault(size, 4);
            if (d.metrics().size() > max) {
                return new SectionData.MetricData(d.metrics().subList(0, max));
            }
            return d;
        }
        if (data instanceof SectionData.ChartData d) {
            int max = sec.getLimit("max_points");
            if (max <= 0) max = DEFAULT_MAX_POINTS.getOrDefault(size, 16);
            if (d.points().size() > max) {
                return new SectionData.ChartData(d.title(), d.points().subList(0, max), d.progress());
            }
            return d;
        }
        if (data instanceof SectionData.ActionData d) {
            int max = sec.getLimit("max_actions");
            if (max <= 0) max = DEFAULT_MAX_ACTIONS.getOrDefault(size, 3);
            if (d.actions().size() > max) {
                return new SectionData.ActionData(d.actions().subList(0, max));
            }
            return d;
        }
        if (data instanceof SectionData.ListData d) {
            int max = sec.getLimit("max_list_items");
            if (max <= 0) max = DEFAULT_MAX_LIST.getOrDefault(size, 5);
            if (d.items().size() > max) {
                return new SectionData.ListData(d.items().subList(0, max));
            }
            return d;
        }
        if (data instanceof SectionData.ToggleData d) {
            int max = sec.getLimit("max_toggle_options");
            if (max <= 0) max = DEFAULT_MAX_TOGGLES.getOrDefault(size, 5);
            if (d.options().size() > max) {
                return new SectionData.ToggleData(d.options().subList(0, max));
            }
            return d;
        }
        if (data instanceof SectionData.NavData d) {
            int max = sec.getLimit("max_nav_tabs");
            if (max <= 0) max = DEFAULT_MAX_TABS.getOrDefault(size, 5);
            if (d.tabs().size() > max) {
                return new SectionData.NavData(d.tabs().subList(0, max), Math.min(d.activeTab(), max - 1));
            }
            return d;
        }
        if (data instanceof SectionData.TextData d) {
            int max = sec.getLimit("max_text_chars");
            if (max <= 0) max = DEFAULT_MAX_TEXT.getOrDefault(size, 80);
            if (max == 0) {
                log.warn("Text section not supported on size={}, skipping", size);
                return null;
            }
            if (d.body().length() > max) {
                return new SectionData.TextData(d.title(), d.body().substring(0, max) + "...");
            }
            return d;
        }
        if (data instanceof SectionData.OverlayData d) {
            int max = sec.getLimit("max_overlay_body");
            if (max <= 0) max = DEFAULT_MAX_OVERLAY_BODY.getOrDefault(size, 100);
            if (max == 0) {
                log.warn("Overlay section not supported on size={}, skipping", size);
                return null;
            }
            if (d.body().length() > max) {
                return new SectionData.OverlayData(
                        d.title(), d.body().substring(0, max) + "...",
                        d.tone(), d.unreadCount(), d.autoHideMs(), d.visible());
            }
            return d;
        }
        return data;
    }

    private SectionLayout selectLayout(SectionLayout requested, CapabilitySchema.SectionCapability sec) {
        if (sec.supportsLayout(requested.wireName())) return requested;
        for (SectionLayout fallback : SectionLayout.values()) {
            if (sec.supportsLayout(fallback.wireName())) {
                log.info("Layout {} not supported, falling back to {}", requested, fallback);
                return fallback;
            }
        }
        return SectionLayout.VERTICAL_SCROLL;
    }

    static String sizeLabel(CapabilitySchema.DeviceProfile profile) {
        if (profile == null) return "MEDIUM";
        int maxDim = Math.max(profile.screenW(), profile.screenH());
        if (maxDim < 200) return "SMALL";
        if (maxDim >= 400) return "LARGE";
        return "MEDIUM";
    }
}
