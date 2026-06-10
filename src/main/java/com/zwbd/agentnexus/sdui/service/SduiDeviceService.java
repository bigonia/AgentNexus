package com.zwbd.agentnexus.sdui.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.zwbd.agentnexus.common.web.GlobalContext;
import com.zwbd.agentnexus.sdui.DeviceSessionManager;
import com.zwbd.agentnexus.sdui.dto.SduiControlDispatchResult;
import com.zwbd.agentnexus.sdui.dto.SduiDeviceControlRequest;
import com.zwbd.agentnexus.sdui.model.SduiDevice;
import com.zwbd.agentnexus.sdui.model.SduiDeviceTelemetry;
import com.zwbd.agentnexus.sdui.protocol.CapabilitySchema;
import com.zwbd.agentnexus.sdui.protocol.CapabilitySnapshotParser;
import com.zwbd.agentnexus.sdui.repo.SduiDeviceCommandRepository;
import com.zwbd.agentnexus.sdui.repo.SduiDeviceRepository;
import com.zwbd.agentnexus.sdui.repo.SduiDeviceTelemetryRepository;
import com.zwbd.agentnexus.sdui.section.SectionOrchestrationService;
import com.zwbd.agentnexus.sdui.section.SectionPresets;
import com.zwbd.agentnexus.sdui.workflow.WorkflowService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;

@Slf4j
@Service
@RequiredArgsConstructor
public class SduiDeviceService {

    private final DeviceLifecycleService lifecycleService;
    private final CommandService commandService;
    private final ClaimService claimService;
    private final SduiCapabilityService capabilityService;
    private final SectionOrchestrationService orchestrationService;
    private final DeviceSessionManager sessionManager;
    private final WorkflowService workflowService;
    private final SduiDeviceRepository deviceRepository;
    private final SduiDeviceTelemetryRepository telemetryRepository;
    private final SduiDeviceCommandRepository commandRepository;
    private final ObjectMapper objectMapper;

    // ── Heartbeat / lifecycle ──

    public SduiDevice onHeartbeat(String deviceId, JsonNode payload) {
        return lifecycleService.onHeartbeat(deviceId, new DeviceLifecycleService.SduiHeartbeatData(
                optInt(payload, "wifi_rssi"),
                optString(payload, "ip"),
                optDouble(payload, "temperature"),
                optInt(payload, "free_heap_internal"),
                optInt(payload, "largest_heap_internal"),
                optInt(payload, "free_heap_dma"),
                optInt(payload, "largest_heap_dma"),
                optInt(payload, "free_heap_psram"),
                optInt(payload, "largest_heap_psram"),
                optInt(payload, "free_heap_total"),
                optInt(payload, "frag_internal_pct"),
                optInt(payload, "frag_dma_pct"),
                optInt(payload, "frag_psram_pct"),
                optInt(payload, "uptime_s"),
                optBoolean(payload, "power_supported"),
                optInt(payload, "battery_mv"),
                optInt(payload, "battery_pct"),
                optBoolean(payload, "charging"),
                optBoolean(payload, "ext_power_present"),
                optBoolean(payload, "ext_power_ctrl"),
                optBoolean(payload, "ext_power_on")
        ));
    }

    public void updateCurrentPage(String deviceId, String pageId) {
        lifecycleService.updateCurrentPage(deviceId, pageId);
    }

    // ── Device queries ──

    public long countDevices() { return deviceRepository.countByOwnerSpaceId(currentSpaceId()); }
    public long countOnlineDevices() { return deviceRepository.countByOwnerSpaceIdAndStatusIgnoreCase(currentSpaceId(), "ONLINE"); }
    public long countOfflineDevices() { return deviceRepository.countByOwnerSpaceIdAndStatusIgnoreCase(currentSpaceId(), "OFFLINE"); }

    public List<SduiDevice> listDevices() {
        lifecycleService.refreshOnlineStatus();
        return deviceRepository.findByOwnerSpaceId(currentSpaceId());
    }

    public List<SduiDevice> listUnclaimedDevices() {
        return deviceRepository.findByRegistrationStatus("UNCLAIMED");
    }

    public Optional<SduiDevice> getDevice(String deviceId) {
        lifecycleService.refreshOnlineStatus();
        return deviceRepository.findById(deviceId)
                .filter(d -> currentSpaceId().equals(d.getOwnerSpaceId()));
    }

    public Page<SduiDeviceTelemetry> getTelemetry(String deviceId, int page, int size) {
        requireOwned(deviceId);
        return telemetryRepository.findByDeviceIdOrderByCreatedAtDesc(deviceId, PageRequest.of(page, size));
    }

    public SduiDeviceTelemetry getLatestTelemetry(String deviceId) {
        return telemetryRepository.findFirstByDeviceIdOrderByCreatedAtDesc(deviceId);
    }

    // ── Commands ──

