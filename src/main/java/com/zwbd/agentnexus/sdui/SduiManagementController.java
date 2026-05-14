package com.zwbd.agentnexus.sdui;

import com.zwbd.agentnexus.common.web.ApiResponse;
import com.zwbd.agentnexus.sdui.dto.SduiClaimDeviceRequest;
import com.zwbd.agentnexus.sdui.dto.SduiControlDispatchResult;
import com.zwbd.agentnexus.sdui.dto.SduiDeviceControlRequest;
import com.zwbd.agentnexus.sdui.dto.SduiDeviceDetailResponse;
import com.zwbd.agentnexus.sdui.model.SduiDevice;
import com.zwbd.agentnexus.sdui.model.SduiDeviceTelemetry;
import com.zwbd.agentnexus.sdui.protocol.CapabilitySchema;
import com.zwbd.agentnexus.sdui.service.SduiCapabilityService;
import com.zwbd.agentnexus.sdui.service.SduiDeviceService;
import com.zwbd.agentnexus.sdui.service.SduiOpsService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;
import java.util.Optional;

@RestController
@RequestMapping("/api/v1/sdui")
@RequiredArgsConstructor
public class SduiManagementController {

    private final SduiDeviceService deviceService;
    private final SduiCapabilityService capabilityService;
    private final SduiOpsService opsService;

    @GetMapping("/devices")
    public ApiResponse<List<SduiDevice>> devices() {
        return ApiResponse.ok(deviceService.listDevices());
    }

    @GetMapping("/devices/unclaimed")
    public ApiResponse<List<SduiDevice>> unclaimedDevices() {
        return ApiResponse.ok(deviceService.listUnclaimedDevices());
    }

    @GetMapping("/devices/{deviceId}")
    public ApiResponse<SduiDeviceDetailResponse> deviceDetail(@PathVariable String deviceId) {
        Optional<SduiDevice> deviceOpt = deviceService.getDevice(deviceId);
        if (deviceOpt.isEmpty()) {
            return ApiResponse.error(40400, "device not found");
        }
        SduiDevice d = deviceOpt.get();
        CapabilitySchema.DeviceProfile profile = capabilityService.getDeviceProfile(deviceId);
        SduiDeviceDetailResponse detail = SduiDeviceDetailResponse.builder()
                .deviceId(d.getDeviceId())
                .name(d.getName())
                .status(d.getStatus())
                .registrationStatus(d.getRegistrationStatus())
                .screenShape(profile != null ? profile.shape() : null)
                .screenWidth(profile != null ? profile.screenW() : 0)
                .screenHeight(profile != null ? profile.screenH() : 0)
                .inputMode(profile != null ? profile.inputMode() : null)
                .availableCommands(capabilityService.getAvailableCommands(deviceId))
                .capabilitiesSnapshot(d.getCapabilitiesSnapshot())
                .lastSeenAt(d.getLastSeenAt())
                .claimedAt(d.getClaimedAt())
                .createdAt(d.getCreatedAt())
                .build();
        return ApiResponse.ok(detail);
    }

    @PostMapping("/devices/{deviceId}/claim")
    public ApiResponse<SduiDevice> claimDevice(@PathVariable String deviceId,
                                                @Valid @RequestBody SduiClaimDeviceRequest request) {
        return ApiResponse.ok(deviceService.claimDevice(deviceId, request.claimCode(), request.deviceName()));
    }

    @GetMapping("/devices/{deviceId}/telemetry")
    public ApiResponse<List<SduiDeviceTelemetry>> telemetry(@PathVariable String deviceId) {
        return ApiResponse.ok(deviceService.telemetry(deviceId));
    }

    @PostMapping("/devices/{deviceId}/control")
    public ApiResponse<Map<String, Object>> control(@PathVariable String deviceId,
                                                     @Valid @RequestBody SduiDeviceControlRequest request) {
        SduiControlDispatchResult result = deviceService.controlDevice(deviceId, request);
        return ApiResponse.ok(Map.of(
                "sent", result.sent(),
                "deviceId", deviceId,
                "command", result.action(),
                "cmdId", result.cmdId(),
                "requestedValue", result.requestedValue(),
                "status", result.status()
        ));
    }

    @GetMapping("/ops/overview")
    public ApiResponse<Map<String, Object>> opsOverview() {
        return ApiResponse.ok(opsService.overview());
    }
}
