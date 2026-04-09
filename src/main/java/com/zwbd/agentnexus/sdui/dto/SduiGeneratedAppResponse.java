package com.zwbd.agentnexus.sdui.dto;

public record SduiGeneratedAppResponse(
        String appId,
        String versionId,
        Integer versionNo,
        String templateName,
        String templateDescription,
        java.util.List<String> selectedAssetSetIds,
        String previewLayoutJson,
        String validationReport
) {
}
