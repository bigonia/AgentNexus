package com.zwbd.agentnexus.sdui.protocol.catalog;

import com.zwbd.agentnexus.sdui.protocol.CapabilitySchema;
import com.zwbd.agentnexus.sdui.service.SduiCapabilityService;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;

import java.util.*;

@Service
public class DeviceCapabilityProjection {

    private final DeviceProtocolCatalog catalog;
    private final SduiCapabilityService capabilityService;

    public DeviceCapabilityProjection(DeviceProtocolCatalog catalog,
                                      @Lazy SduiCapabilityService capabilityService) {
        this.catalog = catalog;
        this.capabilityService = capabilityService;
    }

    public List<CommandSpec> commands(String deviceId) {
        Set<String> supported = capabilityService.getAvailableCommands(deviceId);
        return catalog.commands().stream()
                .filter(command -> supported.contains(command.id()))
                .toList();
    }

    public List<SectionSpec> sections(String deviceId) {
        Optional<CapabilitySchema.CapabilitySnapshot> capsOpt = capabilityService.getCapabilities(deviceId);
        if (capsOpt.isEmpty() || capsOpt.get().display() == null) {
            return List.of();
        }
        Set<String> supported = new LinkedHashSet<>(capsOpt.get().display().sectionTypes());
        return catalog.sections().stream()
                .filter(section -> supported.contains(section.type()))
                .toList();
    }

    public List<EventSpec> events(String deviceId) {
        Optional<CapabilitySchema.CapabilitySnapshot> capsOpt = capabilityService.getCapabilities(deviceId);
        if (capsOpt.isEmpty()) {
            return List.of();
        }

        CapabilitySchema.CapabilitySnapshot caps = capsOpt.get();
        Set<String> allowedSources = new LinkedHashSet<>(caps.inputs());
        Set<String> supportedSectionTypes = caps.display() != null
                ? new LinkedHashSet<>(caps.display().sectionTypes()) : Set.of();

        List<EventSpec> result = new ArrayList<>();
        for (EventSpec event : catalog.events()) {
            if (event.id().startsWith("ui:")) {
                if (supportedSectionTypes.contains(event.source())) {
                    result.add(event);
                }
                continue;
            }
            if (allowedSources.contains(event.source())) {
                result.add(event);
            }
        }
        return result;
    }

    public Map<String, Object> protocol(String deviceId) {
        return Map.of(
                "deviceId", deviceId,
                "commands", commands(deviceId).stream().map(CommandSpec::toMap).toList(),
                "sections", sections(deviceId).stream().map(SectionSpec::toMap).toList(),
                "events", events(deviceId).stream().map(EventSpec::toMap).toList()
        );
    }
}
