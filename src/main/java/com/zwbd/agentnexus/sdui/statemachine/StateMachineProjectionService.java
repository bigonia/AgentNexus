package com.zwbd.agentnexus.sdui.statemachine;

import com.zwbd.agentnexus.sdui.section.SectionData;
import com.zwbd.agentnexus.sdui.section.SectionDataCodec;
import com.zwbd.agentnexus.sdui.section.SectionEntry;
import com.zwbd.agentnexus.sdui.section.SectionLayout;
import com.zwbd.agentnexus.sdui.section.SectionOrchestrationService;
import com.zwbd.agentnexus.sdui.section.SectionPatch;
import com.zwbd.agentnexus.sdui.section.SectionScene;
import com.zwbd.agentnexus.sdui.section.SectionType;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Projects page state changes to devices.
 *
 * In the unified model, each page carries its own device list.
 * Projection iterates pages, diffs their sections, and pushes
 * scenes or patches to the page's bound devices.
 */
@Service
@RequiredArgsConstructor
public class StateMachineProjectionService {

    private final SectionOrchestrationService sectionOrchestrationService;
    private final SectionDataCodec sectionDataCodec;

    /**
     * Push all pages as full scenes to their bound devices.
     */
    public List<Map<String, Object>> projectScene(List<Map<String, Object>> pages) {
        List<Map<String, Object>> results = new ArrayList<>();
        for (Map<String, Object> page : pages) {
            List<String> devices = devicesFromPage(page);
            if (devices.isEmpty()) {
                results.add(skipped(pageId(page), "no bound devices"));
                continue;
            }
            SectionScene scene = toScene(page);
            for (String deviceId : devices) {
                boolean sent = sectionOrchestrationService.sendScene(deviceId, scene);
                results.add(Map.of(
                        "type", "scene",
                        "pageId", pageId(page),
                        "deviceId", deviceId,
                        "sent", sent,
                        "sections", scene.sections().size()
                ));
            }
        }
        return results;
    }

    /**
     * Diff previous and current pages, produce patches or scenes per page.
     */
    public List<Map<String, Object>> projectTransition(List<Map<String, Object>> previousPages,
                                                       List<Map<String, Object>> currentPages) {
        List<Map<String, Object>> results = new ArrayList<>();
        Map<String, Map<String, Object>> currentByPageId = pagesById(currentPages);

        for (Map<String, Object> prevPage : previousPages) {
            String pid = pageId(prevPage);
            Map<String, Object> currPage = currentByPageId.remove(pid);
            if (currPage == null) {
                // Page was removed — not supported yet, skip
                continue;
            }
            results.addAll(projectPageTransition(prevPage, currPage));
        }
        // New pages that didn't exist before
        for (Map<String, Object> newPage : currentByPageId.values()) {
            List<String> devices = devicesFromPage(newPage);
            if (devices.isEmpty()) continue;
            SectionScene scene = toScene(newPage);
            for (String deviceId : devices) {
                results.add(Map.of(
                        "type", "scene",
                        "pageId", pageId(newPage),
                        "deviceId", deviceId,
                        "sent", sectionOrchestrationService.sendScene(deviceId, scene),
                        "sections", scene.sections().size()
                ));
            }
        }
        return results;
    }

