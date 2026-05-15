package com.zwbd.agentnexus.sdui.dto;

import jakarta.validation.constraints.NotBlank;

public record ConsoleAudioPresetRequest(
        @NotBlank(message = "preset must not be blank")
        String preset
) {}
