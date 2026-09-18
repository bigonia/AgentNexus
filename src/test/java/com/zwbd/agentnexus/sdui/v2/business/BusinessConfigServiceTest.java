package com.zwbd.agentnexus.sdui.v2.business;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.zwbd.agentnexus.sdui.v2.V2ProtocolProperties;
import com.zwbd.agentnexus.sdui.v2.capability.CapabilityHash;
import com.zwbd.agentnexus.sdui.v2.capability.CapabilityRegistryV2;
import com.zwbd.agentnexus.sdui.v2.protocol.ProtocolErrors;
import com.zwbd.agentnexus.sdui.v2.protocol.V2Names;
import com.zwbd.agentnexus.sdui.v2.sim.SimulatedLcd085Device;
import com.zwbd.agentnexus.sdui.v2.transport.PlatformRequestService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.context.ApplicationEventPublisher;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 业务配置编排：准备、校验、全量下发、清理与触发。
 *
 * <p><b>开放项（缺口 G17）</b>：当前生效状态是内存实现，平台重启后需要业务层重新下发。
 * 因此这里也验证"本地状态只在终端确认成功后才改变"这一关键不变量。</p>
 */
class BusinessConfigServiceTest {

    private static final String DEVICE = "dev-1";

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final V2ProtocolProperties properties = new V2ProtocolProperties();

    private CapabilityRegistryV2 capabilities;
    private InteractionTokenService tokens;
    private PlatformRequestService requests;
    private ApplicationEventPublisher publisher;
    private BusinessConfigService service;

    @BeforeEach
    void setUp() {
        capabilities = new CapabilityRegistryV2(objectMapper);
        String hash = capabilities.cache(SimulatedLcd085Device.SCHEMA);
        capabilities.onHandshake(DEVICE, hash);

        tokens = new InteractionTokenService();
        requests = mock(PlatformRequestService.class);
        publisher = mock(ApplicationEventPublisher.class);
        when(requests.send(anyString(), anyString(), any()))
                .thenReturn(CompletableFuture.completedFuture(PlatformRequestService.Outcome.success()));

        service = new BusinessConfigService(capabilities, new BusinessConfigValidator(properties),
                tokens, requests, properties, publisher);
    }

    /** 一份需要平台注入 token 的草稿配置。 */
    private static BusinessConfig draft() {
        return new BusinessConfig(DEVICE, 0L, List.of(
                new TriggerBinding("button.ok", TriggerSource.PHYSICAL, null, List.of(
                        ResponseStep.of("audio.record.start"),
                        ResponseStep.of(V2Names.ACTION_PLATFORM_REPORT))),
                new TriggerBinding("platform.trigger", TriggerSource.PLATFORM, null, List.of(
                        ResponseStep.of("prompt.play", Map.of("preset", "start")),
                        ResponseStep.of("audio.record.stop")))
        ));
    }

    private Object captureUpdateBody() {
        ArgumentCaptor<Object> captor = ArgumentCaptor.forClass(Object.class);
        verify(requests).send(org.mockito.ArgumentMatchers.eq(DEVICE),
                org.mockito.ArgumentMatchers.eq(V2Names.BUSINESS_UPDATE), captor.capture());
        return captor.getValue();
    }

    // ── prepare ─────────────────────────────────────────────────────────────

    @Test
    @DisplayName("准备阶段为云端 Trigger 与上报 Response 注入 token，并分配配置版本号")
    void prepareInjectsTokensAndVersion() {
        BusinessConfigService.Prepared prepared = service.prepare(DEVICE, draft());

        TriggerBinding physical = prepared.config().findBinding("button.ok");
        assertNotNull(physical);
        assertEquals(TriggerSource.PHYSICAL, physical.source());
        assertEquals(null, physical.token(), "物理 Trigger 不应有 token");

        TriggerBinding platformBinding = prepared.config().findBinding("platform.trigger");
        assertEquals(TriggerSource.PLATFORM, platformBinding.source());
        assertTrue(platformBinding.token().startsWith("pt_"));

        ResponseStep reportStep = physical.responses().get(1);
        assertEquals(V2Names.ACTION_PLATFORM_REPORT, reportStep.action());
        assertTrue(String.valueOf(reportStep.params().get("token")).startsWith("rt_"));

        assertTrue(prepared.config().configVersion() > 0);
        assertEquals(2, prepared.issuedTokens().size());
        assertTrue(service.validate(DEVICE, draft()).ok());
    }

