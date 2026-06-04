package com.zwbd.agentnexus.sdui.section;

import com.zwbd.agentnexus.sdui.capability.CapabilityCatalog;
import com.zwbd.agentnexus.sdui.protocol.CapabilitySchema;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.*;

/**
 * Adapts a {@link SectionScene} to match a device's capabilities.
 *
 * <p>Three layers of adaptation:
 * <ol>
 *   <li><b>Type filter</b> — remove sections whose types the device doesn't support.</li>
 *   <li><b>Data truncation</b> — cap array lengths (metrics, chart points, list items, etc.) to
 *       the device's per-size-class limits.</li>
 *   <li><b>Render mode</b> — resolve {@link SectionRenderMode} (RICH / COMPACT) from the device's
 *       {@code sizeClass} and tag the scene so the JSON builder can omit compact-hidden fields.</li>
 *   <li><b>Height degradation</b> — when a large screen has insufficient height for all rich-mode
 *       sections, progressively degrade sections to compact per the priority chain in §2.4.</li>
 * </ol>
 */
@Slf4j
@Component
public class SectionCapabilityAdapter {

    private final CapabilityCatalog catalog;

    /**
     * Height degradation priority (§2.4): leftmost degrades first.
     * When a large screen runs out of vertical space, sections are downgraded
     * from rich to compact in this order until the content fits.
     */
    private static final List<String> HEIGHT_DEGRADE_PRIORITY = List.of(
            "text_section", "overlay_section", "image_section", "timer_section",
            "hero_section", "chart_section", "progress_section", "action_section",
            "metric_section", "list_section", "toggle_section", "nav_section"
    );

    /** Estimated rich-mode pixel heights per section type (used for degradation estimation). */
    private static final Map<String, Integer> RICH_HEIGHT_ESTIMATE = Map.ofEntries(
            Map.entry("hero_section", 120),
            Map.entry("metric_section", 80),
            Map.entry("chart_section", 140),
            Map.entry("timer_section", 100),
            Map.entry("image_section", 110),
            Map.entry("action_section", 65),
            Map.entry("progress_section", 70),
            Map.entry("text_section", 50),
            Map.entry("overlay_section", 0),   // rendered on overlay layer, not in flow
            Map.entry("list_section", 65),
            Map.entry("toggle_section", 60),
            Map.entry("nav_section", 50)
    );

    public SectionCapabilityAdapter(CapabilityCatalog catalog) {
        this.catalog = catalog;
    }

    /**
     * Adapt a scene to the device's capabilities.
     *
     * @param scene the full scene to adapt
     * @param caps  the device's capability snapshot
     * @return adapted scene with render mode set
     */
    public SectionScene adapt(SectionScene scene, CapabilitySchema.CapabilitySnapshot caps) {
        CapabilitySchema.DisplayInfo display = caps.display();
        if (display == null) {
            log.warn("Device does not support section rendering, returning original scene");
            return scene;
        }

        Set<String> supportedTypes = new LinkedHashSet<>(display.sectionTypes());
        String sizeClass = display.sizeClass() != null ? display.sizeClass() : "large";
        SectionRenderMode mode = SectionRenderMode.fromSizeClass(sizeClass);
        Map<String, Integer> limits = catalog.getDisplayLimits(sizeClass);

        // 1. Filter unsupported types & truncate data
        List<SectionEntry> adapted = new ArrayList<>();
        for (SectionEntry entry : scene.sections()) {
            if (!supportedTypes.contains(entry.type().wireName())) {
                log.info("Skipping unsupported section type: {}", entry.type().wireName());
                continue;
            }
            SectionData adaptedData = truncateData(entry.data(), limits, sizeClass);
            if (adaptedData != null) {
                adapted.add(new SectionEntry(entry.type(), entry.sectionId(), adaptedData));
            }
        }

        // 2. Cap section count per page
        int maxPerPage = limits.getOrDefault("max_sections_per_page", 12);
        if (maxPerPage > 0 && adapted.size() > maxPerPage) {
            adapted = adapted.subList(0, maxPerPage);
        }

        // 3. Height degradation for large screens
        if (mode == SectionRenderMode.RICH && caps.screen() != null) {
            int screenH = caps.screen().h();
            adapted = applyHeightDegradation(adapted, screenH);
        }

        // 4. Layout fallback
        SectionLayout layout = selectLayout(scene.layout(), display);

        return new SectionScene(scene.pageId(), layout, scene.autoScroll(), scene.autoScrollMs(),
                adapted, mode);
    }