    private List<Map<String, Object>> projectPageTransition(Map<String, Object> prevPage,
                                                            Map<String, Object> currPage) {
        List<String> devices = devicesFromPage(currPage);
        if (devices.isEmpty()) {
            return List.of(skipped(pageId(currPage), "no bound devices"));
        }

        // Check if full scene is needed (page-level properties changed)
        if (requiresScene(prevPage, currPage)) {
            SectionScene scene = toScene(currPage);
            List<Map<String, Object>> results = new ArrayList<>();
            for (String deviceId : devices) {
                boolean sent = sectionOrchestrationService.sendScene(deviceId, scene);
                results.add(Map.of(
                        "type", "scene",
                        "pageId", pageId(currPage),
                        "deviceId", deviceId,
                        "sent", sent,
                        "sections", scene.sections().size()
                ));
            }
            return results;
        }

        // Compute section-level diff
        List<SectionPatch.PatchEntry> patches = buildPatchEntries(prevPage, currPage);
        if (patches.isEmpty()) {
            return List.of(skipped(pageId(currPage), "no section changes"));
        }

        int totalSections = sectionList(currPage).size();
        // Adaptive: if too many patches, send full scene instead
        if (patches.size() > Math.max(1, totalSections / 2)) {
            SectionScene scene = toScene(currPage);
            List<Map<String, Object>> results = new ArrayList<>();
            for (String deviceId : devices) {
                boolean sent = sectionOrchestrationService.sendScene(deviceId, scene);
                results.add(Map.of(
                        "type", "scene",
                        "pageId", pageId(currPage),
                        "deviceId", deviceId,
                        "sent", sent,
                        "sections", scene.sections().size()
                ));
            }
            return results;
        }

        SectionPatch patch = new SectionPatch(pageId(currPage), patches);
        List<Map<String, Object>> results = new ArrayList<>();
        for (String deviceId : devices) {
            boolean sent = sectionOrchestrationService.sendPatch(deviceId, patch);
            results.add(Map.of(
                    "type", "patch",
                    "pageId", pageId(currPage),
                    "deviceId", deviceId,
                    "sent", sent,
                    "patches", patches.size()
            ));
        }
        return results;
    }

    /**
     * Expand a raw state definition through the section codec.
     */
    public List<Map<String, Object>> expandStateSections(Map<String, Object> state) {
        List<Map<String, Object>> result = new ArrayList<>();
        for (Map<String, Object> section : sectionList(state)) {
            String sectionId = string(section.get("sectionId"));
            String sectionType = string(section.get("sectionType"));
            Map<String, Object> expanded = new LinkedHashMap<>();
            expanded.put("sectionId", sectionId);
            expanded.put("sectionType", sectionType);

            if (sectionId.isBlank() || sectionType.isBlank()) {
                expanded.put("valid", false);
                expanded.put("error", "sectionId and sectionType are required");
                result.add(expanded);
                continue;
            }
            SectionType type = SectionType.fromWireName(sectionType);
            if (type == null) {
                expanded.put("valid", false);
                expanded.put("error", "unknown section type: " + sectionType);
                result.add(expanded);
                continue;
            }
            Map<String, Object> fields = section.get("fields") instanceof Map<?, ?> map
                    ? normalize(map) : Map.of();
            SectionData data = sectionDataCodec.buildSectionData(sectionType, fields, sectionId);
            if (data == null) {
                expanded.put("valid", false);
                expanded.put("error", "section codec rejected fields for type " + sectionType);
                expanded.put("fields", fields);
                result.add(expanded);
                continue;
            }
            expanded.put("valid", true);
            expanded.put("fields", sectionDataCodec.toFieldMap(data));
            result.add(expanded);
        }
        return result;
    }

    // ── Helpers ──

    private SectionScene toScene(Map<String, Object> page) {
        List<SectionEntry> entries = new ArrayList<>();
        for (Map<String, Object> section : sectionList(page)) {
            SectionEntry entry = toEntry(section);
            if (entry != null) entries.add(entry);
        }
        return new SectionScene(
                pageId(page),
                SectionLayout.fromWireName(string(page.getOrDefault("layout", "vertical_scroll"))),
                Boolean.TRUE.equals(page.get("autoScroll")),
                intValue(page.get("autoScrollMs"), 0),
                entries
        );
    }

    private List<SectionPatch.PatchEntry> buildPatchEntries(Map<String, Object> prevPage,
                                                            Map<String, Object> currPage) {
        Map<String, Map<String, Object>> prev = sectionsById(prevPage);
        Map<String, Map<String, Object>> curr = sectionsById(currPage);
        List<SectionPatch.PatchEntry> patches = new ArrayList<>();

        for (String id : prev.keySet()) {
            if (!curr.containsKey(id)) {
                patches.add(new SectionPatch.PatchEntry(id, "remove", null, null));
            }
        }
        for (var entry : curr.entrySet()) {
            String sectionId = entry.getKey();
            Map<String, Object> section = entry.getValue();
            Map<String, Object> old = prev.get(sectionId);
            if (old == null) {
                patches.add(toPatch(section, "add"));
            } else if (!Objects.equals(old, section)) {
                patches.add(toPatch(section, "update"));
            }
        }
        return patches.stream().filter(Objects::nonNull).toList();
    }

