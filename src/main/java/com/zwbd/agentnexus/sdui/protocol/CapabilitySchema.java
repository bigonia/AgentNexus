package com.zwbd.agentnexus.sdui.protocol;

import java.util.List;
import java.util.Map;

public final class CapabilitySchema {

    private CapabilitySchema() {}

    public record CapabilitySnapshot(
            DeviceProfile deviceProfile,
            List<InputCapability> inputs,
            List<OutputCapability> outputs,
            SectionCapability section
    ) {}

    public record DeviceProfile(
            String shape,
            int screenW,
            int screenH,
            String inputMode,
            boolean autoSleepByInactive
    ) {}

    public record InputCapability(
            String name,
            String module,
            boolean enabled,
            List<String> events
    ) {}

    public record OutputCapability(
            String name,
            String capability,
            String module,
            boolean enabled,
            List<String> commands,
            List<String> legacyTopics
    ) {}

    public record SectionCapability(
            boolean enabled,
            List<String> commands,
            String transport,
            List<String> supportedSectionTypes,
            List<String> supportedLayouts,
            Map<String, Integer> limits
    ) {
        public boolean supportsType(String type) {
            return supportedSectionTypes != null && supportedSectionTypes.contains(type);
        }

        public boolean supportsLayout(String layout) {
            return supportedLayouts != null && supportedLayouts.contains(layout);
        }

        public int getLimit(String key) {
            return limits != null ? limits.getOrDefault(key, 0) : 0;
        }
    }
}
