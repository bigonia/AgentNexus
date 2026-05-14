package com.zwbd.agentnexus.sdui.service;

import com.zwbd.agentnexus.common.web.GlobalContext;
import com.zwbd.agentnexus.sdui.model.SduiDevice;
import com.zwbd.agentnexus.sdui.model.SduiDeviceTelemetry;
import com.zwbd.agentnexus.sdui.repo.SduiDeviceRepository;
import com.zwbd.agentnexus.sdui.repo.SduiDeviceTelemetryRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;

@Service
@RequiredArgsConstructor
public class DeviceQueryService {

    private final SduiDeviceRepository deviceRepository;
    private final SduiDeviceTelemetryRepository telemetryRepository;

    @Transactional(readOnly = true)
    public List<SduiDevice> listDevices(String spaceId) {
        return deviceRepository.findByOwnerSpaceId(spaceId);
    }

    @Transactional(readOnly = true)
    public List<SduiDevice> listUnclaimedDevices() {
        return deviceRepository.findByRegistrationStatus("UNCLAIMED");
    }

    @Transactional(readOnly = true)
    public Optional<SduiDevice> getDevice(String deviceId, String spaceId) {
        return deviceRepository.findById(deviceId)
                .filter(d -> spaceId.equals(d.getOwnerSpaceId()));
    }

    @Transactional(readOnly = true)
    public List<SduiDeviceTelemetry> getTelemetry(String deviceId, int limit) {
        return telemetryRepository.findTop50ByDeviceIdOrderByCreatedAtDesc(deviceId);
    }

    @Transactional(readOnly = true)
    public long countDevices(String spaceId) {
        return deviceRepository.countByOwnerSpaceId(spaceId);
    }

    @Transactional(readOnly = true)
    public long countByStatus(String spaceId, String status) {
        return deviceRepository.countByOwnerSpaceIdAndStatusIgnoreCase(spaceId, status);
    }

    public String currentSpaceId() {
        String spaceId = GlobalContext.getSpaceId();
        if (spaceId == null || spaceId.isBlank()) {
            throw new IllegalStateException("space_id is required");
        }
        return spaceId;
    }
}
