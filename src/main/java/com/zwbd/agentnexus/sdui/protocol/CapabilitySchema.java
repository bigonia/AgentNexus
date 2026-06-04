package com.zwbd.agentnexus.sdui.protocol;

import java.util.List;

public final class CapabilitySchema {

    private CapabilitySchema() {}

    public record CapabilitySnapshot(
            String schemaVersion,
            String protocolVersion,
            String board,
            ScreenInfo screen,
            String inputMode,
            List<String> inputs,
            List<String> outputs,
            DisplayInfo display
    ) {}

    public record ScreenInfo(int w, int h, String shape) {}

    public record DisplayInfo(
            String transport,
            String sizeClass,
            List<String> sectionTypes,
            List<String> layouts
    ) {
        public boolean supportsType(String type) {
            return sectionTypes != null && sectionTypes.contains(type);
        }

        public boolean supportsLayout(String layout) {
            return layouts != null && layouts.contains(layout);
        }

        /** Effective size class, defaulting to {@code "large"}. */
        public String effectiveSizeClass() {
            return sizeClass != null && !sizeClass.isBlank() ? sizeClass : "large";
        }

        /** @return true if the device should use compact rendering (small screen). */
        public boolean isCompact() {
            return "small".equalsIgnoreCase(sizeClass);
        }

        /** @return true if the device should use rich rendering (large screen). */
        public boolean isRich() {
            return !isCompact();
        }
    }
}
