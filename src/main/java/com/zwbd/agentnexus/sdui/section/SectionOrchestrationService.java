package com.zwbd.agentnexus.sdui.section;

import com.zwbd.agentnexus.sdui.protocol.CapabilitySchema;
import com.zwbd.agentnexus.sdui.service.SduiCapabilityService;
import com.zwbd.agentnexus.sdui.service.SduiProtocolService;
import com.zwbd.agentnexus.sdui.workflow.PageDef;
import com.zwbd.agentnexus.sdui.workflow.SectionSlot;
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
    private final List<SectionTriggerHook> hooks = new CopyOnWriteArrayList<>();

    // Per-device SectionSlot registry: deviceId -> (slotKey -> SectionSlot)
    private final Map<String, Map<String, SectionSlot>> deviceSlots = new ConcurrentHashMap<>();

    // ── Section Slot Management ──

    /**
     * Register available Section slots from device's section capability.
     */
    public void registerDeviceSlots(String deviceId, CapabilitySchema.DisplayInfo displayInfo,
                                     List<PageDef> pages) {
        Map<String, SectionSlot> slots = new LinkedHashMap<>();
        if (pages != null) {
            for (var page : pages) {
                if (page.sections() == null) continue;
                for (var section : page.sections()) {
                    String key = page.id() + "/" + section.id();
                    slots.put(key, new SectionSlot(section.id(), page.id(), section.type()));
                }
            }
        }
        deviceSlots.put(deviceId, slots);
        log.info("Registered {} section slots for device {}", slots.size(), deviceId);
    }

    /**
     * Initialize default slots based on device capability.
     */
    public void initDefaultSlots(String deviceId, CapabilitySchema.DisplayInfo displayInfo) {
        Map<String, SectionSlot> slots = new LinkedHashMap<>();
        if (displayInfo != null && displayInfo.sectionTypes() != null) {
            int idx = 0;
            for (String type : displayInfo.sectionTypes()) {
                String slotId = type.replace("_section", "") + "_" + (idx++);
                slots.put("home/" + slotId, new SectionSlot(slotId, "home", type));
            }
        }
        if (!slots.isEmpty()) {
            deviceSlots.put(deviceId, slots);
            log.info("Initialized {} default slots for device {}", slots.size(), deviceId);
        }
    }

    public Map<String, SectionSlot> getDeviceSlots(String deviceId) {
        return deviceSlots.getOrDefault(deviceId, Map.of());
    }

    public void unregisterDeviceSlots(String deviceId) {
        deviceSlots.remove(deviceId);
        log.info("Unregistered all slots for device {}", deviceId);
    }

    /**
     * Allocate a SectionSlot to a workflow. Returns false if already exclusively bound.
     */
    public boolean allocateSlot(String deviceId, String pageId, String sectionId,
                                 String workflowId, String definitionName, String outputName) {
        Map<String, SectionSlot> slots = deviceSlots.get(deviceId);
        if (slots == null) return false;

        String key = pageId + "/" + sectionId;
        SectionSlot slot = slots.get(key);
        if (slot == null) return false;

        return slot.bind(workflowId, definitionName, outputName);
    }

    /**
     * Free a SectionSlot from a workflow binding.
     */
    public boolean freeSlot(String deviceId, String pageId, String sectionId) {
        Map<String, SectionSlot> slots = deviceSlots.get(deviceId);
        if (slots == null) return false;

        String key = pageId + "/" + sectionId;
        SectionSlot slot = slots.get(key);
        if (slot == null) return false;

        slot.unbind();
        return true;
    }

    /**
     * Free all slots owned by a specific workflow on a device.
     */
    public void freeWorkflowSlots(String deviceId, String workflowId) {
        Map<String, SectionSlot> slots = deviceSlots.get(deviceId);
        if (slots == null) return;

        for (SectionSlot slot : slots.values()) {
            if (slot.binding() != null && slot.binding().workflowId().equals(workflowId)) {
                slot.unbind();
            }
        }
    }

    /**
     * Get all slots bound to a specific workflow on a device.
     */
    public List<Map<String, String>> getWorkflowSlots(String deviceId, String workflowId) {
        List<Map<String, String>> result = new ArrayList<>();
        Map<String, SectionSlot> slots = deviceSlots.get(deviceId);
        if (slots == null) return result;

        for (var entry : slots.entrySet()) {
            SectionSlot slot = entry.getValue();
            if (slot.binding() != null && slot.binding().workflowId().equals(workflowId)) {
                result.add(Map.of(
                        "slotKey", entry.getKey(),
                        "sectionType", slot.sectionType(),
                        "outputName", slot.binding().outputName()
                ));
            }
        }
        return result;
    }

    /**
     * Detect conflicts between proposed slots and existing bindings.
     */
    public List<Map<String, String>> detectSlotConflicts(String deviceId,
                                                          Map<String, String> proposedSlots) {
        List<Map<String, String>> conflicts = new ArrayList<>();
        Map<String, SectionSlot> slots = deviceSlots.get(deviceId);
        if (slots == null) return conflicts;

        for (var entry : proposedSlots.entrySet()) {
            SectionSlot slot = slots.get(entry.getKey());
            if (slot != null && !slot.isFree()) {
                conflicts.add(Map.of(
                        "slot", entry.getKey(),
                        "sectionType", slot.sectionType(),
                        "ownedBy", slot.binding().workflowId(),
                        "ownedByName", slot.binding().definitionName()
                ));
            }
        }
        return conflicts;
    }

    public Map<String, Object> getSlotStatus(String deviceId) {
        Map<String, SectionSlot> slots = deviceSlots.get(deviceId);
        if (slots == null) {
            return Map.of("deviceId", deviceId, "slots", List.of());
        }

        List<Map<String, Object>> slotList = new ArrayList<>();
        for (var entry : slots.entrySet()) {
            SectionSlot slot = entry.getValue();
            Map<String, Object> info = new LinkedHashMap<>();
            info.put("slotKey", entry.getKey());
            info.put("pageId", slot.pageId());
            info.put("sectionType", slot.sectionType());
            info.put("free", slot.isFree());
            if (slot.binding() != null) {
                info.put("boundTo", Map.of(
                        "workflowId", slot.binding().workflowId(),
                        "name", slot.binding().definitionName(),
                        "outputName", slot.binding().outputName()
                ));
            }
            slotList.add(info);
        }
        return Map.of("deviceId", deviceId, "slots", slotList);
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
            notifyHooks(deviceId, patch.pageId(), SectionTriggerHook.TriggerType.PATCH, json);
        }
        return sent;
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