    @Test
    @DisplayName("下发 body 不含 deviceId，符合消息不重复携带设备标识的约定")
    void updateBodyOmitsDeviceId() {
        service.apply(DEVICE, draft());

        Object body = captureUpdateBody();
        assertTrue(body instanceof Map);
        @SuppressWarnings("unchecked")
        Map<String, Object> map = (Map<String, Object>) body;
        assertFalse(map.containsKey("deviceId"));
        assertTrue(map.containsKey("configVersion"));
        assertTrue(map.containsKey("triggers"));
    }

    // ── apply ───────────────────────────────────────────────────────────────

    @Test
    @DisplayName("下发成功后本地生效，旧 token 被撤销")
    void applyCommitsStateOnSuccess() {
        // 先建立一份旧配置，制造可被撤销的旧 token
        service.apply(DEVICE, draft()).join();
        int staleTokens = tokens.tokenCountOfDevice(DEVICE);
        assertEquals(2, staleTokens);

        var outcome = service.apply(DEVICE, draft()).join();

        assertTrue(outcome.ok());
        assertTrue(service.hasActiveBusiness(DEVICE));
        assertEquals(2, tokens.tokenCountOfDevice(DEVICE), "旧 token 被撤销，只保留新配置的 token");

        BusinessConfig active = service.activeState(DEVICE).orElseThrow().config();
        assertTrue(active.findBindingByToken(
                active.findBinding("platform.trigger").token()) != null);
    }

    @Test
    @DisplayName("校验失败时整体拒绝：不下发、不生效、新签发的 token 被撤销")
    void applyRejectsInvalidConfig() {
        BusinessConfig invalid = new BusinessConfig(DEVICE, 0L, List.of(
                new TriggerBinding("button.ok", TriggerSource.PHYSICAL, null,
                        List.of(ResponseStep.of("audio.that.does.not.exist")))));

        var outcome = service.apply(DEVICE, invalid).join();

        assertFalse(outcome.ok());
        assertEquals(ProtocolErrors.CONFIG_INVALID, outcome.error());
        assertFalse(service.hasActiveBusiness(DEVICE));
        assertEquals(0, tokens.tokenCountOfDevice(DEVICE), "失败后不应遗留 token");
        verify(requests, never()).send(anyString(),
                org.mockito.ArgumentMatchers.eq(V2Names.BUSINESS_UPDATE), any());
    }

    @Test
    @DisplayName("终端拒绝下发时本地状态保持不变")
    void applyKeepsStateWhenDeviceRejects() {
        service.apply(DEVICE, draft()).join();
        BusinessConfig previous = service.activeState(DEVICE).orElseThrow().config();

        when(requests.send(anyString(), anyString(), any())).thenReturn(
                CompletableFuture.completedFuture(
                        PlatformRequestService.Outcome.failure(ProtocolErrors.CONFIG_INVALID)));

        var outcome = service.apply(DEVICE, draft()).join();

        assertFalse(outcome.ok());
        assertEquals(previous.configVersion(), service.activeState(DEVICE).orElseThrow().config().configVersion(),
                "失败不应改变本地生效版本");
    }

    @Test
    @DisplayName("能力未同步时拒绝下发，返回 resource_exhausted")
    void applyRequiresCapabilitySync() {
        capabilities.onHandshake(DEVICE, "unknownhash0000");

        var outcome = service.apply(DEVICE, draft()).join();

        assertFalse(outcome.ok());
        assertEquals(ProtocolErrors.RESOURCE_EXHAUSTED, outcome.error());
        verify(requests, never()).send(anyString(), anyString(), any());
    }

    // ── trigger ─────────────────────────────────────────────────────────────

    @Test
    @DisplayName("无活动业务时触发返回 business_not_active")
    void triggerWithoutActiveBusiness() {
        var outcome = service.trigger(DEVICE, "pt_whatever").join();

        assertFalse(outcome.ok());
        assertEquals(ProtocolErrors.BUSINESS_NOT_ACTIVE, outcome.error());
    }

    @Test
    @DisplayName("token 未登记或不属于当前配置时返回 binding_not_found")
    void triggerWithUnknownToken() {
        service.apply(DEVICE, draft()).join();

        var unknown = service.trigger(DEVICE, "pt_notRegistered0000000").join();
        assertFalse(unknown.ok());
        assertEquals(ProtocolErrors.BINDING_NOT_FOUND, unknown.error());
    }

