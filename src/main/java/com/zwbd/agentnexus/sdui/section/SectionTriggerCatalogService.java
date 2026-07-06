package com.zwbd.agentnexus.sdui.section;

import com.zwbd.agentnexus.sdui.event.EventDefinition;
import com.zwbd.agentnexus.sdui.event.EventRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.*;

/**
 * Builds a three-level {@link SectionTriggerCatalog} from a page definition.
 *
 * <pre>
 *   Page
 *   └── Section (interactive only)
 *       └── Element (button / toggle option / list item / nav tab / overlay)
 *           └── Events
 * </pre>
 *
 * Child elements are extracted from section instance {@code fields}, so the
 * frontend sees the actual buttons, toggles, etc. that exist on the current page.
 */
@Slf4j
@Service
public class SectionTriggerCatalogService {

    private final PageService pageService;
    private final SectionTypeCatalog sectionTypeCatalog;
    private final EventRegistry eventRegistry;

    /**
     * Describes how to extract child elements from a section type's fields.
     */
    private record ElementExtractor(
            String arrayField,      // e.g. "actions", "options"
            String idField,         // e.g. "id"
            String labelField,      // e.g. "label", "title"
            String elementType      // e.g. "button", "toggle_option"
    ) {}

    private static final Map<String, ElementExtractor> EXTRACTORS = Map.of(
            "action_section",  new ElementExtractor("actions",  "id", "label", "button"),
            "toggle_section",  new ElementExtractor("options",  "id", "label", "toggle_option"),
            "list_section",    new ElementExtractor("items",    "id", "title", "list_item"),
            "nav_section",     new ElementExtractor("tabs",     "id", "label", "nav_tab")
    );

    public SectionTriggerCatalogService(PageService pageService,
                                        SectionTypeCatalog sectionTypeCatalog,
                                        EventRegistry eventRegistry) {
        this.pageService = pageService;
        this.sectionTypeCatalog = sectionTypeCatalog;
        this.eventRegistry = eventRegistry;
    }

    /**
     * Build the catalog for a given page.
     *
     * @param deviceId the example device (for metadata only)
     * @param board    the board identifier (for metadata only)
     * @param online   whether the device is online
     * @param pageId   the persistent page ID
     */
    public SectionTriggerCatalog buildForPage(String deviceId, String board, boolean online, String pageId) {
        if (pageId == null || pageId.isBlank()) {
            return new SectionTriggerCatalog(null, deviceId, board, online, List.of());
        }

        SduiPageEntity page = pageService.findByPageId(pageId).orElse(null);
        if (page == null) {
            return new SectionTriggerCatalog(pageId, deviceId, board, online, List.of());
        }

        SectionPageDefinition pageDef = pageService.toPageDefinition(page);
        return build(pageDef, deviceId, board, online);
    }

    /**
     * Build the catalog from a raw page JSON array (for ad-hoc / unsaved pages).
     */
    public SectionTriggerCatalog buildFromPageJson(String deviceId, String board, boolean online,
                                                    String pageId, String pageJson) {
        if (pageJson == null || pageJson.isBlank()) {
            return new SectionTriggerCatalog(null, deviceId, board, online, List.of());
        }
        // Delegate to the existing JSON parser in CapabilityNodeCatalogService is not accessible,
        // so we inline a minimal parser here.
        try {
            com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper();
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> sections = mapper.readValue(pageJson,
                    new com.fasterxml.jackson.core.type.TypeReference<List<Map<String, Object>>>() {});
            LinkedHashMap<String, SectionPageDefinition.SectionDef> sectionDefs = new LinkedHashMap<>();
            for (Map<String, Object> sec : sections) {
                String sectionId = string(sec.get("sectionId"));
                String sectionType = string(sec.get("sectionType"));
                if (sectionId.isBlank() || sectionType.isBlank()) continue;
                @SuppressWarnings("unchecked")
                Map<String, Object> fields = (Map<String, Object>) sec.getOrDefault("fields", Map.of());
                sectionDefs.put(sectionId, new SectionPageDefinition.SectionDef(sectionId, sectionType, fields));
            }
            SectionPageDefinition pageDef = new SectionPageDefinition(
                    pageId != null ? pageId : "adhoc",
                    SectionLayout.VERTICAL_SCROLL, false, 0, sectionDefs);
            return build(pageDef, deviceId, board, online);
        } catch (Exception e) {
            log.warn("failed to parse pageJson for section trigger catalog: {}", e.getMessage());
            return new SectionTriggerCatalog(pageId, deviceId, board, online, List.of());
        }
    }

    // ── Core builder ──