    public SduiControlDispatchResult controlDevice(String deviceId, SduiDeviceControlRequest req) {
        requireOwned(deviceId);
        return commandService.dispatchCommand(deviceId, req.command(), req.value());
    }

    public void handleControlAck(String deviceId, JsonNode payload) {
        String cmdId = payload.path("cmd_id").asText(null);
        String status = payload.path("status").asText("ERROR").toUpperCase();
        String reason = payload.path("reason").asText("");
        if (cmdId != null && !cmdId.isBlank()) {
            commandService.handleControlAck(deviceId, cmdId, status, reason);
        }
    }

    public int markTimedOutCommands() { return commandService.markTimedOutCommands(); }

    // ── Update ──

    @Transactional
    public SduiDevice updateDevice(String deviceId, String name, String notes) {
        SduiDevice device = deviceRepository.findById(deviceId)
                .filter(d -> currentSpaceId().equals(d.getOwnerSpaceId()))
                .orElseThrow(() -> new IllegalArgumentException("device not found or not owned"));
        if (name != null && !name.isBlank()) {
            device.setName(name.trim());
        }
        if (notes != null) {
            device.setNotes(notes.isBlank() ? null : notes.trim());
        }
        return deviceRepository.save(device);
    }

    // ── Claim / delete ──

    public SduiDevice claimDevice(String deviceId, String claimCode, String deviceName) {
        SduiDevice device = claimService.claimDevice(deviceId, claimCode, deviceName, currentSpaceId());
        try {
            orchestrationService.sendScene(deviceId, SectionPresets.claimedSuccessScene());
        } catch (Exception e) {
            log.warn("Failed to push claimed success scene to device {}: {}", deviceId, e.getMessage());
        }
        return device;
    }

    @Transactional
    public void deleteDevice(String deviceId) {
        SduiDevice device = deviceRepository.findById(deviceId)
                .filter(d -> currentSpaceId().equals(d.getOwnerSpaceId()))
                .orElseThrow(() -> new IllegalArgumentException("device not found or not owned"));

        workflowService.unloadWorkflow(deviceId);
        telemetryRepository.deleteByDeviceId(deviceId);
        commandRepository.deleteByDeviceId(deviceId);
        capabilityService.clearCapabilitiesCache(deviceId);
        device.setOwnerSpaceId("");
        device.setRegistrationStatus("UNCLAIMED");
        device.setClaimCode(null);
        device.setClaimCodeExpireAt(null);
        device.setClaimedAt(null);
        device.setCurrentPageId(null);
        device.setCurrentAppId(null);
        device.setStatus("OFFLINE");

        sessionManager.disconnectDevice(deviceId);

        log.info("Device {} deleted from space {}, reset to unclaimed", deviceId, currentSpaceId());
    }

    // ── Capabilities ──

    @Transactional
    public void handleCapabilitiesReport(String deviceId, JsonNode payload) {
        try {
            String rawJson = payload.toString();
            CapabilitySchema.CapabilitySnapshot caps = CapabilitySnapshotParser.parse(rawJson, objectMapper);
            capabilityService.onCapabilitiesReport(deviceId, caps, rawJson);

            SduiDevice device = lifecycleService.onHeartbeat(deviceId,
                    new DeviceLifecycleService.SduiHeartbeatData(
                            null, null, null, null, null, null, null, null, null,
                            null, null, null, null, null, null, null, null, null,
                            null, null, null));
            if ("UNCLAIMED".equalsIgnoreCase(device.getRegistrationStatus())) {
                lifecycleService.pushClaimCodeScene(device);
            }
        } catch (Exception e) {
            throw new RuntimeException("Failed to parse capabilities for device " + deviceId, e);
        }
    }

    // ── Internal ──

    private void requireOwned(String deviceId) {
        deviceRepository.findById(deviceId)
                .filter(d -> currentSpaceId().equals(d.getOwnerSpaceId()))
                .orElseThrow(() -> new IllegalArgumentException("device not found or not owned"));
    }

    public String currentSpaceId() {
        String spaceId = GlobalContext.getSpaceId();
        if (spaceId == null || spaceId.isBlank()) {
            throw new IllegalStateException("space_id is required");
        }
        return spaceId;
    }

    // ── JSON helpers ──

    private static Integer optInt(JsonNode node, String field) {
        JsonNode child = node.path(field);
        return child.isMissingNode() || child.isNull() ? null : child.asInt();
    }

    private static Double optDouble(JsonNode node, String field) {
        JsonNode child = node.path(field);
        return child.isMissingNode() || child.isNull() ? null : child.asDouble();
    }

    private static String optString(JsonNode node, String field) {
        JsonNode child = node.path(field);
        return child.isMissingNode() || child.isNull() ? null : child.asText();
    }

    private static Boolean optBoolean(JsonNode node, String field) {
        JsonNode child = node.path(field);
        return child.isMissingNode() || child.isNull() ? null : child.asBoolean();
    }
}
