package com.zwbd.agentnexus.sdui.dto;

import jakarta.validation.constraints.NotBlank;

public record SduiCreateTrustedAppRequest(
        @NotBlank(message = "name must not be blank")
        String name,
        @NotBlank(message = "description must not be blank")
        String description
) {
}
