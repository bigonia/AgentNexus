package com.zwbd.agentnexus.sdui.protocol;

// LCD_085 refactor (2026-09-18): legacy protocol path, scheduled for removal.
// Replaced by: sdui.v2.protocol.V2Names
// Kept only so un-migrated devices keep working; delete once the terminal rolls over to v2.
// See docs/sdui/DELIVERY_CHECKLIST.md section 3, P3.
@Deprecated(since = "0.10.0")
public final class SduiProtocolConstants {

    private SduiProtocolConstants() {}

    public static final class Topics {
        public static final String COMMAND_CONTROL = "cmd/control";
        public static final String COMMAND_CONTROL_ACK = "cmd/control_ack";

        private Topics() {}
    }

    public static final class NodeProtocols {
        public static final String COMMAND_CONTROL = "cmd/control";
        public static final String SECTION_SCENE = "section_scene";
        public static final String SECTION_PATCH = "section_patch";
        public static final String SERVER_AUDIO = "server_audio";

        private NodeProtocols() {}
    }
}
