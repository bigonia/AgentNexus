package com.zwbd.agentnexus.sdui.dto;

public record SduiControlDispatchResult(
        String cmdId,
        String action,
        Integer requestedValue,
        boolean sent,
        String status
) {
}

