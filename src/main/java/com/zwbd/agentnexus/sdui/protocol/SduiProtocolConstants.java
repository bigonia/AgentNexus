package com.zwbd.agentnexus.sdui.protocol;

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
