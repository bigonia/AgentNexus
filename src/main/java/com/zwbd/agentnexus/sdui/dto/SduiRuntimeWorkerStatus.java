package com.zwbd.agentnexus.sdui.dto;

public record SduiRuntimeWorkerStatus(
        String workerId,
        String state,
        boolean healthy,
        long handledRequests,
        String lastError
) {
}

