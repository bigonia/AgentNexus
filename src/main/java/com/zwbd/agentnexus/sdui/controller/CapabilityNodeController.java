package com.zwbd.agentnexus.sdui.controller;

import com.zwbd.agentnexus.common.web.ApiResponse;
import com.zwbd.agentnexus.sdui.capability.node.CapabilityNodeCatalog;
import com.zwbd.agentnexus.sdui.capability.node.CapabilityNodeCatalogService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/sdui/capability-nodes")
@RequiredArgsConstructor
public class CapabilityNodeController {

    private final CapabilityNodeCatalogService nodeCatalogService;

    @GetMapping("/{deviceId}")
    public ApiResponse<CapabilityNodeCatalog> nodes(@PathVariable String deviceId) {
        return ApiResponse.ok(nodeCatalogService.buildForDevice(deviceId));
    }
}
