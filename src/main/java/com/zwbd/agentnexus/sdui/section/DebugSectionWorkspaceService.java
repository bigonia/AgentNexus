package com.zwbd.agentnexus.sdui.section;

import com.zwbd.agentnexus.sdui.protocol.catalog.DeviceCapabilityProjection;
import org.springframework.stereotype.Service;

import java.util.*;

/**
 * Per-device single-page manager for debug.
 *
 * Each device has exactly one active page. Push replaces it; patch mutates
 * its sections; clear removes it. There is no multi-page or workspace concept —
 * the debug tool controls one page at a time on one device.
 */
@Service
public class DebugSectionWorkspaceService {

    private static final String DEFAULT_LAYOUT = "vertical_scroll";
    private static final String SECTION_ID_PREFIX = "section_";
    private static final String WORKSPACE_PAGE_PREFIX = "ws_";

    private final DeviceCapabilityProjection capabilityProjection;
    private final SectionDataCodec sectionDataCodec;
    private final SectionOrchestrationService orchestrationService;
    private final SectionTypeCatalog sectionTypeCatalog;
    private final PageService pageService;

    public DebugSectionWorkspaceService(DeviceCapabilityProjection capabilityProjection,
                                        SectionDataCodec sectionDataCodec,
                                        SectionOrchestrationService orchestrationService,
                                        SectionTypeCatalog sectionTypeCatalog,
                                        PageService pageService) {
        this.capabilityProjection = capabilityProjection;
        this.sectionDataCodec = sectionDataCodec;
        this.orchestrationService = orchestrationService;
        this.sectionTypeCatalog = sectionTypeCatalog;
        this.pageService = pageService;
    }

    /** Deterministic workspace pageId for a device. */
    static String workspacePageId(String deviceId) {
        return WORKSPACE_PAGE_PREFIX + deviceId;
    }

    // ── Push (replaces current page) ──

    public Map<String, Object> push(String deviceId, Map<String, Object> body) {
        SectionPageDefinition page = pageDefinitionFromRequest(deviceId, body);
        String wsPageId = workspacePageId(deviceId);
        List<Map<String, Object>> sectionMaps = pageToSectionMaps(page);
        pageService.findByPageId(wsPageId).ifPresentOrElse(
                existing -> pageService.update(wsPageId, page.pageId(), page.layout().wireName(),
                        page.autoScroll(), page.autoScrollMs(), sectionMaps),
                () -> pageService.create(wsPageId, page.pageId(), page.layout().wireName(),
                        page.autoScroll(), page.autoScrollMs(), sectionMaps)
        );
        boolean sent = orchestrationService.sendScene(deviceId, page.toScene(sectionDataCodec));
        return Map.of(
                "deviceId", deviceId,
                "sent", sent,
                "pageId", wsPageId,
                "layout", page.layout().wireName(),
                "sectionsBuilt", page.sections().size(),
                "sectionsRequested", page.sections().size(),
                "sectionIds", new ArrayList<>(page.sections().keySet())
        );
    }

    // ── Patch (mutates current page) ──

    public Map<String, Object> patch(String deviceId, Map<String, Object> body) {
        SectionPageDefinition page = requirePage(deviceId);

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> rawPatches = body.get("patches") instanceof List<?> list
                ? (List<Map<String, Object>>) list : List.of();
        if (rawPatches.isEmpty()) {
            throw new IllegalArgumentException("patches is required");
        }

        // Make patches mutable (callers may pass Map.of() which is immutable)
        List<Map<String, Object>> mutablePatches = new ArrayList<>();
        for (Map<String, Object> p : rawPatches) {
            mutablePatches.add(new LinkedHashMap<>(p));
        }

        // Pre-generate sectionIds for "add" ops
        Set<String> knownIds = new LinkedHashSet<>(page.sections().keySet());
        for (Map<String, Object> rawPatch : mutablePatches) {
            if ("add".equals(stringValue(rawPatch.getOrDefault("op", "update")))
                    && stringValue(rawPatch.get("sectionId")).isBlank()) {
                rawPatch.put("sectionId", nextSectionId(knownIds));
            }
            knownIds.add(stringValue(rawPatch.get("sectionId")));
        }

        List<SectionPatch.PatchEntry> patchEntries = new ArrayList<>();
        long now = System.currentTimeMillis();
        for (Map<String, Object> rawPatch : mutablePatches) {
            patchEntries.add(applySinglePatch(deviceId, page, rawPatch, now));
        }

        // Build the mutated page
        SectionPageDefinition mutated = applyPatchesToPage(page, mutablePatches, now);
        // Persist updated sections
        String wsPageId = workspacePageId(deviceId);
        pageService.update(wsPageId, mutated.pageId(), mutated.layout().wireName(),
                mutated.autoScroll(), mutated.autoScrollMs(), pageToSectionMaps(mutated));

        boolean sent = orchestrationService.sendPatch(deviceId, new SectionPatch(mutated.pageId(), patchEntries));
        return Map.of(
                "deviceId", deviceId,
                "sent", sent,
                "pageId", mutated.pageId(),
                "patchesBuilt", patchEntries.size(),
                "patchesRequested", rawPatches.size(),
                "sectionIds", new ArrayList<>(mutated.sections().keySet())
        );
    }

