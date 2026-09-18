package com.zwbd.agentnexus.sdui.v2.transport;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.zwbd.agentnexus.sdui.v2.V2TestSupport;
import com.zwbd.agentnexus.sdui.v2.business.BusinessConfigService;
import com.zwbd.agentnexus.sdui.v2.capability.CapabilityRegistryV2;
import com.zwbd.agentnexus.sdui.v2.protocol.V2Names;
import com.zwbd.agentnexus.sdui.v2.session.DeviceConnection;
import com.zwbd.agentnexus.sdui.v2.session.DeviceConnectionRegistry;
import com.zwbd.agentnexus.sdui.v2.session.DeviceConnectionTakenOverEvent;
import com.zwbd.agentnexus.sdui.v2.sim.SimulatedLcd085Device;
import com.zwbd.agentnexus.sdui.v2.system.SystemCommandService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 连接接管后的初始化编排：先确认能力，再重发业务配置与系统期望值。
 */
class V2SessionBootstrapServiceTest {

    private static final String DEVICE = "dev-1";

    private final ObjectMapper objectMapper = new ObjectMapper();
    private DeviceConnectionRegistry connections;
    private CapabilityRegistryV2 capabilities;
    private BusinessConfigService businessConfigService;
    private SystemCommandService systemCommandService;
    private PlatformRequestService requests;
    private V2SessionBootstrapService bootstrap;

    @BeforeEach
    void setUp() {
        connections = new DeviceConnectionRegistry(mock(org.springframework.context.ApplicationEventPublisher.class));
        capabilities = new CapabilityRegistryV2(objectMapper);
        businessConfigService = mock(BusinessConfigService.class);
        systemCommandService = mock(SystemCommandService.class);
        requests = mock(PlatformRequestService.class);
        when(requests.send(anyString(), anyString(), any()))
                .thenReturn(CompletableFuture.completedFuture(PlatformRequestService.Outcome.success()));

        bootstrap = new V2SessionBootstrapService(connections, capabilities, businessConfigService,
                systemCommandService, requests, objectMapper);
    }

    private DeviceConnectionTakenOverEvent attachWithHash(String hash) {
        DeviceConnection connection = connections.attach(DEVICE, V2TestSupport.openSession("s-1"),
                V2TestSupport.handshake(DEVICE, hash));
        return new DeviceConnectionTakenOverEvent(DEVICE, 0L, connection.getGeneration());
    }

    @Test
    @DisplayName("hash 未知：请求完整 Schema，且暂不重发业务配置")
    void requestsSchemaWhenHashUnknown() {
        DeviceConnectionTakenOverEvent event = attachWithHash("unknownhash0000");

        bootstrap.onConnectionTakenOver(event);

        verify(requests).send(eq(DEVICE), eq(V2Names.CAPABILITY_GET), any());
        verify(businessConfigService, never()).resendIfActive(anyString());
        assertEquals(CapabilityRegistryV2.SyncState.PENDING, capabilities.syncStateOf(DEVICE));
    }

    @Test
    @DisplayName("hash 已知：跳过完整同步，直接重发业务配置与系统期望值")
    void skipsSyncWhenHashKnown() {
        String knownHash = capabilities.cache(SimulatedLcd085Device.SCHEMA);
        DeviceConnectionTakenOverEvent event = attachWithHash(knownHash);

        bootstrap.onConnectionTakenOver(event);

        verify(requests, never()).send(anyString(), eq(V2Names.CAPABILITY_GET), any());
        verify(businessConfigService).resendIfActive(DEVICE);
        verify(systemCommandService).resendDesired(DEVICE);
        assertEquals(CapabilityRegistryV2.SyncState.SYNCED, capabilities.syncStateOf(DEVICE));
    }

    @Test
    @DisplayName("Schema 上传校验通过后启用能力并补发业务配置")
    void acceptsUploadedSchema() {
        attachWithHash(simulatedDeviceHash());
        assertEquals(CapabilityRegistryV2.SyncState.PENDING, capabilities.syncStateOf(DEVICE));

        bootstrap.onSchemaUpload(DEVICE, schemaBytes());

        assertEquals(CapabilityRegistryV2.SyncState.SYNCED, capabilities.syncStateOf(DEVICE));
        assertTrue(capabilities.schemaFor(DEVICE).isPresent());
        verify(businessConfigService).resendIfActive(DEVICE);
        verify(systemCommandService).resendDesired(DEVICE);
    }

    @Test
    @DisplayName("Schema 无法解析时标记失败且不启用")
    void rejectsUnparsableSchema() {
        attachWithHash("unknownhash0000");

        bootstrap.onSchemaUpload(DEVICE, new byte[]{'{', 'o', 'o', 'p', 's'});

        assertEquals(CapabilityRegistryV2.SyncState.FAILED, capabilities.syncStateOf(DEVICE));
        verify(businessConfigService, never()).resendIfActive(anyString());
    }

    @Test
    @DisplayName("过期的接管事件被忽略")
    void ignoresStaleTakeoverEvent() {
        attachWithHash("unknownhash0000");
        // 模拟第二次接管后旧事件才到达
        DeviceConnectionTakenOverEvent stale = new DeviceConnectionTakenOverEvent(DEVICE, 0L, 999L);

        bootstrap.onConnectionTakenOver(stale);

        verify(requests, never()).send(anyString(), anyString(), any());
    }

    private String simulatedDeviceHash() {
        return com.zwbd.agentnexus.sdui.v2.capability.CapabilityHash
                .compute(SimulatedLcd085Device.SCHEMA, objectMapper);
    }

    private byte[] schemaBytes() {
        try {
            return objectMapper.writeValueAsBytes(SimulatedLcd085Device.SCHEMA);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
