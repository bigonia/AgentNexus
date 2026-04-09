package com.zwbd.agentnexus.sdui.dto;

import jakarta.validation.constraints.NotBlank;

public record SduiDeviceControlRequest(
        @NotBlank(message = "command must not be blank")
        String command,
        Object value
) {
}

