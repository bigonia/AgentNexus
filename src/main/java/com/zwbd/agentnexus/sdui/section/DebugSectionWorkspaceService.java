package com.zwbd.agentnexus.sdui.section;

import com.zwbd.agentnexus.sdui.protocol.catalog.DeviceCapabilityProjection;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

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
    private static final String PAGE_ID_PREFIX = "debug_page_";
    private static final String SECTION_ID_PREFIX = "section_";

    private final DeviceCapabilityProjection capabilityProjection;
    private final SectionDataCodec sectionDataCodec;
    private final SectionOrchestrationService orchestrationService;
    private final SectionTypeCatalog sectionTypeCatalog;

    /** deviceId → current page */
    private final Map<String, SectionPageDefinition> pagesByDevice = new ConcurrentHashMap<>();

    public DebugSectionWorkspaceService(DeviceCapabilityProjection capabilityProjection,
                                        SectionDataCodec sectionDataCodec,
                                        SectionOrchestrationService orchestrationService,
                                        SectionTypeCatalog sectionTypeCatalog) {
        this.capabilityProjection = capabilityProjection;
        this.sectionDataCodec = sectionDataCodec;
        this.orchestrationService = orchestrationService;
        this.sectionTypeCatalog = sectionTypeCatalog;
    }

    // ── Push (replaces current page) ──

    public Map<String, Object> push(String deviceId, Map<String, Object> body) {
        SectionPageDefinition page = pageDefinitionFromRequest(deviceId, body);
        pagesByDevice.put(deviceId, page);

        boolean sent = orchestrationService.sendScene(deviceId, page.toScene(sectionDataCodec));
        return Map.of(
                "deviceId", deviceId,
                "sent", sent,
                "pageId", page.pageId(),
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
        pagesByDevice.put(deviceId, mutated);

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
        SectionPageDefinition page = pagesByDevice.get(deviceId);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("deviceId", deviceId);
        result.put("page", page != null ? page.toMap() : null);
        return result;
    }

    public Map<String, Object> clear(String deviceId) {
        pagesByDevice.remove(deviceId);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("deviceId", deviceId);
        result.put("cleared", true);
        return result;
    }

    // ── Section type lookup (used by EventStreamService) ──

    public String findSectionType(String deviceId, String sectionId) {
        SectionPageDefinition page = pagesByDevice.get(deviceId);
        if (page == null) return null;
        SectionPageDefinition.SectionDef section = page.sections().get(sectionId);
        return section != null ? section.sectionType() : null;
    }

    public String currentPageId(String deviceId) {
        SectionPageDefinition page = pagesByDevice.get(deviceId);
        return page != null ? page.pageId() : null;
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
            pageId = nextPageId();
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
        SectionPageDefinition page = pagesByDevice.get(deviceId);
        if (page == null) {
            throw new IllegalArgumentException("no page found for device: " + deviceId + ". Push a page first.");
        }
        return page;
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

    private String nextPageId() {
        return PAGE_ID_PREFIX + UUID.randomUUID().toString().replace("-", "").substring(0, 8);
    }

    private String nextSectionId(Set<String> existingIds) {
        String sectionId;
        do {
            sectionId = SECTION_ID_PREFIX + UUID.randomUUID().toString().replace("-", "").substring(0, 8);
        } while (existingIds != null && existingIds.contains(sectionId));
        return sectionId;
    }
}
