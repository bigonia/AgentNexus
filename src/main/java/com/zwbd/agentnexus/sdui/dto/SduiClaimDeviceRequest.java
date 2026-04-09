package com.zwbd.agentnexus.sdui.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record SduiClaimDeviceRequest(
        @NotBlank
        @Size(min = 4, max = 12)
        String claimCode,

        @Size(max = 100)
        String deviceName
) {
}
