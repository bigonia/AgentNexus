package com.zwbd.agentnexus.sdui.v2.capability;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.zwbd.agentnexus.sdui.v2.protocol.ProtocolException;
import com.zwbd.agentnexus.sdui.v2.protocol.ProtocolErrors;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 能力 Schema 缓存与同步状态。
 *
 * <p>对应 04_PROTOCOL_MODEL.md §4 的协商流程：</p>
 *
 * <pre>
 * hash 已知        → 直接使用缓存能力
 * hash 首次/变化   → 平台请求完整 Schema → 终端发送 → 平台校验内容与 hash → 保存并启用
 * </pre>
 *
 * <p>「同一固件能力的多个终端可以共享平台侧同一份 Schema」，因此缓存按 hash 全局共享而非按设备。
 * 「只有首次注册、未知 hash 或 Schema 校验失败时，终端进入能力同步阶段并暂不接受平台业务」，
 * 因此每设备另有一份同步状态。</p>
 */
@Slf4j
@Component
public class CapabilityRegistryV2 {

    /** 设备的能力同步状态。 */
    public enum SyncState {
        /** 握手已到但平台尚未确认能力，暂不接受业务下发。 */
        PENDING,
        /** 能力已确认，可以接受业务下发。 */
        SYNCED,
        /** Schema 校验失败。 */
        FAILED;

        public boolean allowsBusiness() {
            return this == SYNCED;
        }
    }

    private final Map<String, CapabilitySchemaV2> schemaByHash = new ConcurrentHashMap<>();
    private final Map<String, SyncState> syncStateByDevice = new ConcurrentHashMap<>();
    private final Map<String, String> hashByDevice = new ConcurrentHashMap<>();

    private final ObjectMapper objectMapper;

    public CapabilityRegistryV2(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    /** 连接建立或接管时记录设备声明的 hash，并把状态置为待确认。 */
    public void onHandshake(String deviceId, String claimedHash) {
        if (claimedHash == null || claimedHash.isBlank()) {
            syncStateByDevice.put(deviceId, SyncState.PENDING);
            hashByDevice.remove(deviceId);
            log.info("设备未声明 capability_hash，需要完整能力同步: device={}", deviceId);
            return;
        }
        hashByDevice.put(deviceId, claimedHash);
        if (schemaByHash.containsKey(claimedHash)) {
            syncStateByDevice.put(deviceId, SyncState.SYNCED);
            log.info("capability_hash 命中缓存，跳过完整同步: device={}, hash={}", deviceId, claimedHash);
        } else {
            syncStateByDevice.put(deviceId, SyncState.PENDING);
            log.info("capability_hash 未知，需要请求完整 Schema: device={}, hash={}", deviceId, claimedHash);
        }
    }

    /** 平台是否需要请求该设备的完整 Schema。 */
    public boolean needsSchemaUpload(String deviceId) {
        return syncStateOf(deviceId) == SyncState.PENDING;
    }

    public boolean isKnownHash(String hash) {
        return hash != null && schemaByHash.containsKey(hash);
    }

    public Optional<CapabilitySchemaV2> findByHash(String hash) {
        return hash == null ? Optional.empty() : Optional.ofNullable(schemaByHash.get(hash));
    }

    /** 设备当前生效的 Schema；未同步完成时为空。 */
    public Optional<CapabilitySchemaV2> schemaFor(String deviceId) {
        String hash = hashByDevice.get(deviceId);
        if (hash == null) {
            return Optional.empty();
        }
        return Optional.ofNullable(schemaByHash.get(hash));
    }

    /**
     * 校验终端上传的 Schema 与声明的 hash 是否一致，一致则入缓存并标记同步完成。
     *
     * @return 校验是否通过
     */
    public boolean verifyAndCache(String deviceId, CapabilitySchemaV2 schema) {
        if (schema == null) {
            markSyncFailed(deviceId, "schema 为空");
            return false;
        }
        String actualHash = CapabilityHash.compute(schema, objectMapper);
        String claimedHash = hashByDevice.get(deviceId);
        if (claimedHash != null && !claimedHash.equals(actualHash)) {
            markSyncFailed(deviceId, "hash 不一致: claimed=" + claimedHash + ", actual=" + actualHash);
            return false;
        }
        schemaByHash.put(actualHash, schema);
        hashByDevice.put(deviceId, actualHash);
        syncStateByDevice.put(deviceId, SyncState.SYNCED);
        log.info("能力 Schema 校验通过并启用: device={}, hash={}, board={}", deviceId, actualHash, schema.board());
        return true;
    }

    /**
     * 直接以构建时哈希入缓存。用于平台侧预置已知固件能力，或终端上传时未声明 hash 的场景。
     */
    public String cache(CapabilitySchemaV2 schema) {
        String hash = CapabilityHash.compute(schema, objectMapper);
        schemaByHash.put(hash, schema);
        return hash;
    }

    public void markSyncFailed(String deviceId, String reason) {
        syncStateByDevice.put(deviceId, SyncState.FAILED);
        log.warn("能力同步失败: device={}, reason={}", deviceId, reason);
    }

    public SyncState syncStateOf(String deviceId) {
        return syncStateByDevice.getOrDefault(deviceId, SyncState.PENDING);
    }

    /**
     * 设备是否允许接受业务下发。缺口 G8：文档定义了能力同步阶段"暂不接受平台业务"，
     * 但未定义平台如何拦截，这里统一在业务配置域入口处按此判定。
     */
    public boolean businessAllowed(String deviceId) {
        return syncStateOf(deviceId).allowsBusiness();
    }

    /** 业务下发前的准入检查，未通过时抛出协议错误。 */
    public void requireBusinessAllowed(String deviceId) {
        if (!businessAllowed(deviceId)) {
            throw new ProtocolException(ProtocolErrors.RESOURCE_EXHAUSTED,
                    "设备尚未完成能力同步: " + deviceId + ", state=" + syncStateOf(deviceId));
        }
    }

    public void forgetDevice(String deviceId) {
        syncStateByDevice.remove(deviceId);
        hashByDevice.remove(deviceId);
    }

    public int cachedSchemaCount() {
        return schemaByHash.size();
    }
}