    // ── State / Clear ──

    public Map<String, Object> getState(String deviceId) {
        SduiPageEntity entity = pageService.findByPageId(workspacePageId(deviceId)).orElse(null);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("deviceId", deviceId);
        result.put("page", entity != null ? pageService.toPageDefinition(entity).toMap() : null);
        return result;
    }

    public Map<String, Object> clear(String deviceId) {
        String wsPageId = workspacePageId(deviceId);
        pageService.findByPageId(wsPageId).ifPresent(p -> pageService.delete(wsPageId));
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("deviceId", deviceId);
        result.put("cleared", true);
        return result;
    }

    // ── Section type lookup (used by EventStreamService) ──

    public String findSectionType(String deviceId, String sectionId) {
        SectionPageDefinition page = loadPage(deviceId);
        if (page == null) return null;
        SectionPageDefinition.SectionDef section = page.sections().get(sectionId);
        return section != null ? section.sectionType() : null;
    }

    public String currentPageId(String deviceId) {
        return pageService.findByPageId(workspacePageId(deviceId))
                .map(SduiPageEntity::getPageId).orElse(null);
    }

    /**
     * Resolve a human-readable element label from a section's fields by nodeId.
     * Used by the SSE event stream to provide user-facing element names.
     */
    @SuppressWarnings("unchecked")
    public Optional<String> resolveElementLabel(String deviceId, String sectionId, String nodeId) {
        if (nodeId == null || nodeId.isBlank()) return Optional.empty();
        SectionPageDefinition page = loadPage(deviceId);
        if (page == null) return Optional.empty();
        SectionPageDefinition.SectionDef section = page.sections().get(sectionId);
        if (section == null) return Optional.empty();
        ChildExtractor extractor = CHILD_EXTRACTORS.get(section.sectionType());
        if (extractor == null) return Optional.empty();
        Object arrayRaw = section.fields().get(extractor.arrayField());
        if (!(arrayRaw instanceof List<?> list)) return Optional.empty();
        for (Object item : list) {
            if (!(item instanceof Map<?, ?> itemMap)) continue;
            Map<String, Object> fields = (Map<String, Object>) itemMap;
            String id = stringValue(fields.get(extractor.idField()));
            if (nodeId.equals(id)) {
                String label = stringValue(fields.get(extractor.labelField()));
                return label.isBlank() ? Optional.of(id) : Optional.of(label);
            }
        }
        return Optional.empty();
    }

    /**
     * Describes how to extract child interactive elements from a section's fields.
     */
    private record ChildExtractor(String arrayField, String idField, String labelField) {}

    private static final Map<String, ChildExtractor> CHILD_EXTRACTORS = Map.of(
            "action_section", new ChildExtractor("actions", "id", "label"),
            "toggle_section", new ChildExtractor("options", "id", "label"),
            "list_section",   new ChildExtractor("items",   "id", "title"),
            "nav_section",    new ChildExtractor("tabs",    "id", "label")
    );

