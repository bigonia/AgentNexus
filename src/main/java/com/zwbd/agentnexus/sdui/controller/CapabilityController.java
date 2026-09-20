package com.zwbd.agentnexus.sdui.controller;

import com.zwbd.agentnexus.common.web.ApiResponse;
import com.zwbd.agentnexus.sdui.v2.capability.CapabilityQueryService;
import com.zwbd.agentnexus.sdui.v2.capability.CapabilitySchemaV2;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;
import java.util.Optional;

/**
 * 能力查询 API。
 *
 * <p>回答"这台设备现在能做什么"。全部内容来自设备通过 v2 握手声明、并经 {@code capability_hash}
 * 校验的能力 Schema——不存在第二份能力快照，也不做任何平台侧的能力推断。</p>
 *
 * <p>动作的 {@code usableIn} 是前端必须尊重的分界线：只声明 {@code binding} 的动作平台发不出请求，
 * 只能出现在工作流的本地响应序列里。</p>
 *
 * <p>管理面边界见 {@code docs/sdui/PLATFORM_REFACTOR.md} §3；端点明细以本控制器和 OpenAPI 为准。</p>
 */
@RestController
@RequestMapping("/api/v1/sdui/capabilities")
@RequiredArgsConstructor
public class CapabilityController {

    private final CapabilityQueryService capabilities;

    /** 全平台能力概览：设备数、在线数、已完成能力同步的设备数、板型分布。 */
    @GetMapping("/catalog")
    public ApiResponse<Map<String, Object>> catalog() {
        return ApiResponse.ok(capabilities.overview());
    }

    /** 单设备能力摘要。不返回完整 Schema，避免详情接口随 Schema 体积膨胀。 */
    @GetMapping("/{deviceId}")
    public ApiResponse<Map<String, Object>> summary(@PathVariable String deviceId) {
        return ApiResponse.ok(capabilities.summary(deviceId));
    }

    /** 完整能力 Schema。 */
    @GetMapping("/{deviceId}/schema")
    public ApiResponse<CapabilitySchemaV2> schema(@PathVariable String deviceId) {
        Optional<CapabilitySchemaV2> schema = capabilities.schemaOf(deviceId);
        return schema.map(ApiResponse::ok)
                .orElseGet(() -> ApiResponse.error(40400,
                        "capability schema not synced for device: " + deviceId));
    }

    /** 动作目录，含 {@code usableIn} 与参数约束。 */
    @GetMapping("/{deviceId}/actions")
    public ApiResponse<Map<String, Object>> actions(@PathVariable String deviceId) {
        return ApiResponse.ok(Map.of(
                "deviceId", deviceId,
                "online", capabilities.online(deviceId),
                "actions", capabilities.actions(deviceId)));
    }

    /** 可配置触发源目录。 */
    @GetMapping("/{deviceId}/triggers")
    public ApiResponse<Map<String, Object>> triggers(@PathVariable String deviceId) {
        return ApiResponse.ok(Map.of(
                "deviceId", deviceId,
                "online", capabilities.online(deviceId),
                "triggers", capabilities.triggers(deviceId)));
    }

    /** 能力同步进度：定位"为什么这台设备还不能接业务"。 */
    @GetMapping("/{deviceId}/sync")
    public ApiResponse<Map<String, Object>> sync(@PathVariable String deviceId) {
        return ApiResponse.ok(capabilities.sync(deviceId));
    }
}
