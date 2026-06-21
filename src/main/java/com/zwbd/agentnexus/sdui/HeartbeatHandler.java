package com.zwbd.agentnexus.sdui;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.zwbd.agentnexus.sdui.model.SduiDevice;
import com.zwbd.agentnexus.sdui.section.SectionOrchestrationService;
import com.zwbd.agentnexus.sdui.section.SectionPresets;
import com.zwbd.agentnexus.sdui.service.SduiCapabilityService;
import com.zwbd.agentnexus.sdui.service.SduiDeviceService;
import com.zwbd.agentnexus.sdui.service.SduiProtocolService;
import com.zwbd.agentnexus.sdui.statemachine.StateMachineProjectionService;
import com.zwbd.agentnexus.sdui.statemachine.model.StateMachine;
import com.zwbd.agentnexus.sdui.statemachine.model.StateMachineDeployment;
import com.zwbd.agentnexus.sdui.statemachine.repo.StateMachineDeploymentRepository;
import com.zwbd.agentnexus.sdui.statemachine.repo.StateMachineRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.WebSocketSession;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Slf4j
@Component
public class HeartbeatHandler implements TopicHandler {

    private final DeviceSessionManager sessionManager;
    private final SduiDeviceService deviceService;
    private final SduiProtocolService protocolService;
    private final SduiCapabilityService capabilityService;
    private final SectionOrchestrationService orchestrationService;
    private final StateMachineRepository stateMachineRepository;
    private final StateMachineDeploymentRepository deploymentRepository;
    private final StateMachineProjectionService projectionService;

    public HeartbeatHandler(DeviceSessionManager sessionManager,
                            SduiDeviceService deviceService,
                            SduiProtocolService protocolService,
                            SduiCapabilityService capabilityService,
                            SectionOrchestrationService orchestrationService,
                            StateMachineRepository stateMachineRepository,
                            StateMachineDeploymentRepository deploymentRepository,
                            StateMachineProjectionService projectionService) {
        this.sessionManager = sessionManager;
        this.deviceService = deviceService;
        this.protocolService = protocolService;
        this.capabilityService = capabilityService;
        this.orchestrationService = orchestrationService;
        this.stateMachineRepository = stateMachineRepository;
        this.deploymentRepository = deploymentRepository;
        this.projectionService = projectionService;
    }

    @Override
    public String getSupportedTopic() {
        return "telemetry/heartbeat";
    }

    @Override
    public void handle(WebSocketSession session, SduiMessage message) {
        String deviceId = message.getDeviceId();
        boolean justConnected = false;

        if (!sessionManager.isDeviceOnline(deviceId) || !sessionManager.isSameSession(deviceId, session)) {
            sessionManager.registerSession(deviceId, session);
            justConnected = true;
        }

        JsonNode payload = message.getPayload();
        if (payload == null || payload.isNull()) {
            payload = createEmptyPayload();
            log.info("Heartbeat payload missing, fallback to empty payload. deviceId={}", deviceId);
        }

        int rssi = payload.path("wifi_rssi").asInt(0);
        int freeHeap = payload.path("free_heap_internal").asInt(0);
        // log.debug("Heartbeat {} -> RSSI: {} dBm, FreeHeap: {} bytes", deviceId, rssi, freeHeap);

        SduiDevice device = deviceService.onHeartbeat(deviceId, payload);
        if (justConnected) {
            log.info("Device connected. deviceId={}, registrationStatus={}, spaceId={}",
                    deviceId, device.getRegistrationStatus(), device.getOwnerSpaceId());
        }
        if (!justConnected) {
            return;
        }

        if ("UNCLAIMED".equalsIgnoreCase(device.getRegistrationStatus())) {
            log.info("Device unclaimed, pushing claim code scene. deviceId={}, claimCode={}",
                    deviceId, device.getClaimCode());
            pushClaimCodeScene(device);
            return;
        }

        log.info("Device claimed and reconnected. deviceId={}, capabilitiesStored={}",
                deviceId, device.getCapabilitiesSnapshot() != null);
        pushAuthoritativeState(deviceId);
    }

    private void pushAuthoritativeState(String deviceId) {
        List<StateMachineDeployment> deployments = deploymentRepository.findByDeviceId(deviceId);
        int pushed = 0;
        for (StateMachineDeployment dep : deployments) {
            try {
                StateMachine sm = stateMachineRepository.findById(dep.getStateMachineId()).orElse(null);
                if (sm == null) continue;
                // Build pages from the deployment's current state
                Map<String, Object> page = buildPageFromState(sm.getDefinition(), dep.getCurrentStateId(), dep.getDevices());
                List<Map<String, Object>> results = projectionService.projectScene(List.of(page));
                boolean sent = results.stream().anyMatch(r -> Boolean.TRUE.equals(r.get("sent")));
                if (sent) pushed++;
            } catch (Exception e) {
                log.warn("Reconnection scene push failed device={} sm={}: {}",
                        deviceId, dep.getStateMachineId(), e.getMessage());
            }
        }
        log.info("Reconnection recovery: device={}, deployments={}, scenesPushed={}",
                deviceId, deployments.size(), pushed);
    }

    private void pushClaimCodeScene(SduiDevice device) {
        String code = device.getClaimCode();
        if (code == null || code.isBlank()) {
            log.warn("Device {} is unclaimed but has no claim code, skip scene push", device.getDeviceId());
            return;
        }
        try {
            orchestrationService.sendScene(device.getDeviceId(),
                    SectionPresets.claimCodeScene(code));
        } catch (Exception e) {
            log.warn("Failed to push claim code scene to device {}: {}", device.getDeviceId(), e.getMessage());
        }
    }

    private Map<String, Object> buildPageFromState(Map<String, Object> definition, String stateId, List<String> devices) {
        Map<String, Object> page = new LinkedHashMap<>();
        page.put("pageId", "main");
        page.put("layout", "vertical_scroll");
        page.put("autoScroll", false);
        page.put("autoScrollMs", 0);
        page.put("devices", new ArrayList<>(devices));

        List<Map<String, Object>> stateSections = List.of();
        Object rawStates = definition.get("states");
        if (rawStates instanceof List<?> states) {
            for (Object rawState : states) {
                if (rawState instanceof Map<?, ?> stateMap
                        && stateId.equals(String.valueOf(stateMap.get("id")))) {
                    Object rawSections = stateMap.get("sections");
                    if (rawSections instanceof List<?> sections) {
                        stateSections = new ArrayList<>();
                        for (Object s : sections) {
                            if (s instanceof Map<?, ?> sm) {
                                Map<String, Object> copy = new LinkedHashMap<>();
                                sm.forEach((k, v) -> copy.put(String.valueOf(k), v));
                                stateSections.add(copy);
                            }
                        }
                    }
                    break;
                }
            }
        }
        page.put("sections", stateSections);
        return page;
    }

    private JsonNode createEmptyPayload() {
        return new ObjectNode(JsonNodeFactory.instance);
    }
}
