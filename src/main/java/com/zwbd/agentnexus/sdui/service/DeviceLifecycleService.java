package com.zwbd.agentnexus.sdui.service;

import com.zwbd.agentnexus.sdui.model.SduiDevice;
import com.zwbd.agentnexus.sdui.model.SduiDeviceTelemetry;
import com.zwbd.agentnexus.sdui.protocol.BinaryProtocolCodec.DecodedFrame;
import com.zwbd.agentnexus.sdui.repo.SduiDeviceRepository;
import com.zwbd.agentnexus.sdui.repo.SduiDeviceTelemetryRepository;
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

    private static final long OFFLINE_TIMEOUT_SECONDS = 90L;
    private static final long CLAIM_CODE_TTL_MINUTES = 15L;

    @Transactional
    public void onHello(String deviceId, DecodedFrame frame) {
        SduiDevice device = deviceRepository.findById(deviceId).orElseGet(() -> {
            SduiDevice d = new SduiDevice();
            d.setDeviceId(deviceId);
            d.setName("device-" + deviceId);
            d.setOwnerSpaceId("");
            d.setRegistrationStatus("UNCLAIMED");
            return d;
        });
        device.setStatus("ONLINE");
        device.setLastSeenAt(LocalDateTime.now());
        deviceRepository.save(device);
    }

    @Transactional
    public SduiDevice onHeartbeat(String deviceId, SduiHeartbeatData data) {
        SduiDevice device = deviceRepository.findById(deviceId).orElseGet(() -> {
            SduiDevice d = new SduiDevice();
            d.setDeviceId(deviceId);
            d.setName("device-" + deviceId);
            d.setOwnerSpaceId("");
            d.setRegistrationStatus("UNCLAIMED");
            return d;
        });

        device.setStatus("ONLINE");
        device.setLastSeenAt(LocalDateTime.now());

        if (!isClaimed(device)) {
            device.setRegistrationStatus("UNCLAIMED");
            if (device.getClaimCode() == null || isClaimCodeExpired(device)) {
                issueClaimCode(device);
            }
        }

        device = deviceRepository.save(device);

        if (isClaimed(device)) {
            SduiDeviceTelemetry telemetry = new SduiDeviceTelemetry();
            telemetry.setDeviceId(deviceId);
            telemetry.setWifiRssi(data.wifiRssi());
            telemetry.setTemperature(data.temperature());
            telemetry.setFreeHeapInternal(data.freeHeapInternal());
            telemetry.setFreeHeapTotal(data.freeHeapTotal());
            telemetry.setUptimeS(data.uptimeS());
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

    private boolean isClaimed(SduiDevice device) {
        return device.getOwnerSpaceId() != null && !device.getOwnerSpaceId().isBlank();
    }

    public record SduiHeartbeatData(
            Integer wifiRssi, Double temperature,
            Integer freeHeapInternal, Integer freeHeapTotal, Integer uptimeS
    ) {}
}
