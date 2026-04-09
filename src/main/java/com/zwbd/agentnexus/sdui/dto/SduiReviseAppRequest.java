package com.zwbd.agentnexus.sdui.dto;

import jakarta.validation.constraints.NotBlank;

public record SduiReviseAppRequest(
        @NotBlank(message = "instruction must not be blank")
        String instruction
) {
}

