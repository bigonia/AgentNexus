package com.zwbd.agentnexus.sdui.section;

import com.zwbd.agentnexus.sdui.protocol.CapabilitySchema;
import com.zwbd.agentnexus.sdui.service.SduiCapabilityService;
import com.zwbd.agentnexus.sdui.service.SduiProtocolService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

@Slf4j
@Service
@RequiredArgsConstructor
public class SectionOrchestrationService {

    private final SduiProtocolService protocolService;
    private final SduiCapabilityService capabilityService;
    private final SectionSceneBuilder sceneBuilder;
    private final SectionCapabilityAdapter adapter;
    private final SectionDataCodec sectionDataCodec;
    private final List<SectionTriggerHook> hooks = new CopyOnWriteArrayList<>();

    /** deviceId → pageId → SectionPageDefinition */
    private final Map<String, Map<String, SectionPageDefinition>> pageStatesByDevice = new ConcurrentHashMap<>();

    public Map<String, Object> getPageState(String deviceId) {
        return getSectionState(deviceId);
    }

    // ── Scene/Patch sending ──

    public void registerHook(SectionTriggerHook hook) {
        hooks.add(hook);
    }

    public void removeHook(SectionTriggerHook hook) {
        hooks.remove(hook);
    }

    public boolean sendScene(String deviceId, SectionScene scene) {
        Optional<CapabilitySchema.CapabilitySnapshot> caps = capabilityService.getCapabilities(deviceId);
        if (caps.isPresent()) {
            scene = adapter.adapt(scene, caps.get());
            if (scene.sections().isEmpty()) {
                log.warn("All sections filtered out for device {}, nothing to send", deviceId);
                return false;
            }
        } else {
            log.info("No capabilities cached for device {}, sending scene without adaptation", deviceId);
        }

        String json = sceneBuilder.buildSceneJson(scene);
        log.info("Sending section scene to {}: pageId={} sections={} layout={}",
                deviceId, scene.pageId(), scene.sections().size(), scene.layout());
        boolean sent = protocolService.sendSectionScene(deviceId, json);
        if (sent) {
            rememberScene(deviceId, scene);
            notifyHooks(deviceId, scene.pageId(), SectionTriggerHook.TriggerType.SCENE, json);
        }
        return sent;
    }

    public boolean sendPatch(String deviceId, SectionPatch patch) {
        String json = sceneBuilder.buildPatchJson(patch);
        log.info("Sending section patch to {}: pageId={} patches={}",
                deviceId, patch.pageId(), patch.patches().size());
        boolean sent = protocolService.sendSectionPatch(deviceId, json);
        if (sent) {
            rememberPatch(deviceId, patch);
            notifyHooks(deviceId, patch.pageId(), SectionTriggerHook.TriggerType.PATCH, json);
        }
        return sent;
    }

    // ── State query ──

    public Map<String, Object> getSectionState(String deviceId) {
        Map<String, SectionPageDefinition> devicePages = pageStatesByDevice.get(deviceId);
        if (devicePages == null || devicePages.isEmpty()) {
            return emptyState(deviceId);
        }

        List<Map<String, Object>> pages = new ArrayList<>();
        for (SectionPageDefinition page : devicePages.values()) {
            pages.add(page.toMap());
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("deviceId", deviceId);
        result.put("activePageId", null);
        result.put("pages", pages);
        return result;
    }

    public String findSectionType(String deviceId, String pageId, String sectionId) {
        Map<String, SectionPageDefinition> devicePages = pageStatesByDevice.get(deviceId);
        if (devicePages == null) return null;
        SectionPageDefinition page = devicePages.get(pageId);
        if (page == null) return null;
        SectionPageDefinition.SectionDef section = page.sections().get(sectionId);
        return section != null ? section.sectionType() : null;
    }

    // ── Internal: state remembering ──

    private void rememberScene(String deviceId, SectionScene scene) {
        SectionPageDefinition page = SectionPageDefinition.fromScene(scene, sectionDataCodec);
        Map<String, SectionPageDefinition> devicePages = pageStatesByDevice.computeIfAbsent(deviceId, k -> new LinkedHashMap<>());
        devicePages.put(scene.pageId(), page);
    }

    private void rememberPatch(String deviceId, SectionPatch patch) {
        Map<String, SectionPageDefinition> devicePages = pageStatesByDevice.computeIfAbsent(deviceId, k -> new LinkedHashMap<>());
        SectionPageDefinition page = devicePages.get(patch.pageId());
        if (page == null) {
            page = new SectionPageDefinition(patch.pageId());
        }

        LinkedHashMap<String, SectionPageDefinition.SectionDef> sections = new LinkedHashMap<>(page.sections());
        for (SectionPatch.PatchEntry entry : patch.patches()) {
            String op = entry.op() != null ? entry.op() : "update";
            switch (op) {
                case "remove" -> sections.remove(entry.sectionId());
                case "add" -> {
                    if (entry.data() == null || entry.type() == null) continue;
                    Map<String, Object> fields = new LinkedHashMap<>(sectionDataCodec.toFieldMap(entry.data()));
                    sections.put(entry.sectionId(), new SectionPageDefinition.SectionDef(entry.sectionId(), entry.type(), fields));
                }
                default -> { // update
                    if (entry.data() == null) continue;
                    Map<String, Object> fields = new LinkedHashMap<>(sectionDataCodec.toFieldMap(entry.data()));
                    SectionPageDefinition.SectionDef existing = sections.get(entry.sectionId());
                    if (existing == null) {
                        String type = entry.type() != null ? entry.type() : "unknown";
                        sections.put(entry.sectionId(), new SectionPageDefinition.SectionDef(entry.sectionId(), type, fields));
                    } else {
                        Map<String, Object> merged = new LinkedHashMap<>(existing.fields());
                        merged.putAll(fields);
                        sections.put(entry.sectionId(), new SectionPageDefinition.SectionDef(entry.sectionId(), existing.sectionType(), merged));
                    }
                }
            }
        }
        devicePages.put(patch.pageId(), new SectionPageDefinition(page.pageId(), page.layout(), page.autoScroll(), page.autoScrollMs(), sections));
    }

    private Map<String, Object> emptyState(String deviceId) {
        Map<String, Object> state = new LinkedHashMap<>();
        state.put("deviceId", deviceId);
        state.put("activePageId", null);
        state.put("pages", List.of());
        return state;
    }

    // ── Hooks ──

    private void notifyHooks(String deviceId, String pageId, SectionTriggerHook.TriggerType type, String json) {
        for (SectionTriggerHook hook : hooks) {
            try {
                hook.onSectionSent(deviceId, pageId, type, json);
            } catch (Exception e) {
                log.warn("Section trigger hook error for device {}: {}", deviceId, e.getMessage());
            }
        }
    }

    public boolean supportsSection(String deviceId) {
        return capabilityService.getSectionCapability(deviceId).isPresent();
    }

    public Optional<CapabilitySchema.DisplayInfo> getSectionCapability(String deviceId) {
        return capabilityService.getSectionCapability(deviceId);
    }
}
