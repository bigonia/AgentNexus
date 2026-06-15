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

    private final Map<String, DeviceSectionState> deviceStates = new ConcurrentHashMap<>();

    public Map<String, Object> getPageState(String deviceId) {
        return getSectionState(deviceId);
    }

    // ── Scene/Patch sending (existing) ──

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

    public Map<String, Object> getSectionState(String deviceId) {
        DeviceSectionState state = deviceStates.get(deviceId);
        if (state == null) {
            return Map.of(
                    "deviceId", deviceId,
                    "activePageId", null,
                    "pages", List.of()
            );
        }

        List<Map<String, Object>> pages = new ArrayList<>();
        for (PageState page : state.pages.values()) {
            List<Map<String, Object>> sections = new ArrayList<>();
            for (SectionState section : page.sections.values()) {
                Map<String, Object> entry = new LinkedHashMap<>();
                entry.put("sectionId", section.sectionId);
                entry.put("sectionType", section.sectionType);
                entry.put("fields", section.fields);
                entry.put("updatedAt", section.updatedAt);
                sections.add(entry);
            }

            Map<String, Object> pageEntry = new LinkedHashMap<>();
            pageEntry.put("pageId", page.pageId);
            pageEntry.put("layout", page.layout);
            pageEntry.put("autoScroll", page.autoScroll);
            pageEntry.put("autoScrollMs", page.autoScrollMs);
            pageEntry.put("sections", sections);
            pageEntry.put("updatedAt", page.updatedAt);
            pages.add(pageEntry);
        }

        return Map.of(
                "deviceId", deviceId,
                "activePageId", state.activePageId,
                "pages", pages
        );
    }

    public String findSectionType(String deviceId, String pageId, String sectionId) {
        DeviceSectionState deviceState = deviceStates.get(deviceId);
        if (deviceState == null) {
            return null;
        }
        PageState pageState = deviceState.pages.get(pageId);
        if (pageState == null) {
            return null;
        }
        SectionState sectionState = pageState.sections.get(sectionId);
        return sectionState != null ? sectionState.sectionType : null;
    }

    private void rememberScene(String deviceId, SectionScene scene) {
        DeviceSectionState deviceState = deviceStates.computeIfAbsent(deviceId, ignored -> new DeviceSectionState());
        PageState pageState = new PageState(scene.pageId());
        pageState.layout = scene.layout().wireName();
        pageState.autoScroll = scene.autoScroll();
        pageState.autoScrollMs = scene.autoScrollMs();
        pageState.updatedAt = System.currentTimeMillis();

        for (SectionEntry entry : scene.sections()) {
            SectionState sectionState = new SectionState(
                    entry.sectionId(),
                    entry.type().wireName(),
                    new LinkedHashMap<>(sectionDataCodec.toFieldMap(entry.data())),
                    System.currentTimeMillis()
            );
            pageState.sections.put(sectionState.sectionId, sectionState);
        }

        deviceState.activePageId = scene.pageId();
        deviceState.pages.put(scene.pageId(), pageState);
    }

    private void rememberPatch(String deviceId, SectionPatch patch) {
        DeviceSectionState deviceState = deviceStates.computeIfAbsent(deviceId, ignored -> new DeviceSectionState());
        PageState pageState = deviceState.pages.computeIfAbsent(patch.pageId(), PageState::new);
        if (deviceState.activePageId == null || deviceState.activePageId.isBlank()) {
            deviceState.activePageId = patch.pageId();
        }

        long now = System.currentTimeMillis();
        for (SectionPatch.PatchEntry entry : patch.patches()) {
            String op = entry.op() != null ? entry.op() : "update";
            switch (op) {
                case "remove" -> pageState.sections.remove(entry.sectionId());
                case "add" -> {
                    if (entry.data() == null || entry.type() == null) {
                        continue;
                    }
                    pageState.sections.put(entry.sectionId(), new SectionState(
                            entry.sectionId(),
                            entry.type(),
                            new LinkedHashMap<>(sectionDataCodec.toFieldMap(entry.data())),
                            now
                    ));
                }
                default -> {
                    if (entry.data() == null) {
                        continue;
                    }
                    Map<String, Object> fields = new LinkedHashMap<>(sectionDataCodec.toFieldMap(entry.data()));
                    SectionState existing = pageState.sections.get(entry.sectionId());
                    if (existing == null) {
                        String type = entry.type() != null ? entry.type() : "unknown";
                        pageState.sections.put(entry.sectionId(), new SectionState(entry.sectionId(), type, fields, now));
                    } else {
                        existing.fields.putAll(fields);
                        existing.updatedAt = now;
                    }
                }
            }
        }
        pageState.updatedAt = now;
    }

    private static final class DeviceSectionState {
        private String activePageId;
        private final Map<String, PageState> pages = new LinkedHashMap<>();
    }

    private static final class PageState {
        private final String pageId;
        private String layout = "vertical_scroll";
        private boolean autoScroll;
        private int autoScrollMs;
        private long updatedAt;
        private final Map<String, SectionState> sections = new LinkedHashMap<>();

        private PageState(String pageId) {
            this.pageId = pageId;
        }
    }

    private static final class SectionState {
        private final String sectionId;
        private final String sectionType;
        private final Map<String, Object> fields;
        private long updatedAt;

        private SectionState(String sectionId, String sectionType, Map<String, Object> fields, long updatedAt) {
            this.sectionId = sectionId;
            this.sectionType = sectionType;
            this.fields = fields;
            this.updatedAt = updatedAt;
        }
    }

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
