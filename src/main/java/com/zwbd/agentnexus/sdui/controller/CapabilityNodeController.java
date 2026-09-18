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

/**
 * 设备级的能力节点目录，服务于工作流编辑器的编排（闭环步骤 ③）。
 *
 * <p>与 {@code /board-types/{board}/capability-nodes} 的分工：板型域回答"这个型号能做哪些节点"，
 * 用于尚未选定具体设备的编辑场景；本接口回答"这台设备此刻能做哪些节点"，因为可用性最终取决于
 * 该设备已上报的能力 Schema。前端编辑器在设备已绑定时走这里，未绑定时退回板型域。</p>
 */
@RestController
@RequestMapping("/api/v1/sdui/capability-nodes")
@RequiredArgsConstructor
public class CapabilityNodeController {

    private final CapabilityNodeCatalogService nodeCatalogService;

    @GetMapping("/{deviceId}")
    public ApiResponse<CapabilityNodeCatalog> nodes(
            @PathVariable String deviceId,
            @RequestParam(required = false) String pageId,
            @RequestParam(required = false) String pageJson) {
        return ApiResponse.ok(nodeCatalogService.buildForDevice(deviceId, pageId, pageJson));
    }
}
