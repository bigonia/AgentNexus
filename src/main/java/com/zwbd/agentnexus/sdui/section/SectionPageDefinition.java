package com.zwbd.agentnexus.sdui.section;

import java.util.*;

/**
 * Shared page definition that holds a collection of sections under a layout.
 *
 * Replaces the three previously-duplicated page models:
 * <ul>
 *   <li>{@code DebugSectionWorkspaceService.WorkspacePage}</li>
 *   <li>{@code SectionOrchestrationService.PageState}</li>
 *   <li>Inline page handling in {@code SduiUiTemplateService}</li>
 * </ul>
 *
 * Sections are stored as raw {@code Map<String, Object>} fields so both the
 * debug workspace and template service can use this type without coupling to
 * {@link SectionDataCodec}. The codec is used at conversion boundaries
 * ({@link #toScene} / {@link #fromScene}).
 */
public record SectionPageDefinition(
        String pageId,
        SectionLayout layout,
        boolean autoScroll,
        int autoScrollMs,
        LinkedHashMap<String, SectionDef> sections
) {

    public record SectionDef(
            String sectionId,
            String sectionType,
            Map<String, Object> fields
    ) {}

    // ── Convenience constructors ──

    /** Create with default layout and no auto-scroll. */
    public SectionPageDefinition(String pageId) {
        this(pageId, SectionLayout.VERTICAL_SCROLL, false, 0, new LinkedHashMap<>());
    }

    /** Create with a specified layout. */
    public SectionPageDefinition(String pageId, SectionLayout layout) {
        this(pageId, layout, false, 0, new LinkedHashMap<>());
    }

    // ── Conversions ──

    /**
     * Convert this page definition to a {@link SectionScene} for wire transmission.
     * Validates each section by building typed {@link SectionData}.
     *
     * @throws IllegalArgumentException if any section's fields are invalid for its type
     */
    public SectionScene toScene(SectionDataCodec codec) {
        List<SectionEntry> entries = new ArrayList<>();
        for (SectionDef section : sections.values()) {
            SectionData data = codec.buildSectionData(section.sectionType, section.fields, section.sectionId);
            if (data == null) {
                throw new IllegalArgumentException(
                        "invalid fields for sectionType " + section.sectionType + " (sectionId=" + section.sectionId + ")");
            }
            entries.add(new SectionEntry(section.sectionType, section.sectionId, data));
        }
        return new SectionScene(pageId, layout, autoScroll, autoScrollMs, entries);
    }

    /**
     * Build a {@code SectionPageDefinition} from a {@link SectionScene},
     * extracting fields back to raw maps via the codec.
     */
    public static SectionPageDefinition fromScene(SectionScene scene, SectionDataCodec codec) {
        LinkedHashMap<String, SectionDef> sectionDefs = new LinkedHashMap<>();
        for (SectionEntry entry : scene.sections()) {
            Map<String, Object> fields = new LinkedHashMap<>(codec.toFieldMap(entry.data()));
            sectionDefs.put(entry.sectionId(), new SectionDef(entry.sectionId(), entry.type(), fields));
        }
        return new SectionPageDefinition(
                scene.pageId(),
                scene.layout(),
                scene.autoScroll(),
                scene.autoScrollMs(),
                sectionDefs
        );
    }

    /**
     * Build a {@code SectionPageDefinition} from a raw definition map
     * (as stored in template definitions or received from API requests).
     *
     * Expected keys: pageId, layout, autoScroll, autoScrollMs, sections[]
     * where each section has: sectionId, sectionType, fields{}
     */
    @SuppressWarnings("unchecked")
    public static SectionPageDefinition fromMap(Map<String, Object> map) {
        String pageId = string(map.get("pageId"), "main");
        String layoutName = string(map.get("layout"), "vertical_scroll");
        SectionLayout layout = SectionLayout.fromWireName(layoutName);
        boolean autoScroll = Boolean.TRUE.equals(map.get("autoScroll"));
        int autoScrollMs = map.get("autoScrollMs") instanceof Number n ? n.intValue() : 0;

        LinkedHashMap<String, SectionDef> sectionDefs = new LinkedHashMap<>();
        Object sectionsRaw = map.get("sections");
        if (sectionsRaw instanceof List<?> list) {
            for (Object item : list) {
                if (item instanceof Map<?, ?> sectionMap) {
                    Map<String, Object> sm = (Map<String, Object>) sectionMap;
                    String sectionId = string(sm.get("sectionId"), "");
                    String sectionType = string(sm.getOrDefault("sectionType", sm.get("type")), "");
                    Map<String, Object> fields = sm.get("fields") instanceof Map<?, ?> f
                            ? new LinkedHashMap<>((Map<String, Object>) f) : new LinkedHashMap<>();
                    if (!sectionId.isBlank() && !sectionType.isBlank()) {
                        sectionDefs.put(sectionId, new SectionDef(sectionId, sectionType, fields));
                    }
                }
            }
        }

        return new SectionPageDefinition(pageId, layout, autoScroll, autoScrollMs, sectionDefs);
    }

    /**
     * Serialize to a frontend-friendly map for API responses.
     */
    public Map<String, Object> toMap() {
        List<Map<String, Object>> sectionList = new ArrayList<>();
        for (SectionDef section : sections.values()) {
            Map<String, Object> sm = new LinkedHashMap<>();
            sm.put("sectionId", section.sectionId);
            sm.put("sectionType", section.sectionType);
            sm.put("fields", new LinkedHashMap<>(section.fields));
            sectionList.add(sm);
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("pageId", pageId);
        result.put("layout", layout.wireName());
        result.put("autoScroll", autoScroll);
        result.put("autoScrollMs", autoScrollMs);
        result.put("sections", sectionList);
        return result;
    }

    // ── Query helpers ──

    /** Deduplicated set of all section types in this page. */
    public Set<String> sectionTypes() {
        Set<String> types = new LinkedHashSet<>();
        for (SectionDef section : sections.values()) {
            types.add(section.sectionType);
        }
        return types;
    }

    /**
     * Interaction event IDs that this page's sections can emit,
     * filtered to only those supported by the given board section types.
     *
     * @param catalog           the section type catalog for event lookup
     * @param boardSectionTypes section types supported by the target board
     * @return deduplicated set of interaction event IDs
     */
    public Set<String> interactionEvents(SectionTypeCatalog catalog, Set<String> boardSectionTypes) {
        Set<String> eventIds = new LinkedHashSet<>();
        for (String sectionType : sectionTypes()) {
            if (!boardSectionTypes.contains(sectionType)) continue;
            for (SectionTypeCatalog.InteractionEvent evt : catalog.getInteractionEvents(sectionType)) {
                eventIds.add(evt.eventId());
            }
        }
        return eventIds;
    }

    /**
     * Validate all sections in this page against the type catalog and codec.
     * <p>
     * Checks that each section type is known to the {@link SectionTypeCatalog}
     * and that its fields can be built into valid {@link SectionData}.
     * <p>
     * Device-specific capability checks (whether a given device supports the
     * section types) should be done separately at the call site via
     * {@link DeviceCapabilityProjection#sections(String)}.
     *
     * @throws IllegalArgumentException if any section fails validation
     */
    public void validate(SectionTypeCatalog catalog, SectionDataCodec codec) {
        for (SectionDef section : sections.values()) {
            if (!catalog.isValidType(section.sectionType)) {
                throw new IllegalArgumentException("unknown sectionType: " + section.sectionType);
            }
            if (codec.buildSectionData(section.sectionType, section.fields, section.sectionId) == null) {
                throw new IllegalArgumentException(
                        "invalid fields for sectionType " + section.sectionType + " (sectionId=" + section.sectionId + ")");
            }
        }
    }

    // ── Mutations (used by patch logic) ──

    /**
     * Add a section definition, returning a new page (this record is immutable).
     */
    public SectionPageDefinition withSection(SectionDef section) {
        LinkedHashMap<String, SectionDef> newSections = new LinkedHashMap<>(this.sections);
        newSections.put(section.sectionId, section);
        return new SectionPageDefinition(pageId, layout, autoScroll, autoScrollMs, newSections);
    }

    /**
     * Remove a section by ID, returning a new page.
     */
    public SectionPageDefinition withoutSection(String sectionId) {
        LinkedHashMap<String, SectionDef> newSections = new LinkedHashMap<>(this.sections);
        newSections.remove(sectionId);
        return new SectionPageDefinition(pageId, layout, autoScroll, autoScrollMs, newSections);
    }

    /**
     * Update a section's fields (merge), returning a new page.
     */
    public SectionPageDefinition withUpdatedSection(String sectionId, Map<String, Object> patchFields) {
        SectionDef existing = sections.get(sectionId);
        if (existing == null) return this;
        Map<String, Object> merged = new LinkedHashMap<>(existing.fields);
        merged.putAll(patchFields);
        LinkedHashMap<String, SectionDef> newSections = new LinkedHashMap<>(this.sections);
        newSections.put(sectionId, new SectionDef(sectionId, existing.sectionType, merged));
        return new SectionPageDefinition(pageId, layout, autoScroll, autoScrollMs, newSections);
    }

    // ── Internal ──

    private static String string(Object value, String defaultValue) {
        if (value instanceof String s && !s.isBlank()) return s;
        return defaultValue;
    }
}