    private SectionTriggerCatalog build(SectionPageDefinition pageDef, String deviceId,
                                         String board, boolean online) {
        // Pre-build shortId → namespacedEventId map from EventRegistry
        Map<String, String> namespacedMap = buildNamespacedEventMap();

        List<SectionTriggerCatalog.SectionEntry> sectionEntries = new ArrayList<>();

        for (var entry : pageDef.sections().entrySet()) {
            String sectionId = entry.getKey();
            SectionPageDefinition.SectionDef sectionDef = entry.getValue();
            String sectionType = sectionDef.sectionType();

            SectionTypeCatalog.SectionTypeDef typeDef = sectionTypeCatalog.get(sectionType).orElse(null);
            if (typeDef == null || !typeDef.interactive()) continue;

            ElementExtractor extractor = EXTRACTORS.get(sectionType);
            List<SectionTriggerCatalog.ElementEntry> elements;

            if (extractor != null) {
                // Has child array — extract individual elements
                elements = extractChildElements(sectionDef.fields(), extractor, namespacedMap, typeDef);
            } else {
                // overlay_section or future section with no child array
                elements = extractSelfAsElement(sectionDef, namespacedMap, typeDef);
            }

            sectionEntries.add(new SectionTriggerCatalog.SectionEntry(
                    sectionId,
                    sectionType,
                    typeDef.displayName(),
                    extractor != null && !elements.isEmpty(),
                    elements
            ));
        }

        return new SectionTriggerCatalog(
                pageDef.pageId(), deviceId, board, online, sectionEntries);
    }

    // ── Element extraction ──

    /**
     * Extract child elements from an array field inside the section's fields.
     * E.g. {@code actions: [{id: "btn_ok", label: "OK", tone: "primary"}, ...]}.
     */
    @SuppressWarnings("unchecked")
    private List<SectionTriggerCatalog.ElementEntry> extractChildElements(
            Map<String, Object> sectionFields,
            ElementExtractor extractor,
            Map<String, String> namespacedMap,
            SectionTypeCatalog.SectionTypeDef typeDef) {

        Object arrayRaw = sectionFields.get(extractor.arrayField());
        if (!(arrayRaw instanceof List<?> list) || list.isEmpty()) {
            return List.of();
        }

        List<SectionTriggerCatalog.ElementEntry> elements = new ArrayList<>();
        for (Object item : list) {
            if (!(item instanceof Map<?, ?> itemMap)) continue;
            Map<String, Object> fields = (Map<String, Object>) itemMap;

            String elementId = string(fields.get(extractor.idField()));
            if (elementId.isBlank()) continue;

            String elementLabel = string(fields.get(extractor.labelField()));
            // Fall back to elementId if no label is defined
            if (elementLabel.isBlank()) elementLabel = elementId;

            // Build elementMeta from all fields except id and label
            Map<String, Object> meta = new LinkedHashMap<>();
            for (var fieldEntry : fields.entrySet()) {
                String key = fieldEntry.getKey();
                if (key.equals(extractor.idField()) || key.equals(extractor.labelField())) continue;
                meta.put(key, fieldEntry.getValue());
            }

            List<SectionTriggerCatalog.EventEntry> events = buildEventEntries(typeDef, namespacedMap);

            elements.add(new SectionTriggerCatalog.ElementEntry(
                    elementId, elementLabel, extractor.elementType(), meta, events));
        }
        return elements;
    }

    /**
     * For sections without child arrays (e.g. overlay_section), treat the section
     * itself as a single interactive element.
     */
    private List<SectionTriggerCatalog.ElementEntry> extractSelfAsElement(
            SectionPageDefinition.SectionDef sectionDef,
            Map<String, String> namespacedMap,
            SectionTypeCatalog.SectionTypeDef typeDef) {

        String elementType = sectionDef.sectionType().endsWith("_section")
                ? sectionDef.sectionType().replace("_section", "")
                : sectionDef.sectionType();

        List<SectionTriggerCatalog.EventEntry> events = buildEventEntries(typeDef, namespacedMap);

        return List.of(new SectionTriggerCatalog.ElementEntry(
                sectionDef.sectionId(),
                typeDef.displayName(),
                elementType,
                Map.of(),
                events
        ));
    }

    // ── Event mapping ──

    /**
     * Build the list of events for a section type, resolving both short and namespaced IDs.
     */
    private List<SectionTriggerCatalog.EventEntry> buildEventEntries(
            SectionTypeCatalog.SectionTypeDef typeDef,
            Map<String, String> namespacedMap) {

        List<SectionTriggerCatalog.EventEntry> events = new ArrayList<>();
        for (SectionTypeCatalog.InteractionEvent ie : typeDef.interactionEvents()) {
            events.add(new SectionTriggerCatalog.EventEntry(
                    ie.eventId(),
                    namespacedMap.getOrDefault(ie.eventId(), ie.eventId()),
                    ie.description(),
                    ie.description()
            ));
        }
        return events;
    }

    /**
     * Build a map from short event ID (e.g. "action.click") to namespaced event ID
     * (e.g. "ui:action.click") by scanning registered section events.
     */
    private Map<String, String> buildNamespacedEventMap() {
        Map<String, String> map = new LinkedHashMap<>();
        for (EventDefinition def : eventRegistry.getAllSectionEvents()) {
            if (def.transport() != null && def.transport().eventName() != null) {
                String shortId = def.transport().eventName();
                map.putIfAbsent(shortId, def.eventId());
            }
        }
        return map;
    }

    // ── Helpers ──

    private static String string(Object value) {
        return value == null ? "" : String.valueOf(value);
    }
}
