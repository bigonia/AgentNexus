package com.zwbd.agentnexus.sdui.protocol;

public enum SemanticCommand {
    // Template
    DISPLAY_TEMPLATE_RENDER("display.template.render"),
    DISPLAY_TEMPLATE_PATCH("display.template.patch"),
    // Section
    DISPLAY_SECTION_RENDER("display.section.render"),
    DISPLAY_SECTION_PATCH("display.section.patch"),
    // Legacy layout (replaced by above, kept for capability matching)
    DISPLAY_LAYOUT_RENDER("display.layout.render"),
    DISPLAY_LAYOUT_PATCH("display.layout.patch"),
    // Actuator
    DISPLAY_BRIGHTNESS_SET("display.brightness.set"),
    AUDIO_PROMPT_PLAY("audio.prompt.play"),
    AUDIO_STREAM_PLAY("audio.stream.play"),
    AUDIO_VOLUME_SET("audio.volume.set"),
    RGB_EFFECT_SET("rgb.effect.set"),
    RGB_OFF("rgb.off"),
    DEVICE_REBOOT("device.reboot");

    private final String name;

    SemanticCommand(String name) {
        this.name = name;
    }

    public String getName() {
        return name;
    }

    public static SemanticCommand fromName(String name) {
        for (SemanticCommand cmd : values()) {
            if (cmd.name.equals(name)) {
                return cmd;
            }
        }
        return null;
    }
}