    /**
     * Build a page event catalog for the current debug page of a device.
     * <p>
     * For sections that contain child interactive elements (buttons, toggles, list
     * items, nav tabs), each child is expanded into its own event entry carrying
     * {@code elementId} and {@code elementLabel} so the debug frontend can distinguish
     * which specific element fired an event.
     */
    public List<Map<String, Object>> buildPageEventCatalog(String deviceId) {
        SectionPageDefinition page = loadPage(deviceId);
        if (page == null) return List.of();

        String pageId = page.pageId();
        List<Map<String, Object>> events = new ArrayList<>();
        for (var entry : page.sections().entrySet()) {
            String sectionId = entry.getKey();
            String sectionType = entry.getValue().sectionType();
            SectionTypeCatalog.SectionTypeDef def = sectionTypeCatalog.get(sectionType).orElse(null);
            if (def == null || !def.interactive()) continue;

            ChildExtractor extractor = CHILD_EXTRACTORS.get(sectionType);
            if (extractor != null) {
                // Has child elements — expand each element with its own entry
                events.addAll(expandChildEvents(
                        sectionId, sectionType, def, extractor, entry.getValue().fields()));
            } else {
                // No child elements (e.g. overlay_section) — section-level entry
                for (SectionTypeCatalog.InteractionEvent evt : def.interactionEvents()) {
                    events.add(sectionEventEntry(sectionId, sectionType, def.displayName(), evt));
                }
            }
        }

        // Attach page context
        Map<String, Object> wrapper = new LinkedHashMap<>();
        wrapper.put("pageId", pageId);
        wrapper.put("events", events);
        return List.of(wrapper); // single page for debug (consistent with getState)
    }

    /** Build a section-level event entry (no child elements). */
    private static Map<String, Object> sectionEventEntry(String sectionId, String sectionType,
                                                          String sectionDisplayName,
                                                          SectionTypeCatalog.InteractionEvent evt) {
        Map<String, Object> info = new LinkedHashMap<>();
        info.put("sectionId", sectionId);
        info.put("sectionType", sectionType);
        info.put("sectionDisplayName", sectionDisplayName);
        info.put("eventId", evt.eventId());
        return info;
    }

    /** Expand child elements from section fields into individual event entries. */
    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> expandChildEvents(
            String sectionId, String sectionType, SectionTypeCatalog.SectionTypeDef def,
            ChildExtractor extractor, Map<String, Object> sectionFields) {

        Object arrayRaw = sectionFields.get(extractor.arrayField());
        if (!(arrayRaw instanceof List<?> list) || list.isEmpty()) {
            // Fallback: no child data, emit section-level entry
            List<Map<String, Object>> fallback = new ArrayList<>();
            for (SectionTypeCatalog.InteractionEvent evt : def.interactionEvents()) {
                fallback.add(sectionEventEntry(sectionId, sectionType, def.displayName(), evt));
            }
            return fallback;
        }

        List<Map<String, Object>> events = new ArrayList<>();
        for (Object item : list) {
            if (!(item instanceof Map<?, ?> itemMap)) continue;
            Map<String, Object> fields = (Map<String, Object>) itemMap;

            String elementId = string(fields.get(extractor.idField()));
            if (elementId.isBlank()) continue;

            String elementLabel = string(fields.get(extractor.labelField()));
            if (elementLabel.isBlank()) elementLabel = elementId;

            for (SectionTypeCatalog.InteractionEvent evt : def.interactionEvents()) {
                Map<String, Object> info = new LinkedHashMap<>();
                info.put("sectionId", sectionId);
                info.put("sectionType", sectionType);
                info.put("sectionDisplayName", def.displayName());
                info.put("elementId", elementId);
                info.put("elementLabel", elementLabel);
                info.put("eventId", evt.eventId());
                events.add(info);
            }
        }
        return events;
    }

    /**
     * Build a flat list of expected events with page context, suitable for SSE push.
     */
    public Map<String, Object> buildPageEventCatalogMap(String deviceId) {
        List<Map<String, Object>> catalogs = buildPageEventCatalog(deviceId);
        if (catalogs.isEmpty()) return Map.of("pageId", "", "events", List.of());
        return catalogs.get(0);
    }

    // ── Internal: page construction ──

    private SectionPageDefinition pageDefinitionFromRequest(String deviceId, Map<String, Object> body) {
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> rawSections = body.get("sections") instanceof List<?> list
                ? (List<Map<String, Object>>) list : List.of();
        if (rawSections.isEmpty()) {
            throw new IllegalArgumentException("sections is required");
        }

        String pageId = stringValue(body.get("pageId"));
        if (pageId.isBlank()) {
            pageId = workspacePageId(deviceId);
        }
        String layoutName = stringValue(body.getOrDefault("layout", DEFAULT_LAYOUT));
        boolean autoScroll = Boolean.TRUE.equals(body.get("autoScroll"));
        int autoScrollMs = body.get("autoScrollMs") instanceof Number n ? n.intValue() : 0;

        LinkedHashMap<String, SectionPageDefinition.SectionDef> sectionDefs = new LinkedHashMap<>();
        Set<String> seenIds = new LinkedHashSet<>();
        for (Map<String, Object> rawSection : rawSections) {
            SectionPageDefinition.SectionDef section = buildSectionDef(deviceId, rawSection, seenIds);
            sectionDefs.put(section.sectionId(), section);
            seenIds.add(section.sectionId());
        }

        SectionPageDefinition page = new SectionPageDefinition(
                pageId, SectionLayout.fromWireName(layoutName), autoScroll, autoScrollMs, sectionDefs);
        page.validate(sectionTypeCatalog, sectionDataCodec);
        return page;
    }

