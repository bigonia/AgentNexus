package com.zwbd.agentnexus.sdui.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.zwbd.agentnexus.sdui.capability.CapabilityCatalog;
import com.zwbd.agentnexus.sdui.capability.CapabilityRegistry;
import com.zwbd.agentnexus.sdui.model.SduiDevice;
import com.zwbd.agentnexus.sdui.protocol.CapabilitySchema;
import com.zwbd.agentnexus.sdui.protocol.CapabilitySnapshotParser;
import com.zwbd.agentnexus.sdui.repo.SduiDeviceRepository;
import com.zwbd.agentnexus.sdui.workflow.node.CapabilityNodeRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

@Slf4j
@Service
public class SduiCapabilityService {

    private final SduiDeviceRepository deviceRepository;
    private final ObjectMapper objectMapper;
    private final CommandSchemaRegistry schemaRegistry;
    private final CapabilityNodeRegistry nodeRegistry;
    private final CapabilityRegistry capabilityRegistry;
    private final CapabilityCatalog catalog;
    private final Map<String, CapabilitySchema.CapabilitySnapshot> cache = new ConcurrentHashMap<>();

    public SduiCapabilityService(SduiDeviceRepository deviceRepository,
                                  ObjectMapper objectMapper,
                                  CommandSchemaRegistry schemaRegistry,
                                  @Lazy CapabilityNodeRegistry nodeRegistry,
                                  CapabilityRegistry capabilityRegistry,
                                  CapabilityCatalog catalog) {
        this.deviceRepository = deviceRepository;
        this.objectMapper = objectMapper;
        this.schemaRegistry = schemaRegistry;
        this.nodeRegistry = nodeRegistry;
        this.capabilityRegistry = capabilityRegistry;
        this.catalog = catalog;
    }

    @Transactional
    public void onCapabilitiesReport(String deviceId, CapabilitySchema.CapabilitySnapshot caps, String rawJson) {
        cache.put(deviceId, caps);
        schemaRegistry.loadFromCapabilities(deviceId, caps);
        SduiDevice device = deviceRepository.findById(deviceId).orElse(null);
        if (device != null) {
            device.setCapabilitiesSnapshot(rawJson);
            deviceRepository.save(device);
        }
        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> capsMap = objectMapper.readValue(rawJson, Map.class);
            nodeRegistry.registerDeviceNodes(deviceId, capsMap);
        } catch (Exception e) {
            log.warn("Failed to register device nodes for {}: {}", deviceId, e.getMessage());
        }
        capabilityRegistry.onDeviceReport(deviceId, caps);
        log.info("Capabilities stored for device {}, hasDisplay={}", deviceId, caps.display() != null);
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
            schemaRegistry.loadFromCapabilities(deviceId, caps);
            return Optional.of(caps);
        } catch (Exception e) {
            log.error("Failed to parse capabilities for device {}", deviceId, e);
            return Optional.empty();
        }
    }

    public boolean supportsSectionType(String deviceId, String sectionType) {
        return getCapabilities(deviceId)
                .map(CapabilitySchema.CapabilitySnapshot::display)
                .map(d -> d.supportsType(sectionType))
                .orElse(false);
    }

    public boolean supportsSectionLayout(String deviceId, String layout) {
        return getCapabilities(deviceId)
                .map(CapabilitySchema.CapabilitySnapshot::display)
                .map(d -> d.supportsLayout(layout))
                .orElse(false);
    }

    public int getSectionLimit(String deviceId, String limitKey) {
        return getCapabilities(deviceId)
                .map(caps -> {
                    String sizeClass = caps.display() != null ? caps.display().effectiveSizeClass() : "large";
                    return catalog.getDisplayLimits(sizeClass).getOrDefault(limitKey, 0);
                })
                .orElse(0);
    }

    public Optional<CapabilitySchema.DisplayInfo> getSectionCapability(String deviceId) {
        return getCapabilities(deviceId)
                .map(CapabilitySchema.CapabilitySnapshot::display);
    }

    public Set<String> getAvailableCommands(String deviceId) {
        return getCapabilities(deviceId)
                .map(caps -> catalog.getAllCommands(new LinkedHashSet<>(caps.outputs())))
                .orElse(Collections.emptySet());
    }

    public void clearCapabilitiesCache(String deviceId) {
        cache.remove(deviceId);
        schemaRegistry.clearDeviceSchemas(deviceId);
        nodeRegistry.unregisterDeviceNodes(deviceId);
        capabilityRegistry.removeDevice(deviceId);
        log.info("Capabilities cache cleared for device {}", deviceId);
    }

    public record CommandRoute(String topic, String action, boolean bareTopic) {
        public boolean isActionTopic() { return !bareTopic; }
    }

    public CommandRoute resolveRoute(String deviceId, String command) {
        CapabilityCatalog.CommandDef cmdDef = catalog.getCommand(command).orElse(null);
        if (cmdDef != null) {
            return new CommandRoute(cmdDef.topic(), cmdDef.action(), cmdDef.action() == null);
        }
        return new CommandRoute("cmd/control", command, false);
    }
}
