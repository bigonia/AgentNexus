package com.zwbd.agentnexus.sdui.service;

import com.zwbd.agentnexus.sdui.model.SduiDevice;
import com.zwbd.agentnexus.sdui.repo.SduiDeviceRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

@Service
@RequiredArgsConstructor
public class ClaimService {

    private final SduiDeviceRepository deviceRepository;

    @Transactional
    public SduiDevice claimDevice(String deviceId, String claimCode, String deviceName, String userId) {
        SduiDevice device = deviceRepository.findById(deviceId)
                .orElseThrow(() -> new IllegalArgumentException("device not found"));

        if (claimCode == null || !claimCode.trim().equalsIgnoreCase(
                device.getClaimCode() == null ? "" : device.getClaimCode())) {
            throw new IllegalArgumentException("invalid claim code");
        }
        if (device.getClaimCodeExpireAt() != null
                && LocalDateTime.now().isAfter(device.getClaimCodeExpireAt())) {
            throw new IllegalArgumentException("claim code expired");
        }
        if (device.getOwnerUserId() != null && !device.getOwnerUserId().isBlank()
                && !userId.equals(device.getOwnerUserId())) {
            throw new IllegalArgumentException("device already claimed by another user");
        }

        device.setOwnerUserId(userId);
        device.setRegistrationStatus("CLAIMED");
        device.setClaimedAt(LocalDateTime.now());
        device.setClaimCode(null);
        device.setClaimCodeExpireAt(null);
        if (deviceName != null && !deviceName.isBlank()) {
            device.setName(deviceName.trim());
        }
        return deviceRepository.save(device);
    }
}
