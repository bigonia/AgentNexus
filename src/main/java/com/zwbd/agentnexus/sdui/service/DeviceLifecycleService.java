package com.zwbd.agentnexus.sdui.service;

import com.zwbd.agentnexus.sdui.model.SduiDevice;
import com.zwbd.agentnexus.sdui.model.SduiDeviceTelemetry;
import com.zwbd.agentnexus.sdui.repo.SduiDeviceRepository;
import com.zwbd.agentnexus.sdui.repo.SduiDeviceTelemetryRepository;
import com.zwbd.agentnexus.sdui.section.SectionOrchestrationService;
import com.zwbd.agentnexus.sdui.section.SectionPresets;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class DeviceLifecycleService {

    private final SduiDeviceRepository deviceRepository;
    private final SduiDeviceTelemetryRepository telemetryRepository;
    private final SectionOrchestrationService orchestrationService;

    private static final long OFFLINE_TIMEOUT_SECONDS = 90L;
    private static final long CLAIM_CODE_TTL_MINUTES = 15L;

    @Transactional
    public SduiDevice onHeartbeat(String deviceId, SduiHeartbeatData data) {
        SduiDevice device = deviceRepository.findById(deviceId).orElseGet(() -> {
            SduiDevice d = new SduiDevice();
            d.setDeviceId(deviceId);
            d.setName("device-" + deviceId);
            d.setOwnerUserId("");
            d.setRegistrationStatus("UNCLAIMED");
            return d;
        });

        device.setStatus("ONLINE");
        device.setLastSeenAt(LocalDateTime.now());

        boolean codeRefreshed = false;
        if (!isClaimed(device)) {
            device.setRegistrationStatus("UNCLAIMED");
            if (device.getClaimCode() == null || isClaimCodeExpired(device)) {
                issueClaimCode(device);
                codeRefreshed = true;
            }
        }

        device = deviceRepository.save(device);

        if (codeRefreshed) {
            pushClaimCodeScene(device);
        }

        if (isClaimed(device)) {
            SduiDeviceTelemetry telemetry = new SduiDeviceTelemetry();
            telemetry.setDeviceId(deviceId);
            telemetry.setWifiRssi(data.wifiRssi());
            telemetry.setIp(data.ip());
            telemetry.setTemperature(data.temperature());
            telemetry.setFreeHeapInternal(data.freeHeapInternal());
            telemetry.setLargestHeapInternal(data.largestHeapInternal());
            telemetry.setFreeHeapDma(data.freeHeapDma());
            telemetry.setLargestHeapDma(data.largestHeapDma());
            telemetry.setFreeHeapPsram(data.freeHeapPsram());
            telemetry.setLargestHeapPsram(data.largestHeapPsram());
            telemetry.setFreeHeapTotal(data.freeHeapTotal());
            telemetry.setFragInternalPct(data.fragInternalPct());
            telemetry.setFragDmaPct(data.fragDmaPct());
            telemetry.setFragPsramPct(data.fragPsramPct());
            telemetry.setUptimeS(data.uptimeS());
            telemetry.setPowerSupported(data.powerSupported());
            telemetry.setBatteryMv(data.batteryMv());
            telemetry.setBatteryPct(data.batteryPct());
            telemetry.setCharging(data.charging());
            telemetry.setExtPowerPresent(data.extPowerPresent());
            telemetry.setExtPowerCtrl(data.extPowerCtrl());
            telemetry.setExtPowerOn(data.extPowerOn());
            telemetryRepository.save(telemetry);
        }
        return device;
    }

    @Transactional
    public void updateCurrentPage(String deviceId, String pageId) {
        deviceRepository.findById(deviceId).ifPresent(device -> {
            device.setCurrentPageId(pageId);
            device.setLastSeenAt(LocalDateTime.now());
            if (device.getStatus() == null) device.setStatus("ONLINE");
            deviceRepository.save(device);
        });
    }

    /**
     * Update lastSeenAt without full heartbeat processing.
     * Keeps binary-protocol devices from being marked OFFLINE
     * by refreshOnlineStatus() after the 90-second threshold.
     * Also creates the device entity and issues a claim code if
     * the device is connecting for the first time via binary protocol.
     */
    @Transactional
    public void touchDevice(String deviceId) {
        SduiDevice device = deviceRepository.findById(deviceId).orElseGet(() -> {
            SduiDevice d = new SduiDevice();
            d.setDeviceId(deviceId);
            d.setName("device-" + deviceId);
            d.setOwnerUserId("");
            d.setRegistrationStatus("UNCLAIMED");
            return d;
        });

        device.setLastSeenAt(LocalDateTime.now());
        if (!"ONLINE".equals(device.getStatus())) {
            device.setStatus("ONLINE");
        }

        // Issue claim code for unclaimed devices if needed
        if (!isClaimed(device)) {
            device.setRegistrationStatus("UNCLAIMED");
            if (device.getClaimCode() == null || isClaimCodeExpired(device)) {
                issueClaimCode(device);
            }
        }

        deviceRepository.save(device);
    }

    @Transactional
    public int refreshOnlineStatus() {
        LocalDateTime threshold = LocalDateTime.now().minusSeconds(OFFLINE_TIMEOUT_SECONDS);
        return deviceRepository.markOfflineDevices(threshold, "OFFLINE", "ONLINE");
    }

    private void issueClaimCode(SduiDevice device) {
        String code = UUID.randomUUID().toString().replace("-", "").substring(0, 6).toUpperCase();
        device.setClaimCode(code);
        device.setClaimCodeExpireAt(LocalDateTime.now().plusMinutes(CLAIM_CODE_TTL_MINUTES));
    }

    private boolean isClaimCodeExpired(SduiDevice device) {
        return device.getClaimCodeExpireAt() == null
                || LocalDateTime.now().isAfter(device.getClaimCodeExpireAt());
    }

    public void pushClaimCodeScene(SduiDevice device) {
        String code = device.getClaimCode();
        if (code == null || code.isBlank()) return;
        try {
            orchestrationService.sendScene(device.getDeviceId(),
                    SectionPresets.claimCodeScene(code));
        } catch (Exception e) {
            log.warn("Failed to push claim code scene to device {}: {}", device.getDeviceId(), e.getMessage());
        }
    }

    private boolean isClaimed(SduiDevice device) {
        return device.getOwnerUserId() != null && !device.getOwnerUserId().isBlank();
    }

    public record SduiHeartbeatData(
            // Network
            Integer wifiRssi, String ip,
            // Temperature
            Double temperature,
            // Internal SRAM
            Integer freeHeapInternal, Integer largestHeapInternal,
            // DMA
            Integer freeHeapDma, Integer largestHeapDma,
            // PSRAM
            Integer freeHeapPsram, Integer largestHeapPsram,
            // Aggregate
            Integer freeHeapTotal,
            // Fragmentation
            Integer fragInternalPct, Integer fragDmaPct, Integer fragPsramPct,
            // Uptime
            Integer uptimeS,
            // Power / Battery
            Boolean powerSupported, Integer batteryMv, Integer batteryPct,
            Boolean charging, Boolean extPowerPresent, Boolean extPowerCtrl, Boolean extPowerOn
    ) {}
}