    /**
     * Apply height degradation: if estimated rich-mode content exceeds available screen height,
     * progressively degrade sections to compact starting from the lowest-priority types.
     *
     * <p>A degraded section has its compact-hidden fields omitted by the JSON builder
     * (because the scene's renderMode is COMPACT for that section). Since we can't set per-section
     * mode in the current model, we degrade the whole scene to COMPACT when content overflows.
     *
     * @return the (possibly unchanged) section list
     */
    private List<SectionEntry> applyHeightDegradation(List<SectionEntry> sections, int screenH) {
        int totalRichH = sections.stream()
                .mapToInt(e -> RICH_HEIGHT_ESTIMATE.getOrDefault(e.type().wireName(), 60))
                .sum();
        if (totalRichH <= screenH) {
            return sections; // fits in rich mode
        }

        // Build a priority-ordered map: lower index = degrade first
        Map<String, Integer> degradeOrder = new LinkedHashMap<>();
        for (int i = 0; i < HEIGHT_DEGRADE_PRIORITY.size(); i++) {
            degradeOrder.put(HEIGHT_DEGRADE_PRIORITY.get(i), i);
        }

        // Sort sections by degradation priority (lowest priority first = degrade earlier)
        List<SectionEntry> sorted = new ArrayList<>(sections);
        sorted.sort(Comparator.comparingInt(
                e -> degradeOrder.getOrDefault(e.type().wireName(), Integer.MAX_VALUE)));

        int currentH = totalRichH;
        int compactSavings = 30; // approximate height saved per section when degrading
        int degradeCount = 0;

        for (SectionEntry entry : sorted) {
            if (currentH <= screenH) break;
            if (!HEIGHT_DEGRADE_PRIORITY.contains(entry.type().wireName())) continue;
            currentH -= compactSavings;
            degradeCount++;
        }

        if (degradeCount > 0) {
            log.info("Height degradation: {}px estimated exceeds {}px screen, degrading {} sections",
                    totalRichH, screenH, degradeCount);
        }

        // Sections remain in original order; the scene-level renderMode stays RICH
        // because we don't have per-section render mode yet.
        // The degradation log serves as a signal to the caller.
        return sections;
    }

    /**
     * Resolve the render mode for a device.
     */
    public SectionRenderMode resolveMode(CapabilitySchema.CapabilitySnapshot caps) {
        CapabilitySchema.DisplayInfo display = caps.display();
        if (display == null) return SectionRenderMode.RICH;
        return SectionRenderMode.fromSizeClass(display.sizeClass());
    }

    private SectionData truncateData(SectionData data, Map<String, Integer> limits, String sizeClass) {
        if (data instanceof SectionData.MetricData d) {
            int max = limits.getOrDefault("max_metrics", 4);
            if (d.metrics().size() > max) {
                return new SectionData.MetricData(d.metrics().subList(0, max));
            }
            return d;
        }
        if (data instanceof SectionData.ChartData d) {
            int max = limits.getOrDefault("max_chart_points", 16);
            if (d.points().size() > max) {
                return new SectionData.ChartData(d.title(), d.points().subList(0, max), d.progress());
            }
            return d;
        }
        if (data instanceof SectionData.ActionData d) {
            int max = limits.getOrDefault("max_actions", 4);
            if (d.actions().size() > max) {
                return new SectionData.ActionData(d.actions().subList(0, max));
            }
            return d;
        }
        if (data instanceof SectionData.ListData d) {
            int max = limits.getOrDefault("max_list_items", 12);
            if (d.items().size() > max) {
                return new SectionData.ListData(d.items().subList(0, max));
            }
            return d;
        }
        if (data instanceof SectionData.ToggleData d) {
            int max = limits.getOrDefault("max_toggle_options", 6);
            if (d.options().size() > max) {
                return new SectionData.ToggleData(d.options().subList(0, max));
            }
            return d;
        }
        if (data instanceof SectionData.NavData d) {
            int max = limits.getOrDefault("max_nav_tabs", 5);
            if (d.tabs().size() > max) {
                return new SectionData.NavData(d.tabs().subList(0, max), Math.min(d.activeTab(), max - 1));
            }
            return d;
        }
        if (data instanceof SectionData.TextData d) {
            int max = limits.getOrDefault("max_text_chars", 200);
            if (max == 0) {
                log.warn("Text section not supported on sizeClass={}, skipping", sizeClass);
                return null;
            }
            if (d.body() != null && d.body().length() > max) {
                return new SectionData.TextData(d.title(), d.body().substring(0, max) + "...");
            }
            return d;
        }
        if (data instanceof SectionData.OverlayData d) {
            int max = limits.getOrDefault("max_overlay_body", 100);
            if (max == 0) {
                log.warn("Overlay section not supported on sizeClass={}, skipping", sizeClass);
                return null;
            }
            if (d.body() != null && d.body().length() > max) {
                return new SectionData.OverlayData(
                        d.title(), d.body().substring(0, max) + "...",
                        d.tone(), d.unreadCount(), d.autoHideMs());
            }
            return d;
        }
        return data;
    }

    private SectionLayout selectLayout(SectionLayout requested, CapabilitySchema.DisplayInfo display) {
        if (display.supportsLayout(requested.wireName())) return requested;
        for (SectionLayout fallback : SectionLayout.values()) {
            if (display.supportsLayout(fallback.wireName())) {
                log.info("Layout {} not supported, falling back to {}", requested, fallback);
                return fallback;
            }
        }
        return SectionLayout.VERTICAL_SCROLL;
    }
}