    private SectionPageDefinition.SectionDef buildSectionDef(String deviceId, Map<String, Object> rawSection,
                                                              Set<String> existingIds) {
        String sectionId = stringValue(rawSection.get("sectionId"));
        if (sectionId.isBlank()) {
            sectionId = nextSectionId(existingIds);
        }
        String sectionType = stringValue(rawSection.getOrDefault("sectionType", rawSection.get("type")));
        if (sectionType.isBlank()) {
            throw new IllegalArgumentException("sectionType is required");
        }
        validateSectionType(deviceId, sectionType);

        @SuppressWarnings("unchecked")
        Map<String, Object> rawFields = rawSection.get("fields") instanceof Map<?, ?> map
                ? (Map<String, Object>) map : null;
        if (rawFields == null) {
            throw new IllegalArgumentException("fields is required for section " + sectionId);
        }

        Map<String, Object> normalizedFields = sectionDataCodec.normalizeFields(sectionType, new LinkedHashMap<>(rawFields));
        if (sectionDataCodec.buildSectionData(sectionType, normalizedFields, sectionId) == null) {
            throw new IllegalArgumentException("invalid fields for sectionType: " + sectionType);
        }

        return new SectionPageDefinition.SectionDef(sectionId, sectionType, normalizedFields);
    }

    // ── Internal: patch application ──

    private SectionPageDefinition requirePage(String deviceId) {
        SectionPageDefinition page = loadPage(deviceId);
        if (page == null) {
            throw new IllegalArgumentException("no page found for device: " + deviceId + ". Push a page first.");
        }
        return page;
    }

    private SectionPageDefinition loadPage(String deviceId) {
        return pageService.findByPageId(workspacePageId(deviceId))
                .map(pageService::toPageDefinition)
                .orElse(null);
    }

    private List<Map<String, Object>> pageToSectionMaps(SectionPageDefinition page) {
        List<Map<String, Object>> sections = new ArrayList<>();
        for (var entry : page.sections().entrySet()) {
            sections.add(Map.of(
                    "sectionId", entry.getValue().sectionId(),
                    "sectionType", entry.getValue().sectionType(),
                    "fields", entry.getValue().fields()
            ));
        }
        return sections;
    }

    private SectionPatch.PatchEntry applySinglePatch(String deviceId, SectionPageDefinition page,
                                                      Map<String, Object> rawPatch, long now) {
        String op = stringValue(rawPatch.getOrDefault("op", "update"));
        String sectionId = stringValue(rawPatch.get("sectionId"));
        if (!"add".equals(op) && sectionId.isBlank()) {
            throw new IllegalArgumentException("sectionId is required");
        }

        return switch (op) {
            case "add" -> applyAddPatch(deviceId, page, rawPatch, sectionId, now);
            case "update" -> applyUpdatePatch(deviceId, page, rawPatch, sectionId, now);
            case "remove" -> applyRemovePatch(page, sectionId);
            default -> throw new IllegalArgumentException("unsupported patch op: " + op);
        };
    }

    private SectionPageDefinition applyPatchesToPage(SectionPageDefinition page,
                                                      List<Map<String, Object>> rawPatches, long now) {
        LinkedHashMap<String, SectionPageDefinition.SectionDef> sections = new LinkedHashMap<>(page.sections());
        for (Map<String, Object> rawPatch : rawPatches) {
            String op = stringValue(rawPatch.getOrDefault("op", "update"));
            String sectionId = stringValue(rawPatch.get("sectionId"));
            switch (op) {
                case "remove" -> sections.remove(sectionId);
                case "add" -> {
                    String sectionType = stringValue(rawPatch.getOrDefault("sectionType", rawPatch.get("type")));
                    if (sectionId.isBlank()) {
                        sectionId = nextSectionId(sections.keySet());
                    }
                    @SuppressWarnings("unchecked")
                    Map<String, Object> rawFields = rawPatch.get("fields") instanceof Map<?, ?> map
                            ? (Map<String, Object>) map : Map.of();
                    Map<String, Object> normalizedFields = sectionDataCodec.normalizeFields(sectionType, new LinkedHashMap<>(rawFields));
                    sections.put(sectionId, new SectionPageDefinition.SectionDef(sectionId, sectionType, normalizedFields));
                }
                default -> { // update
                    SectionPageDefinition.SectionDef existing = sections.get(sectionId);
                    if (existing == null) continue;
                    @SuppressWarnings("unchecked")
                    Map<String, Object> patchFields = rawPatch.get("fields") instanceof Map<?, ?> map
                            ? (Map<String, Object>) map : Map.of();
                    Map<String, Object> merged = new LinkedHashMap<>(existing.fields());
                    merged.putAll(patchFields);
                    sections.put(sectionId, new SectionPageDefinition.SectionDef(sectionId, existing.sectionType(), merged));
                }
            }
        }
        return new SectionPageDefinition(page.pageId(), page.layout(), page.autoScroll(), page.autoScrollMs(), sections);
    }

