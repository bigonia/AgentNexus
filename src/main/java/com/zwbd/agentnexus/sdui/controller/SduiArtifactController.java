package com.zwbd.agentnexus.sdui.controller;

import com.zwbd.agentnexus.common.web.ApiResponse;
import com.zwbd.agentnexus.sdui.artifact.SduiArtifactEntity;
import com.zwbd.agentnexus.sdui.artifact.SduiArtifactService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/v1/sdui")
@RequiredArgsConstructor
public class SduiArtifactController {

    private final SduiArtifactService artifactService;

    @GetMapping("/artifacts/{artifactId}")
    public ApiResponse<Map<String, Object>> artifact(@PathVariable String artifactId) {
        try {
            return ApiResponse.ok(artifactService.toMap(artifactService.require(artifactId)));
        } catch (IllegalArgumentException e) {
            return ApiResponse.error(40400, e.getMessage());
        }
    }

    @GetMapping("/artifacts/{artifactId}/blob")
    public ResponseEntity<byte[]> artifactBlob(@PathVariable String artifactId) {
        SduiArtifactEntity artifact;
        try {
            artifact = artifactService.require(artifactId);
        } catch (IllegalArgumentException e) {
            return ResponseEntity.notFound().build();
        }
        if (artifact.getBlob() == null) {
            return ResponseEntity.notFound().build();
        }
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.parseMediaType(artifact.getMimeType()));
        headers.setContentDispositionFormData("inline", artifact.getArtifactId());
        headers.setContentLength(artifact.getBlob().length);
        return ResponseEntity.ok().headers(headers).body(artifact.getBlob());
    }

    @GetMapping("/devices/{deviceId}/artifacts/latest")
    public ApiResponse<Map<String, Object>> latestDeviceArtifact(@PathVariable String deviceId,
                                                                 @RequestParam(defaultValue = SduiArtifactService.AUDIO_RECORDING) String type) {
        return artifactService.findLatest(deviceId, type)
                .map(artifact -> ApiResponse.ok(artifactService.toMap(artifact)))
                .orElseGet(() -> ApiResponse.error(40400, "artifact not found"));
    }
}
