package com.zwbd.agentnexus.sdui.v2.transport;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.zwbd.agentnexus.sdui.v2.business.BusinessConfigService;
import com.zwbd.agentnexus.sdui.v2.capability.CapabilityRegistryV2;
import com.zwbd.agentnexus.sdui.v2.capability.CapabilitySchemaV2;
import com.zwbd.agentnexus.sdui.v2.protocol.V2Names;
import com.zwbd.agentnexus.sdui.v2.session.DeviceConnection;
import com.zwbd.agentnexus.sdui.v2.session.DeviceConnectionRegistry;
import com.zwbd.agentnexus.sdui.v2.session.DeviceConnectionTakenOverEvent;
import com.zwbd.agentnexus.sdui.v2.system.SystemCommandService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.Optional;

/**
 * 连接接管后的设备初始化编排。
 *
 * <p>04_PROTOCOL_MODEL.md §3 与 §4 规定了接管后的顺序：</p>
 *
 * <pre>
 * 新连接接管
 *   → 判断 capability_hash
 *       已知      → 直接使用缓存能力
 *       未知/变化 → 请求完整 Schema，校验通过后启用
 *   → 能力已确认后，重新下发该设备当前的完整 business.update
 *   → 按需重发系统命令期望值
 * </pre>
 *
 * <p>「只有首次注册、未知 hash 或 Schema 校验失败时，终端进入能力同步阶段并暂不接受平台业务」，
 * 所以能力未同步时不重发业务配置；能力同步完成后再补发。</p>
 */
@Slf4j
@Service
public class V2SessionBootstrapService {

    private final DeviceConnectionRegistry connections;
    private final CapabilityRegistryV2 capabilities;
    private final BusinessConfigService businessConfigService;
    private final SystemCommandService systemCommandService;
    private final PlatformRequestService requests;
    private final ObjectMapper objectMapper;

    public V2SessionBootstrapService(DeviceConnectionRegistry connections,
                                     CapabilityRegistryV2 capabilities,
                                     BusinessConfigService businessConfigService,
                                     SystemCommandService systemCommandService,
                                     PlatformRequestService requests,
                                     ObjectMapper objectMapper) {
        this.connections = connections;
        this.capabilities = capabilities;
        this.businessConfigService = businessConfigService;
        this.systemCommandService = systemCommandService;
        this.requests = requests;
        this.objectMapper = objectMapper;
    }

    @EventListener
    public void onConnectionTakenOver(DeviceConnectionTakenOverEvent event) {
        String deviceId = event.deviceId();
        Optional<DeviceConnection> connection = connections.find(deviceId);
        if (connection.isEmpty()) {
            log.warn("接管事件到达但连接已不存在: device={}", deviceId);
            return;
        }
        DeviceConnection current = connection.get();
        if (current.getGeneration() != event.currentGeneration()) {
            log.debug("接管事件已过期，忽略: device={}, eventGeneration={}, currentGeneration={}",
                    deviceId, event.currentGeneration(), current.getGeneration());
            return;
        }

        capabilities.onHandshake(deviceId, current.capabilityHash());

        if (capabilities.needsSchemaUpload(deviceId)) {
            // 能力同步阶段：暂不接受平台业务，先取完整 Schema
            requestFullSchema(deviceId);
            return;
        }
        afterCapabilityReady(deviceId);
    }

    /** 能力同步完成后的后续动作。 */
    public void afterCapabilityReady(String deviceId) {
        businessConfigService.resendIfActive(deviceId);
        systemCommandService.resendDesired(deviceId);
    }

    /**
     * 终端上传完整 Schema（Binary dataType = {@code CAPABILITY_SCHEMA}）。
     *
     * <p>缺口 G7：文档要求"平台请求完整 Schema → 终端发送 Schema → 平台校验内容与 hash"，
     * 但未定义控制流程。平台侧临时采用 {@code capability.get} 请求 + Binary 上传 + Result 回执。</p>
     */
    public void onSchemaUpload(String deviceId, byte[] payload) {
        CapabilitySchemaV2 schema;
        try {
            schema = objectMapper.readValue(payload, CapabilitySchemaV2.class);
        } catch (Exception e) {
            capabilities.markSyncFailed(deviceId, "Schema 解析失败: " + e.getMessage());
            log.warn("能力 Schema 解析失败: device={}", deviceId, e);
            return;
        }
        if (!capabilities.verifyAndCache(deviceId, schema)) {
            return;
        }
        afterCapabilityReady(deviceId);
    }

    private void requestFullSchema(String deviceId) {
        String hash = connections.find(deviceId).map(DeviceConnection::capabilityHash).orElse(null);
        log.info("请求完整能力 Schema: device={}, claimedHash={}", deviceId, hash);
        requests.send(deviceId, V2Names.CAPABILITY_GET, Map.of("capabilityHash", hash == null ? "" : hash))
                .thenAccept(outcome -> {
                    if (!outcome.ok()) {
                        log.warn("能力 Schema 请求失败: device={}, error={}", deviceId, outcome.error());
                    }
                });
    }
}
