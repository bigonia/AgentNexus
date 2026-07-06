package com.zwbd.agentnexus.sdui.controller;

import com.zwbd.agentnexus.common.web.ApiResponse;
import com.zwbd.agentnexus.sdui.capability.node.CapabilityNodeCatalog;
import com.zwbd.agentnexus.sdui.capability.node.CapabilityNodeCatalogService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/sdui/capability-nodes")
@RequiredArgsConstructor
public class CapabilityNodeController {

    private final CapabilityNodeCatalogService nodeCatalogService;

    /**
     * @deprecated 前端未使用，统一通过 {@code GET /board-types/{board}/capability-nodes} 查询。
     *             该接口预期在后续版本中移除。
     */
    @Deprecated
    @GetMapping("/{deviceId}")
    public ApiResponse<CapabilityNodeCatalog> nodes(
            @PathVariable String deviceId,
            @RequestParam(required = false) String pageId,
            @RequestParam(required = false) String pageJson) {
        return ApiResponse.ok(nodeCatalogService.buildForDevice(deviceId, pageId, pageJson));
    }
}