    private SectionPatch.PatchEntry applyAddPatch(String deviceId, SectionPageDefinition page,
                                                   Map<String, Object> rawPatch, String sectionId, long now) {
        Set<String> existingIds = page.sections().keySet();
        SectionPageDefinition.SectionDef section = buildSectionDef(deviceId, rawPatch, existingIds);
        sectionId = section.sectionId();
        if (page.sections().containsKey(sectionId)) {
            throw new IllegalArgumentException("section already exists: " + sectionId);
        }
        return new SectionPatch.PatchEntry(
                sectionId, "add", section.sectionType(),
                sectionDataCodec.buildSectionData(section.sectionType(), section.fields(), section.sectionId())
        );
    }

    private SectionPatch.PatchEntry applyUpdatePatch(String deviceId, SectionPageDefinition page,
                                                      Map<String, Object> rawPatch, String sectionId, long now) {
        SectionPageDefinition.SectionDef existing = page.sections().get(sectionId);
        if (existing == null) {
            throw new IllegalArgumentException("section not found: " + sectionId);
        }

        @SuppressWarnings("unchecked")
        Map<String, Object> patchFields = rawPatch.get("fields") instanceof Map<?, ?> map
                ? (Map<String, Object>) map : null;
        if (patchFields == null) {
            throw new IllegalArgumentException("fields is required for section " + sectionId);
        }

        String sectionType = stringValue(rawPatch.get("sectionType"));
        if (!sectionType.isBlank() && !sectionType.equals(existing.sectionType())) {
            throw new IllegalArgumentException("sectionType cannot change for existing section " + sectionId);
        }

        validateSectionType(deviceId, existing.sectionType());
        Map<String, Object> merged = new LinkedHashMap<>(existing.fields());
        merged.putAll(patchFields);
        SectionData data = sectionDataCodec.buildSectionData(existing.sectionType(), merged, sectionId);
        if (data == null) {
            throw new IllegalArgumentException("invalid fields for sectionType: " + existing.sectionType());
        }

        return new SectionPatch.PatchEntry(sectionId, "update", null, data);
    }

    private SectionPatch.PatchEntry applyRemovePatch(SectionPageDefinition page, String sectionId) {
        if (!page.sections().containsKey(sectionId)) {
            throw new IllegalArgumentException("section not found: " + sectionId);
        }
        return new SectionPatch.PatchEntry(sectionId, "remove", null, null);
    }

    // ── Internal: validation ──

    private void validateSectionType(String deviceId, String sectionType) {
        if (sectionType.isBlank()) {
            throw new IllegalArgumentException("sectionType is required");
        }
        boolean supported = capabilityProjection.sections(deviceId).stream()
                .anyMatch(section -> section.type().equals(sectionType));
        if (!supported) {
            throw new IllegalArgumentException("unsupported sectionType: " + sectionType);
        }
        if (!sectionTypeCatalog.isValidType(sectionType)) {
            throw new IllegalArgumentException("unknown sectionType: " + sectionType);
        }
    }

    // ── Internal: helpers ──

    private String stringValue(Object value) {
        return value instanceof String s ? s : value != null ? String.valueOf(value) : "";
    }

    private static String string(Object value) {
        return value == null ? "" : String.valueOf(value);
    }

    private String nextSectionId(Set<String> existingIds) {
        String sectionId;
        do {
            sectionId = SECTION_ID_PREFIX + UUID.randomUUID().toString().replace("-", "").substring(0, 8);
        } while (existingIds != null && existingIds.contains(sectionId));
        return sectionId;
    }
}
