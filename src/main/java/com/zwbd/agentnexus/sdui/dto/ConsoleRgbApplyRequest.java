package com.zwbd.agentnexus.sdui.dto;

import jakarta.validation.constraints.NotBlank;

public record ConsoleRgbApplyRequest(
        @NotBlank(message = "mode must not be blank")
        String mode,
        String color,
        Integer periodMs
) {}
