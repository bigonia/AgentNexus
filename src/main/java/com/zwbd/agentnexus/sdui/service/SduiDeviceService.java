package com.zwbd.agentnexus.sdui.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.zwbd.agentnexus.sdui.dto.SduiControlDispatchResult;
import com.zwbd.agentnexus.sdui.dto.SduiDeviceControlRequest;
import com.zwbd.agentnexus.sdui.model.SduiDevice;
import com.zwbd.agentnexus.sdui.model.SduiDeviceTelemetry;
import com.zwbd.agentnexus.sdui.protocol.CapabilitySchema;
import com.zwbd.agentnexus.sdui.protocol.CapabilitySnapshotParser;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;

@Service
@RequiredArgsConstructor
public class SduiDeviceService {

    private final DeviceLifecycleService lifecycleService;
    private final DeviceQueryService queryService;
    private final CommandService commandService;
    private final ClaimService claimService;
    private final SduiCapabilityService capabilityService;
    private final ObjectMapper objectMapper;

    public SduiDevice onHeartbeat(String deviceId, JsonNode payload) {
        return lifecycleService.onHeartbeat(deviceId, new DeviceLifecycleService.SduiHeartbeatData(
                payload.path("wifi_rssi").isMissingNode() ? null : payload.path("wifi_rssi").asInt(),
                payload.path("temperature").isMissingNode() ? null : payload.path("temperature").asDouble(),
                payload.path("free_heap_internal").isMissingNode() ? null : payload.path("free_heap_internal").asInt(),
                payload.path("free_heap_total").isMissingNode() ? null : payload.path("free_heap_total").asInt(),
                payload.path("uptime_s").isMissingNode() ? null : payload.path("uptime_s").asInt()
        ));
    }

    public void updateCurrentPage(String deviceId, String pageId) {
        lifecycleService.updateCurrentPage(deviceId, pageId);
    }

    public long countDevices() { return queryService.countDevices(currentSpaceId()); }
    public long countOnlineDevices() { return queryService.countByStatus(currentSpaceId(), "ONLINE"); }
    public long countOfflineDevices() { return queryService.countByStatus(currentSpaceId(), "OFFLINE"); }

    public List<SduiDevice> listDevices() {
        lifecycleService.refreshOnlineStatus();
        return queryService.listDevices(currentSpaceId());
    }

    public List<SduiDevice> listUnclaimedDevices() {
        return queryService.listUnclaimedDevices();
    }

    public Optional<SduiDevice> getDevice(String deviceId) {
        lifecycleService.refreshOnlineStatus();
        return queryService.getDevice(deviceId, currentSpaceId());
    }

    public List<SduiDeviceTelemetry> telemetry(String deviceId) {
        requireOwned(deviceId);
        return queryService.getTelemetry(deviceId, 50);
    }

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

    public SduiDevice claimDevice(String deviceId, String claimCode, String deviceName) {
        return claimService.claimDevice(deviceId, claimCode, deviceName, currentSpaceId());
    }

    @Transactional
    public void handleCapabilitiesReport(String deviceId, JsonNode payload) {
        try {
            String rawJson = payload.toString();
            CapabilitySchema.CapabilitySnapshot caps = CapabilitySnapshotParser.parse(rawJson, objectMapper);
            capabilityService.onCapabilitiesReport(deviceId, caps, rawJson);
        } catch (Exception e) {
            throw new RuntimeException("Failed to parse capabilities for device " + deviceId, e);
        }
    }

    private void requireOwned(String deviceId) {
        queryService.getDevice(deviceId, currentSpaceId())
                .orElseThrow(() -> new IllegalArgumentException("device not found or not owned"));
    }

    private String currentSpaceId() { return queryService.currentSpaceId(); }
}
