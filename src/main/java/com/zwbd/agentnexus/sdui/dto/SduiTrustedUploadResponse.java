package com.zwbd.agentnexus.sdui.dto;

public record SduiTrustedUploadResponse(
        String appId,
        String versionId,
        Integer versionNo,
        String versionLabel,
        String runtime,
        String entrypoint,
        String validationReport,
        String packageUri
) {
}
