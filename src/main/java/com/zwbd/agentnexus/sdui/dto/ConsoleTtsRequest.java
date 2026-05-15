package com.zwbd.agentnexus.sdui.dto;

import jakarta.validation.constraints.NotBlank;

public record ConsoleTtsRequest(
        @NotBlank(message = "text must not be blank")
        String text
) {}
