package com.zwbd.agentnexus.sdui.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.zwbd.agentnexus.sdui.model.SduiDevice;
import com.zwbd.agentnexus.sdui.protocol.CapabilitySchema;
import com.zwbd.agentnexus.sdui.protocol.CapabilitySnapshotParser;
import com.zwbd.agentnexus.sdui.repo.SduiDeviceRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

@Slf4j
@Service
@RequiredArgsConstructor
public class SduiCapabilityService {

    private final SduiDeviceRepository deviceRepository;
    private final ObjectMapper objectMapper;
    private final Map<String, CapabilitySchema.CapabilitySnapshot> cache = new ConcurrentHashMap<>();

    @Transactional
    public void onCapabilitiesReport(String deviceId, CapabilitySchema.CapabilitySnapshot caps, String rawJson) {
        cache.put(deviceId, caps);
        SduiDevice device = deviceRepository.findById(deviceId).orElse(null);
        if (device != null) {
            device.setCapabilitiesSnapshot(rawJson);
            deviceRepository.save(device);
        }
        log.info("Capabilities stored for device {}, sectionEnabled={}", deviceId,
                caps.section() != null && caps.section().enabled());
    }

    public Optional<CapabilitySchema.CapabilitySnapshot> getCapabilities(String deviceId) {
        CapabilitySchema.CapabilitySnapshot cached = cache.get(deviceId);
        if (cached != null) return Optional.of(cached);

        SduiDevice device = deviceRepository.findById(deviceId).orElse(null);
        if (device == null || device.getCapabilitiesSnapshot() == null) return Optional.empty();
        try {
            CapabilitySchema.CapabilitySnapshot caps = CapabilitySnapshotParser.parse(
                    device.getCapabilitiesSnapshot(), objectMapper);
            cache.put(deviceId, caps);
            return Optional.of(caps);
        } catch (Exception e) {
            log.error("Failed to parse capabilities for device {}", deviceId, e);
            return Optional.empty();
        }
    }

    public boolean supportsSectionType(String deviceId, String sectionType) {
        return getCapabilities(deviceId)
                .map(CapabilitySchema.CapabilitySnapshot::section)
                .filter(CapabilitySchema.SectionCapability::enabled)
                .map(s -> s.supportsType(sectionType))
                .orElse(false);
    }

    public boolean supportsSectionLayout(String deviceId, String layout) {
        return getCapabilities(deviceId)
                .map(CapabilitySchema.CapabilitySnapshot::section)
                .filter(CapabilitySchema.SectionCapability::enabled)
                .map(s -> s.supportsLayout(layout))
                .orElse(false);
    }

    public int getSectionLimit(String deviceId, String limitKey) {
        return getCapabilities(deviceId)
                .map(CapabilitySchema.CapabilitySnapshot::section)
                .map(s -> s.getLimit(limitKey))
                .orElse(0);
    }

    public Optional<CapabilitySchema.SectionCapability> getSectionCapability(String deviceId) {
        return getCapabilities(deviceId)
                .map(CapabilitySchema.CapabilitySnapshot::section)
                .filter(CapabilitySchema.SectionCapability::enabled);
    }

    public Set<String> getAvailableCommands(String deviceId) {
        return getCapabilities(deviceId)
                .map(caps -> {
                    Set<String> cmds = new LinkedHashSet<>();
                    for (CapabilitySchema.OutputCapability o : caps.outputs()) {
                        if (o.enabled() && o.commands() != null) cmds.addAll(o.commands());
                    }
                    return cmds;
                }).orElse(Collections.emptySet());
    }

    public CapabilitySchema.DeviceProfile getDeviceProfile(String deviceId) {
        return getCapabilities(deviceId)
                .map(CapabilitySchema.CapabilitySnapshot::deviceProfile)
                .orElse(null);
    }
}