    private SectionPatch.PatchEntry toPatch(Map<String, Object> section, String op) {
        SectionEntry entry = toEntry(section);
        return entry != null ? new SectionPatch.PatchEntry(entry.sectionId(), op, entry.type().wireName(), entry.data()) : null;
    }

    private SectionEntry toEntry(Map<String, Object> section) {
        String sectionId = string(section.get("sectionId"));
        String sectionType = string(section.get("sectionType"));
        if (sectionId.isBlank() || sectionType.isBlank()) return null;
        SectionType type = SectionType.fromWireName(sectionType);
        if (type == null) return null;
        Map<String, Object> fields = section.get("fields") instanceof Map<?, ?> map
                ? normalize(map) : Map.of();
        SectionData data = sectionDataCodec.buildSectionData(sectionType, fields, sectionId);
        return data != null ? new SectionEntry(type, sectionId, data) : null;
    }

    private boolean requiresScene(Map<String, Object> prevPage, Map<String, Object> currPage) {
        return !Objects.equals(pageId(prevPage), pageId(currPage))
                || !Objects.equals(prevPage.get("layout"), currPage.get("layout"))
                || !Objects.equals(prevPage.get("autoScroll"), currPage.get("autoScroll"))
                || !Objects.equals(prevPage.get("autoScrollMs"), currPage.get("autoScrollMs"));
    }

    @SuppressWarnings("unchecked")
    public List<String> devicesFromPage(Map<String, Object> page) {
        Set<String> devices = new LinkedHashSet<>();
        Object raw = page.get("devices");
        if (raw instanceof List<?> list) {
            for (Object item : list) {
                String deviceId = string(item);
                if (!deviceId.isBlank()) devices.add(deviceId);
            }
        } else if (raw instanceof String s && !s.isBlank()) {
            devices.add(s);
        }
        return new ArrayList<>(devices);
    }

    /**
     * Collect all unique devices across all pages.
     */
    List<String> allDevices(List<Map<String, Object>> pages) {
        Set<String> devices = new LinkedHashSet<>();
        for (Map<String, Object> page : pages) {
            devices.addAll(devicesFromPage(page));
        }
        return new ArrayList<>(devices);
    }

    private Map<String, Map<String, Object>> sectionsById(Map<String, Object> page) {
        Map<String, Map<String, Object>> result = new LinkedHashMap<>();
        for (Map<String, Object> section : sectionList(page)) {
            String sectionId = string(section.get("sectionId"));
            if (!sectionId.isBlank()) result.put(sectionId, section);
        }
        return result;
    }

    private Map<String, Map<String, Object>> pagesById(List<Map<String, Object>> pages) {
        Map<String, Map<String, Object>> result = new LinkedHashMap<>();
        for (Map<String, Object> page : pages) {
            result.put(pageId(page), page);
        }
        return result;
    }

    @SuppressWarnings("unchecked")
    List<Map<String, Object>> sectionList(Map<String, Object> page) {
        Object raw = page.get("sections");
        if (!(raw instanceof List<?> list)) return new ArrayList<>();
        List<Map<String, Object>> sections = new ArrayList<>();
        for (Object item : list) {
            if (item instanceof Map<?, ?> map) sections.add(normalize(map));
        }
        return sections;
    }

    private Map<String, Object> skipped(String pageId, String reason) {
        return Map.of("type", "none", "pageId", pageId, "sent", false, "reason", reason);
    }

    String pageId(Map<String, Object> page) {
        String pid = string(page.get("pageId"));
        return pid.isBlank() ? "state_machine_page" : pid;
    }

    private Map<String, Object> normalize(Map<?, ?> raw) {
        Map<String, Object> result = new LinkedHashMap<>();
        raw.forEach((key, value) -> result.put(String.valueOf(key), value));
        return result;
    }

    private String string(Object value) {
        return value == null ? "" : String.valueOf(value);
    }

    private int intValue(Object value, int fallback) {
        if (value instanceof Number n) return n.intValue();
        try {
            return value != null ? Integer.parseInt(String.valueOf(value)) : fallback;
        } catch (NumberFormatException e) {
            return fallback;
        }
    }
}