    @Test
    @DisplayName("合法 token 触发时携带 token 下发 business.trigger")
    void triggerSendsRequestWithToken() {
        service.apply(DEVICE, draft()).join();
        String token = service.activeState(DEVICE).orElseThrow()
                .config().findBinding("platform.trigger").token();

        var outcome = service.trigger(DEVICE, token).join();

        assertTrue(outcome.ok());
        @SuppressWarnings("unchecked")
        ArgumentCaptor<Object> captor = ArgumentCaptor.forClass(Object.class);
        verify(requests).send(org.mockito.ArgumentMatchers.eq(DEVICE),
                org.mockito.ArgumentMatchers.eq(V2Names.BUSINESS_TRIGGER), captor.capture());
        assertEquals(Map.of("token", token), captor.getValue());
    }

    // ── reset ───────────────────────────────────────────────────────────────

    @Test
    @DisplayName("清理成功后撤销全部 token、移除生效配置并发布清理事件")
    void resetClearsState() {
        service.apply(DEVICE, draft()).join();

        var outcome = service.reset(DEVICE).join();

        assertTrue(outcome.ok());
        assertFalse(service.hasActiveBusiness(DEVICE));
        assertEquals(0, tokens.tokenCountOfDevice(DEVICE));

        ArgumentCaptor<BusinessClearedEvent> captor = ArgumentCaptor.forClass(BusinessClearedEvent.class);
        verify(publisher, times(1)).publishEvent(captor.capture());
        assertEquals(DEVICE, captor.getValue().deviceId());
        assertEquals(BusinessClearedEvent.Reason.RESET_REQUESTED, captor.getValue().reason());
    }

    @Test
    @DisplayName("清理失败时保留本地状态，避免平台与终端状态脱节")
    void resetKeepsStateOnFailure() {
        service.apply(DEVICE, draft()).join();
        when(requests.send(anyString(), anyString(), any())).thenReturn(
                CompletableFuture.completedFuture(
                        PlatformRequestService.Outcome.failure(ProtocolErrors.TIMEOUT)));

        var outcome = service.reset(DEVICE).join();

        assertFalse(outcome.ok());
        assertTrue(service.hasActiveBusiness(DEVICE));
        assertEquals(2, tokens.tokenCountOfDevice(DEVICE));
    }

    @Test
    @DisplayName("业务切换按 reset → update 顺序执行")
    void switchBusinessRunsResetThenUpdate() {
        service.apply(DEVICE, draft()).join();

        var outcome = service.switchBusiness(DEVICE, draft()).join();

        assertTrue(outcome.ok());
        var order = org.mockito.Mockito.inOrder(requests);
        order.verify(requests).send(DEVICE, V2Names.BUSINESS_RESET, Map.of());
        order.verify(requests).send(org.mockito.ArgumentMatchers.eq(DEVICE),
                org.mockito.ArgumentMatchers.eq(V2Names.BUSINESS_UPDATE), any());
    }

    @Test
    @DisplayName("reset 失败时不再执行 update")
    void switchBusinessStopsOnResetFailure() {
        when(requests.send(DEVICE, V2Names.BUSINESS_RESET, Map.of())).thenReturn(
                CompletableFuture.completedFuture(
                        PlatformRequestService.Outcome.failure(ProtocolErrors.TIMEOUT)));

        var outcome = service.switchBusiness(DEVICE, draft()).join();

        assertFalse(outcome.ok());
        assertEquals(ProtocolErrors.TIMEOUT, outcome.error());
        verify(requests, never()).send(anyString(),
                org.mockito.ArgumentMatchers.eq(V2Names.BUSINESS_UPDATE), any());
    }

    // ── 上行上报 ────────────────────────────────────────────────────────────

    @Test
    @DisplayName("合法的业务上报被受理并发布领域事件")
    void acceptsInteractionReport() {
        service.apply(DEVICE, draft()).join();
        String reportToken = service.activeState(DEVICE).orElseThrow()
                .config().findBinding("button.ok").responses().get(1).params().get("token").toString();

        assertTrue(service.handleInteractionReport(DEVICE, reportToken));

        ArgumentCaptor<BusinessInteraction> captor = ArgumentCaptor.forClass(BusinessInteraction.class);
        verify(publisher).publishEvent(captor.capture());
        assertEquals(DEVICE, captor.getValue().deviceId());
        assertEquals("button.ok", captor.getValue().triggerId());
        assertEquals("binding:button.ok", captor.getValue().contextRef());
    }

