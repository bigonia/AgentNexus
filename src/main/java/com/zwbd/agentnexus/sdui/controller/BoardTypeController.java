package com.zwbd.agentnexus.sdui.controller;

import com.zwbd.agentnexus.common.web.ApiResponse;
import com.zwbd.agentnexus.sdui.capability.node.CapabilityNodeCatalog;
import com.zwbd.agentnexus.sdui.capability.node.CapabilityNodeCatalogService;
import com.zwbd.agentnexus.sdui.section.SectionTriggerCatalog;
import com.zwbd.agentnexus.sdui.section.SectionTriggerCatalogService;
import com.zwbd.agentnexus.sdui.v2.capability.CapabilityQueryService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 板型域 API。
 *
 * <p>尚未绑定具体设备时，前端仍需要回答与能力域相同的问题。实现方式是解析出一个该板型的
 * <b>代表设备</b>（优先在线），再复用同一套能力查询——板型不是独立的数据源，它只是设备的聚合视图。
 * 因此这里的每个答案都与设备级接口同源，不会出现"板型说能做、设备说不能做"。</p>
 *
 * <p>接口集定义见 {@code docs/sdui/front/CLIENT_API.md} §2.3。</p>
 */
@RestController
@RequestMapping("/api/v1/sdui/board-types")
@RequiredArgsConstructor
public class BoardTypeController {

    private final CapabilityQueryService capabilities;
    private final CapabilityNodeCatalogService nodeCatalogService;
    private final SectionTriggerCatalogService sectionTriggerCatalogService;

    /** 板型列表。未完成能力同步的设备无法归类，单独计入一个空板型条目。 */
    @GetMapping
    public ApiResponse<List<Map<String, Object>>> listBoards() {
        return ApiResponse.ok(capabilities.boards());
    }

    /** 板型详情。 */
    @GetMapping("/{board}")
    public ApiResponse<Map<String, Object>> getBoard(@PathVariable String board) {
        Optional<Map<String, Object>> detail = capabilities.board(board);
        return detail.map(ApiResponse::ok)
                .orElseGet(() -> ApiResponse.error(40400, "board not found: " + board));
    }

    /** 板型动作目录（= 代表设备的动作目录）。 */
    @GetMapping("/{board}/actions")
    public ApiResponse<Map<String, Object>> actions(@PathVariable String board) {
        return withRepresentativeDevice(board, deviceId -> ApiResponse.ok(Map.of(
                "board", board,
                "exampleDeviceId", deviceId,
                "online", capabilities.online(deviceId),
                "actions", capabilities.actions(deviceId))));
    }

    /** 板型可配置触发源目录。 */
    @GetMapping("/{board}/triggers")
    public ApiResponse<Map<String, Object>> triggers(@PathVariable String board) {
        return withRepresentativeDevice(board, deviceId -> ApiResponse.ok(Map.of(
                "board", board,
                "exampleDeviceId", deviceId,
                "online", capabilities.online(deviceId),
                "triggers", capabilities.triggers(deviceId))));
    }

    /**
     * 工作流编辑器的节点目录：这台板型上可以往工作流里放哪些节点。
     * 不可用的节点会在 {@code unresolvedNodes} 里给出原因。
     */
    @GetMapping("/{board}/capability-nodes")
    public ApiResponse<Map<String, Object>> capabilityNodes(@PathVariable String board) {
        return withRepresentativeDevice(board, deviceId -> {
            CapabilityNodeCatalog catalog = nodeCatalogService.buildForDevice(deviceId);
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("board", board);
            data.put("exampleDeviceId", deviceId);
            data.put("online", catalog.online());
            data.put("status", catalog.status());
            data.put("nodes", catalog.nodes());
            data.put("unresolvedNodes", catalog.unresolvedNodes());
            return ApiResponse.ok(data);
        });
    }

    /**
     * Section 触发树：Section → 可交互元素 → 该元素可触发的事件。
     *
     * <p>与 {@code /capability-nodes} 的分工：后者给出工作流图的可用节点，本接口给出
     * {@code section.trigger} 节点的配置面板数据。</p>
     *
     * <p>// TODO(lcd085-refactor): 这是接口集里最后一处仍读旧 Section 模型（{@link SectionTriggerCatalog}
     * 及其 Service）的端点。触发树应从 v2 能力 Schema 的 {@code surface.ui} 派生。
     * 登记于 12_DESIGN_NOTES.md §7.2，随 P5c 一并处理——终端固件未切换前，旧 Section 模型仍是活的，
     * 现在改写会让触发面板没有数据源。</p>
     */
    @GetMapping("/{board}/section-triggers")
    public ApiResponse<SectionTriggerCatalog> sectionTriggers(
            @PathVariable String board,
            @RequestParam(required = false) String pageId,
            @RequestParam(required = false) String pageJson) {

        Optional<String> device = capabilities.representativeDevice(board);
        if (device.isEmpty()) {
            return ApiResponse.error(40400,
                    "no device available for board: " + board + "。等待该板型设备完成能力同步。");
        }
        String deviceId = device.get();
        boolean online = capabilities.online(deviceId);

        SectionTriggerCatalog catalog;
        if (pageId != null && !pageId.isBlank()) {
            catalog = sectionTriggerCatalogService.buildForPage(deviceId, board, online, pageId);
        } else if (pageJson != null && !pageJson.isBlank()) {
            catalog = sectionTriggerCatalogService.buildFromPageJson(deviceId, board, online, pageId, pageJson);
        } else {
            catalog = new SectionTriggerCatalog(null, deviceId, board, online, List.of());
        }
        return ApiResponse.ok(catalog);
    }

    // ── 内部 ───────────────────────────────────────────────────────────────

    /** 解析板型的代表设备并执行回调；没有可用设备时给出可区分的原因。 */
    private ApiResponse<Map<String, Object>> withRepresentativeDevice(
            String board,
            java.util.function.Function<String, ApiResponse<Map<String, Object>>> callback) {

        if (capabilities.board(board).isEmpty()) {
            return ApiResponse.error(40400, "board not found: " + board);
        }
        return capabilities.representativeDevice(board)
                .map(callback)
                .orElseGet(() -> ApiResponse.error(40400,
                        "no device available for board: " + board + "。等待该板型设备完成能力同步。"));
    }
}
