package com.zwbd.agentnexus.sdui.v2.capability;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.zwbd.agentnexus.sdui.v2.protocol.ProtocolException;
import com.zwbd.agentnexus.sdui.v2.protocol.ProtocolErrors;
import com.zwbd.agentnexus.sdui.v2.sim.SimulatedLcd085Device;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 能力 Schema 缓存与协商状态。
 */
class CapabilityRegistryV2Test {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private CapabilityRegistryV2 registry;

    @BeforeEach
    void setUp() {
        registry = new CapabilityRegistryV2(objectMapper);
    }

    @Test
    @DisplayName("首次出现未知 hash：进入待同步状态并要求上传 Schema")
    void unknownHashNeedsUpload() {
        registry.onHandshake("dev-1", "aaaabbbbccccdddd");

        assertEquals(CapabilityRegistryV2.SyncState.PENDING, registry.syncStateOf("dev-1"));
        assertTrue(registry.needsSchemaUpload("dev-1"));
        assertFalse(registry.businessAllowed("dev-1"));
        assertTrue(registry.schemaFor("dev-1").isEmpty());
    }

    @Test
    @DisplayName("已知 hash：直接使用缓存能力并允许业务下发")
    void knownHashSkipsUpload() {
        String hash = registry.cache(SimulatedLcd085Device.SCHEMA);

        registry.onHandshake("dev-1", hash);

        assertEquals(CapabilityRegistryV2.SyncState.SYNCED, registry.syncStateOf("dev-1"));
        assertFalse(registry.needsSchemaUpload("dev-1"));
        assertTrue(registry.businessAllowed("dev-1"));
        assertTrue(registry.schemaFor("dev-1").isPresent());
    }

    @Test
    @DisplayName("同一固件能力的多个终端共享同一份缓存 Schema")
    void schemaIsSharedAcrossDevices() {
        String hash = registry.cache(SimulatedLcd085Device.SCHEMA);

        registry.onHandshake("dev-1", hash);
        registry.onHandshake("dev-2", hash);

        assertEquals(1, registry.cachedSchemaCount());
        assertTrue(registry.businessAllowed("dev-1"));
        assertTrue(registry.businessAllowed("dev-2"));
    }

    @Test
    @DisplayName("未知 hash 的设备上传完整 Schema 后校验通过、入缓存并启用")
    void verifyAndCacheAcceptsMatchingHash() {
        String realHash = CapabilityHash.compute(SimulatedLcd085Device.SCHEMA, objectMapper);
        registry.onHandshake("dev-1", realHash);

        // 协商起点：平台不认识该 hash，需要完整上传
        assertEquals(CapabilityRegistryV2.SyncState.PENDING, registry.syncStateOf("dev-1"));
        assertTrue(registry.needsSchemaUpload("dev-1"));

        assertTrue(registry.verifyAndCache("dev-1", SimulatedLcd085Device.SCHEMA));

        assertEquals(CapabilityRegistryV2.SyncState.SYNCED, registry.syncStateOf("dev-1"));
        assertTrue(registry.isKnownHash(realHash));
        assertTrue(registry.schemaFor("dev-1").isPresent());
    }

    @Test
    @DisplayName("上传的 Schema 与声明 hash 不一致时标记失败，且不缓存错误内容")
    void verifyAndCacheRejectsMismatchedHash() {
        registry.onHandshake("dev-1", "aaaabbbbccccdddd");

        assertFalse(registry.verifyAndCache("dev-1", SimulatedLcd085Device.SCHEMA));

        assertEquals(CapabilityRegistryV2.SyncState.FAILED, registry.syncStateOf("dev-1"));
        assertFalse(registry.businessAllowed("dev-1"));
        assertEquals(0, registry.cachedSchemaCount());
        assertTrue(registry.schemaFor("dev-1").isEmpty());
    }

    @Test
    @DisplayName("上传空 Schema 直接失败")
    void rejectsNullSchema() {
        registry.onHandshake("dev-1", "aaaabbbbccccdddd");

        assertFalse(registry.verifyAndCache("dev-1", null));

        assertEquals(CapabilityRegistryV2.SyncState.FAILED, registry.syncStateOf("dev-1"));
    }

    @Test
    @DisplayName("未声明 hash 时视为待同步")
    void missingHashNeedsUpload() {
        registry.onHandshake("dev-1", null);

        assertEquals(CapabilityRegistryV2.SyncState.PENDING, registry.syncStateOf("dev-1"));
        assertTrue(registry.needsSchemaUpload("dev-1"));
    }

    @Test
    @DisplayName("准入检查在未同步时抛出 resource_exhausted")
    void requireBusinessAllowedThrows() {
        registry.onHandshake("dev-1", "aaaabbbbccccdddd");

        ProtocolException error = assertThrows(ProtocolException.class,
                () -> registry.requireBusinessAllowed("dev-1"));
        assertEquals(ProtocolErrors.RESOURCE_EXHAUSTED, error.code());
    }

    @Test
    @DisplayName("准入检查在同步完成后放行")
    void requireBusinessAllowedPasses() {
        String hash = registry.cache(SimulatedLcd085Device.SCHEMA);
        registry.onHandshake("dev-1", hash);

        registry.requireBusinessAllowed("dev-1");
    }

    @Test
    @DisplayName("forgetDevice 只清设备状态，不影响共享缓存")
    void forgetDeviceKeepsSharedCache() {
        String hash = registry.cache(SimulatedLcd085Device.SCHEMA);
        registry.onHandshake("dev-1", hash);

        registry.forgetDevice("dev-1");

        assertEquals(CapabilityRegistryV2.SyncState.PENDING, registry.syncStateOf("dev-1"));
        assertFalse(registry.businessAllowed("dev-1"));
        assertEquals(1, registry.cachedSchemaCount());
        assertTrue(registry.isKnownHash(hash));
    }
}
