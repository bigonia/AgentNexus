package com.zwbd.agentnexus.sdui.dto;

import jakarta.validation.constraints.NotEmpty;

import java.util.List;

public record SduiRollbackRequest(
        @NotEmpty(message = "deviceIds must not be empty")
        List<String> deviceIds
) {
}
