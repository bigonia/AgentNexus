package com.zwbd.agentnexus.sdui.section;

import com.zwbd.agentnexus.sdui.protocol.CapabilitySchema;
import com.zwbd.agentnexus.sdui.service.SduiCapabilityService;
import com.zwbd.agentnexus.sdui.service.SduiProtocolService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Optional;
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

    public Optional<CapabilitySchema.SectionCapability> getSectionCapability(String deviceId) {
        return capabilityService.getSectionCapability(deviceId);
    }
}
