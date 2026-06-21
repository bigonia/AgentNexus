package com.zwbd.agentnexus.sdui.section;

import com.zwbd.agentnexus.sdui.protocol.catalog.DeviceCapabilityProjection;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class DebugSectionWorkspaceService {

    private static final String DEFAULT_LAYOUT = "vertical_scroll";
    private static final String PAGE_ID_PREFIX = "debug_page_";
    private static final String SECTION_ID_PREFIX = "section_";
    private static final String DEBUG_NAMESPACE = "debug";

    private final DeviceCapabilityProjection capabilityProjection;
    private final SectionDataCodec sectionDataCodec;
    private final SectionOrchestrationService orchestrationService;

    private final Map<String, DebugWorkspace> workspaces = new ConcurrentHashMap<>();

    public DebugSectionWorkspaceService(DeviceCapabilityProjection capabilityProjection,
                                        SectionDataCodec sectionDataCodec,
                                        SectionOrchestrationService orchestrationService) {
        this.capabilityProjection = capabilityProjection;
        this.sectionDataCodec = sectionDataCodec;
        this.orchestrationService = orchestrationService;
    }

    public Map<String, Object> push(String deviceId, Map<String, Object> body) {
        WorkspacePage page = workspacePageFromRequest(deviceId, body);

        DebugWorkspace workspace = workspace(DEBUG_NAMESPACE, deviceId);
        replacePage(workspace, page);

        boolean sent = orchestrationService.sendScene(deviceId, toScene(page));
        return Map.of(
                "deviceId", deviceId,
                "sent", sent,
                "pageId", page.pageId,
                "layout", page.layout,
                "sectionsBuilt", page.sections.size(),
                "sectionsRequested", page.sections.size(),
                "sectionIds", new ArrayList<>(page.sections.keySet())
        );
    }

    public Map<String, Object> patch(String deviceId, Map<String, Object> body) {
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> rawPatches = body.get("patches") instanceof List<?> list
                ? (List<Map<String, Object>>) list : List.of();
        if (rawPatches.isEmpty()) {
            throw new IllegalArgumentException("patches is required");
        }

        DebugWorkspace workspace = workspace(DEBUG_NAMESPACE, deviceId);
        String pageId = resolvePatchPageId(workspace, body);
        WorkspacePage page = workspace.pages.get(pageId);
        if (page == null) {
            throw new IllegalArgumentException("page not found in debug workspace: " + pageId);
        }

        List<SectionPatch.PatchEntry> patchEntries = applyPatchEntries(deviceId, page, rawPatches);
        workspace.activePageId = pageId;

        boolean sent = orchestrationService.sendPatch(deviceId, new SectionPatch(pageId, patchEntries));
        return Map.of(
                "deviceId", deviceId,
                "sent", sent,
                "pageId", pageId,
                "patchesBuilt", patchEntries.size(),
                "patchesRequested", rawPatches.size(),
                "sectionIds", new ArrayList<>(page.sections.keySet())
        );
    }

    public Map<String, Object> getState(String deviceId) {
        return getState(DEBUG_NAMESPACE, deviceId);
    }

    /**
     * Export the current debug workspace as a state-machine-compatible page state.
     * This bridges the debug section editor to the state machine: users build a
     * page in the debug UI, then export it as a state definition that can be
     * copied into a state machine definition's "states" array.
     *
     * Returns the active page in state machine page format:
     * {@code { pageId, layout, autoScroll, autoScrollMs, sections: [...] }}.
     */
    public Map<String, Object> getStateAsStateMachinePage(String deviceId) {
        DebugWorkspace workspace = workspaces.get(workspaceKey(DEBUG_NAMESPACE, deviceId));
        if (workspace == null) {
            return emptyStateMachinePage(deviceId);
        }

        WorkspacePage activePage = workspace.pages.get(workspace.activePageId);
        if (activePage == null && !workspace.pages.isEmpty()) {
            activePage = workspace.pages.values().iterator().next();
        }
        if (activePage == null) {
            return emptyStateMachinePage(deviceId);
        }

        List<Map<String, Object>> sections = new ArrayList<>();
        for (WorkspaceSection section : activePage.sections.values()) {
            Map<String, Object> sectionMap = new LinkedHashMap<>();
            sectionMap.put("sectionId", section.sectionId);
            sectionMap.put("sectionType", section.sectionType);
            sectionMap.put("fields", new LinkedHashMap<>(section.fields));
            sections.add(sectionMap);
        }

        Map<String, Object> pageState = new LinkedHashMap<>();
        pageState.put("pageId", activePage.pageId);
        pageState.put("layout", activePage.layout);
        pageState.put("autoScroll", activePage.autoScroll);
        pageState.put("autoScrollMs", activePage.autoScrollMs);
        pageState.put("sections", sections);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("deviceId", deviceId);
        result.put("state", pageState);
        result.put("usage", "Copy the 'state' object into a state machine definition's 'states' array, or use it as the basis for a page node.");
        return result;
    }

    private Map<String, Object> emptyStateMachinePage(String deviceId) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("deviceId", deviceId);
        result.put("state", Map.of(
                "pageId", "state_machine_page",
                "layout", "vertical_scroll",
                "autoScroll", false,
                "autoScrollMs", 0,
                "sections", List.of()
        ));
        result.put("usage", "No debug workspace exists. Build a page in the section debug editor first.");
        return result;
    }

    public Map<String, Object> clear(String deviceId) {
        workspaces.remove(workspaceKey(DEBUG_NAMESPACE, deviceId));
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("deviceId", deviceId);
        result.put("cleared", true);
        result.put("activePageId", null);
        result.put("pages", List.of());
        return result;
    }

    private WorkspacePage workspacePageFromRequest(String deviceId, Map<String, Object> body) {
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

        WorkspacePage page = new WorkspacePage(pageId, SectionLayout.fromWireName(layoutName).wireName(), autoScroll, autoScrollMs);
        long now = System.currentTimeMillis();
        for (Map<String, Object> rawSection : rawSections) {
            WorkspaceSection section = buildSection(deviceId, rawSection, false, now, null);
            if (page.sections.putIfAbsent(section.sectionId, section) != null) {
                throw new IllegalArgumentException("duplicate sectionId in page: " + section.sectionId);
            }
        }
        page.updatedAt = now;
        return page;
    }

    private WorkspaceSection buildSection(String deviceId, Map<String, Object> rawSection,
                                          boolean requireType, long now, Set<String> existingIds) {
        String sectionId = stringValue(rawSection.get("sectionId"));
        if (sectionId.isBlank()) {
            sectionId = nextSectionId(existingIds);
        }
        String sectionType = stringValue(rawSection.getOrDefault("sectionType", rawSection.get("type")));
        if (requireType && sectionType.isBlank()) {
            throw new IllegalArgumentException("sectionType is required");
        }
        validateSectionType(deviceId, sectionType);

        @SuppressWarnings("unchecked")
        Map<String, Object> fields = rawSection.get("fields") instanceof Map<?, ?> map
                ? (Map<String, Object>) map : null;
        if (fields == null) {
            throw new IllegalArgumentException("fields is required for section " + sectionId);
        }

        SectionData data = sectionDataCodec.buildSectionData(sectionType, fields, sectionId);
        if (data == null) {
            throw new IllegalArgumentException("invalid fields for sectionType: " + sectionType);
        }

        return new WorkspaceSection(
                sectionId,
                sectionType,
                new LinkedHashMap<>(sectionDataCodec.toFieldMap(data)),
                now
        );
    }

    private SectionPatch.PatchEntry applySinglePatch(String deviceId, WorkspacePage page,
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

    private List<SectionPatch.PatchEntry> applyPatchEntries(String deviceId, WorkspacePage page,
                                                            List<Map<String, Object>> rawPatches) {
        List<SectionPatch.PatchEntry> patchEntries = new ArrayList<>();
        long now = System.currentTimeMillis();
        for (Map<String, Object> rawPatch : rawPatches) {
            patchEntries.add(applySinglePatch(deviceId, page, rawPatch, now));
        }
        page.updatedAt = now;
        return patchEntries;
    }

    private void applyPatchEntries(WorkspacePage page, List<SectionPatch.PatchEntry> patches) {
        long now = System.currentTimeMillis();
        for (SectionPatch.PatchEntry entry : patches) {
            String op = entry.op() != null ? entry.op() : "update";
            switch (op) {
                case "remove" -> {
                    if (page.sections.remove(entry.sectionId()) == null) {
                        throw new IllegalArgumentException("section not found: " + entry.sectionId());
                    }
                }
                case "add" -> {
                    if (entry.data() == null || entry.type() == null) {
                        throw new IllegalArgumentException("invalid add patch for section: " + entry.sectionId());
                    }
                    if (page.sections.containsKey(entry.sectionId())) {
                        throw new IllegalArgumentException("section already exists: " + entry.sectionId());
                    }
                    page.sections.put(entry.sectionId(), new WorkspaceSection(
                            entry.sectionId(),
                            entry.type(),
                            new LinkedHashMap<>(sectionDataCodec.toFieldMap(entry.data())),
                            now
                    ));
                }
                default -> {
                    if (entry.data() == null) {
                        throw new IllegalArgumentException("invalid update patch for section: " + entry.sectionId());
                    }
                    WorkspaceSection existing = page.sections.get(entry.sectionId());
                    if (existing == null) {
                        throw new IllegalArgumentException("section not found: " + entry.sectionId());
                    }
                    existing.fields.clear();
                    existing.fields.putAll(sectionDataCodec.toFieldMap(entry.data()));
                    existing.updatedAt = now;
                }
            }
        }
        page.updatedAt = now;
    }

    private SectionPatch.PatchEntry applyAddPatch(String deviceId, WorkspacePage page,
                                                  Map<String, Object> rawPatch, String sectionId, long now) {
        WorkspaceSection section = buildSection(deviceId, rawPatch, true, now, page.sections.keySet());
        sectionId = section.sectionId;
        if (page.sections.containsKey(sectionId)) {
            throw new IllegalArgumentException("section already exists: " + sectionId);
        }
        page.sections.put(sectionId, section);
        return new SectionPatch.PatchEntry(
                sectionId,
                "add",
                section.sectionType,
                sectionDataCodec.buildSectionData(section.sectionType, section.fields, section.sectionId)
        );
    }

    private SectionPatch.PatchEntry applyUpdatePatch(String deviceId, WorkspacePage page,
                                                     Map<String, Object> rawPatch, String sectionId, long now) {
        WorkspaceSection existing = page.sections.get(sectionId);
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
        if (!sectionType.isBlank() && !sectionType.equals(existing.sectionType)) {
            throw new IllegalArgumentException("sectionType cannot change for existing section " + sectionId);
        }

        validateSectionType(deviceId, existing.sectionType);
        Map<String, Object> merged = new LinkedHashMap<>(existing.fields);
        merged.putAll(patchFields);
        SectionData data = sectionDataCodec.buildSectionData(existing.sectionType, merged, sectionId);
        if (data == null) {
            throw new IllegalArgumentException("invalid fields for sectionType: " + existing.sectionType);
        }

        existing.fields.clear();
        existing.fields.putAll(sectionDataCodec.toFieldMap(data));
        existing.updatedAt = now;
        return new SectionPatch.PatchEntry(sectionId, "update", null, data);
    }

    private SectionPatch.PatchEntry applyRemovePatch(WorkspacePage page, String sectionId) {
        if (page.sections.remove(sectionId) == null) {
            throw new IllegalArgumentException("section not found: " + sectionId);
        }
        return new SectionPatch.PatchEntry(sectionId, "remove", null, null);
    }

    private void validateSectionType(String deviceId, String sectionType) {
        if (sectionType.isBlank()) {
            throw new IllegalArgumentException("sectionType is required");
        }
        boolean supported = capabilityProjection.sections(deviceId).stream()
                .anyMatch(section -> section.type().equals(sectionType));
        if (!supported) {
            throw new IllegalArgumentException("unsupported sectionType: " + sectionType);
        }
        if (SectionType.fromWireName(sectionType) == null) {
            throw new IllegalArgumentException("unknown sectionType: " + sectionType);
        }
    }

    private SectionScene toScene(WorkspacePage page) {
        List<SectionEntry> entries = new ArrayList<>();
        for (WorkspaceSection section : page.sections.values()) {
            SectionType type = SectionType.fromWireName(section.sectionType);
            SectionData data = sectionDataCodec.buildSectionData(section.sectionType, section.fields, section.sectionId);
            entries.add(new SectionEntry(type, section.sectionId, data));
        }
        return new SectionScene(
                page.pageId,
                SectionLayout.fromWireName(page.layout),
                page.autoScroll,
                page.autoScrollMs,
                entries
        );
    }

    private Map<String, Object> emptyState(String deviceId) {
        Map<String, Object> state = new LinkedHashMap<>();
        state.put("deviceId", deviceId);
        state.put("activePageId", null);
        state.put("pages", List.of());
        return state;
    }

    private String stringValue(Object value) {
        return value instanceof String s ? s : value != null ? String.valueOf(value) : "";
    }

    private String resolvePatchPageId(DebugWorkspace workspace, Map<String, Object> body) {
        String pageId = stringValue(body.get("pageId"));
        if (!pageId.isBlank()) {
            return pageId;
        }
        if (workspace.activePageId != null && !workspace.activePageId.isBlank()) {
            return workspace.activePageId;
        }
        throw new IllegalArgumentException("pageId is required when no active page exists");
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

    private DebugWorkspace workspace(String namespace, String deviceId) {
        return workspaces.computeIfAbsent(workspaceKey(namespace, deviceId), ignored -> new DebugWorkspace(deviceId));
    }

    private String workspaceKey(String namespace, String deviceId) {
        return namespace + ":" + deviceId;
    }

    private void replacePage(DebugWorkspace workspace, WorkspacePage page) {
        workspace.activePageId = page.pageId;
        workspace.pages.put(page.pageId, page);
    }

    private Map<String, Object> getState(String namespace, String deviceId) {
        DebugWorkspace workspace = workspaces.get(workspaceKey(namespace, deviceId));
        if (workspace == null) {
            return emptyState(deviceId);
        }

        List<Map<String, Object>> pages = new ArrayList<>();
        for (WorkspacePage page : workspace.pages.values()) {
            List<Map<String, Object>> sections = new ArrayList<>();
            for (WorkspaceSection section : page.sections.values()) {
                Map<String, Object> sectionMap = new LinkedHashMap<>();
                sectionMap.put("sectionId", section.sectionId);
                sectionMap.put("sectionType", section.sectionType);
                sectionMap.put("fields", new LinkedHashMap<>(section.fields));
                sectionMap.put("updatedAt", section.updatedAt);
                sections.add(sectionMap);
            }

            Map<String, Object> pageMap = new LinkedHashMap<>();
            pageMap.put("pageId", page.pageId);
            pageMap.put("layout", page.layout);
            pageMap.put("autoScroll", page.autoScroll);
            pageMap.put("autoScrollMs", page.autoScrollMs);
            pageMap.put("sections", sections);
            pageMap.put("updatedAt", page.updatedAt);
            pages.add(pageMap);
        }

        return Map.of(
                "deviceId", deviceId,
                "activePageId", workspace.activePageId,
                "pages", pages
        );
    }

    private String findSectionType(String namespace, String deviceId, String pageId, String sectionId) {
        DebugWorkspace workspace = workspaces.get(workspaceKey(namespace, deviceId));
        if (workspace == null) {
            return null;
        }
        WorkspacePage page = workspace.pages.get(pageId);
        if (page == null) {
            return null;
        }
        WorkspaceSection section = page.sections.get(sectionId);
        return section != null ? section.sectionType : null;
    }

    private static final class DebugWorkspace {
        private final String deviceId;
        private String activePageId;
        private final Map<String, WorkspacePage> pages = new LinkedHashMap<>();

        private DebugWorkspace(String deviceId) {
            this.deviceId = deviceId;
        }
    }

    private static final class WorkspacePage {
        private final String pageId;
        private final String layout;
        private final boolean autoScroll;
        private final int autoScrollMs;
        private long updatedAt;
        private final Map<String, WorkspaceSection> sections = new LinkedHashMap<>();

        private WorkspacePage(String pageId, String layout, boolean autoScroll, int autoScrollMs) {
            this.pageId = pageId;
            this.layout = layout;
            this.autoScroll = autoScroll;
            this.autoScrollMs = autoScrollMs;
        }
    }

    private static final class WorkspaceSection {
        private final String sectionId;
        private final String sectionType;
        private final Map<String, Object> fields;
        private long updatedAt;

        private WorkspaceSection(String sectionId, String sectionType, Map<String, Object> fields, long updatedAt) {
            this.sectionId = sectionId;
            this.sectionType = sectionType;
            this.fields = fields;
            this.updatedAt = updatedAt;
        }
    }
}