    @Test
    @DisplayName("未登记的上报 token 被丢弃")
    void rejectsUnknownReportToken() {
        service.apply(DEVICE, draft()).join();

        assertFalse(service.handleInteractionReport(DEVICE, "rt_notRegistered00000"));
        verify(publisher, never()).publishEvent(any(BusinessInteraction.class));
    }

    @Test
    @DisplayName("上报 token 与来源设备不匹配时被丢弃")
    void rejectsReportFromOtherDevice() {
        service.apply(DEVICE, draft()).join();
        String reportToken = service.activeState(DEVICE).orElseThrow()
                .config().findBinding("button.ok").responses().get(1).params().get("token").toString();

        assertFalse(service.handleInteractionReport("dev-other", reportToken));
        verify(publisher, never()).publishEvent(any(BusinessInteraction.class));
    }

    @Test
    @DisplayName("无活动业务时的上报被丢弃")
    void rejectsReportWithoutActiveBusiness() {
        String token = tokens.issueReportToken(DEVICE, "button.ok", "binding:button.ok");

        assertFalse(service.handleInteractionReport(DEVICE, token));
        verify(publisher, never()).publishEvent(any(BusinessInteraction.class));
    }

    @Test
    @DisplayName("干跑校验不会在注册表里遗留 token")
    void dryRunValidationLeavesNoTokens() {
        assertTrue(service.validate(DEVICE, draft()).ok());
        assertEquals(0, tokens.tokenCountOfDevice(DEVICE));

        BusinessConfig invalid = new BusinessConfig(DEVICE, 0L, List.of(
                new TriggerBinding("button.ok", TriggerSource.PHYSICAL, null,
                        List.of(ResponseStep.of("unknown.action")))));
        assertFalse(service.validate(DEVICE, invalid).ok());
        assertEquals(0, tokens.tokenCountOfDevice(DEVICE));
    }

    // ── 接管重发 ────────────────────────────────────────────────────────────

    @Test
    @DisplayName("有生效配置时接管后幂等重发完整配置")
    void resendIfActive() {
        service.apply(DEVICE, draft()).join();
        org.mockito.Mockito.clearInvocations(requests);

        service.resendIfActive(DEVICE);

        verify(requests).send(org.mockito.ArgumentMatchers.eq(DEVICE),
                org.mockito.ArgumentMatchers.eq(V2Names.BUSINESS_UPDATE), any());
    }

    @Test
    @DisplayName("无生效配置时接管后不重发")
    void resendSkippedWhenNoActiveBusiness() {
        service.resendIfActive(DEVICE);

        verify(requests, never()).send(anyString(), anyString(), any());
    }

    @Test
    @DisplayName("能力未同步时暂缓重发，等待同步完成")
    void resendDeferredUntilCapabilitySynced() {
        service.apply(DEVICE, draft()).join();
        capabilities.onHandshake(DEVICE, "unknownhash0000");
        org.mockito.Mockito.clearInvocations(requests);

        service.resendIfActive(DEVICE);

        verify(requests, never()).send(anyString(), anyString(), any());
    }

    @Test
    @DisplayName("设备遗忘会撤销 token 并发布清理事件")
    void forgetRevokesEverything() {
        service.apply(DEVICE, draft()).join();

        service.forget(DEVICE);

        assertFalse(service.hasActiveBusiness(DEVICE));
        assertEquals(0, tokens.tokenCountOfDevice(DEVICE));
        verify(publisher).publishEvent(any(BusinessClearedEvent.class));
    }

    @Test
    @DisplayName("能力 hash 与模拟终端一致，说明两端 Schema 形态可互通")
    void simulatedDeviceHashIsAccepted() {
        CapabilityRegistryV2 shared = new CapabilityRegistryV2(objectMapper);
        SimulatedLcd085Device device = new SimulatedLcd085Device(DEVICE, objectMapper);

        shared.onHandshake(DEVICE, device.capabilityHash());

        assertEquals(CapabilityRegistryV2.SyncState.PENDING, shared.syncStateOf(DEVICE));
        assertTrue(shared.verifyAndCache(DEVICE, SimulatedLcd085Device.SCHEMA));
        assertEquals(CapabilityHash.compute(SimulatedLcd085Device.SCHEMA, objectMapper),
                device.capabilityHash());
    }
}
