package com.zwbd.agentnexus.sdui.v2.business;

import com.zwbd.agentnexus.sdui.v2.V2ProtocolProperties;
import com.zwbd.agentnexus.sdui.v2.capability.CapabilityRegistryV2;
import com.zwbd.agentnexus.sdui.v2.protocol.ProtocolErrors;
import com.zwbd.agentnexus.sdui.v2.protocol.V2Names;
import com.zwbd.agentnexus.sdui.v2.transport.PlatformRequestService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 业务配置编排：准备、校验、全量下发、清理与触发。
 *
 * <p>这是平台侧对 01_INTERACTION_MODEL.md §6 的实现入口。三条语义刻意保持独立：</p>
 *
 * <pre>
 * business.reset    清理业务运行状态，不清理连接 / 能力缓存 / 系统命令期望值
 * business.update   全量原子替换声明式配置，不隐含 reset，不停止已运行的持续动作
 * business.trigger  按 token 触发绑定，只接收一次最终结果
 * </pre>
 *
 * <p>平台侧持有"该设备当前生效的完整配置"，这是 04_PROTOCOL_MODEL.md §9 要求平台承担的兜底职责
 * （重连后重新完整下发）。缺口 G17：首期该状态为内存实现，平台重启后需要由业务层重新
 * {@code update}。</p>
 */
@Slf4j
@Service
public class BusinessConfigService {

    /** 设备当前生效的业务状态。 */
    public record ActiveBusinessState(BusinessConfig config, Instant appliedAt) {}

    /** 准备阶段结果：已注入 token 与版本号的配置，以及本次新签发的 token。 */
    public record Prepared(BusinessConfig config, List<String> issuedTokens) {}

    private final CapabilityRegistryV2 capabilities;
    private final BusinessConfigValidator validator;
    private final InteractionTokenService tokenService;
    private final PlatformRequestService requests;
    private final V2ProtocolProperties properties;
    private final ApplicationEventPublisher eventPublisher;

    private final Map<String, ActiveBusinessState> activeByDevice = new ConcurrentHashMap<>();
    private final AtomicLong versionSequence = new AtomicLong();

    public BusinessConfigService(CapabilityRegistryV2 capabilities,
                                 BusinessConfigValidator validator,
                                 InteractionTokenService tokenService,
                                 PlatformRequestService requests,
                                 V2ProtocolProperties properties,
                                 ApplicationEventPublisher eventPublisher) {
        this.capabilities = capabilities;
        this.validator = validator;
        this.tokenService = tokenService;
        this.requests = requests;
        this.properties = properties;
        this.eventPublisher = eventPublisher;
    }

    // ── 校验与准备 ──────────────────────────────────────────────────────────

    /**
     * 只做校验，不下发。供调试接口与工作流部署前预检使用。
     *
     * <p>这是干跑（dry run）：准备阶段的副作用是签发 token，校验结束后必须全部撤销，
     * 否则预检会在注册表里留下永远不会被使用的 token。</p>
     */
    public BusinessConfigValidator.ValidationResult validate(String deviceId, BusinessConfig draft) {
        Prepared prepared = prepare(deviceId, draft);
        try {
            return validator.validate(capabilities.schemaFor(deviceId).orElse(null), prepared.config());
        } finally {
            tokenService.revokeAll(prepared.issuedTokens());
        }
    }

    /**
     * 准备配置：注入配置版本号、为云端 Trigger 与业务上报 Response 生成 token。
     *
     * <p>01§3 要求 token "本身就是云端 Trigger 的控制参数"；01§4 要求平台"在下发绑定时为该
     * Response 提供一个有长度上限的不透明 token"。两者都在这里生成。</p>
     */
    public Prepared prepare(String deviceId, BusinessConfig draft) {
        long version = versionSequence.incrementAndGet();
        List<String> issued = new ArrayList<>();
        List<TriggerBinding> bindings = new ArrayList<>();

        for (TriggerBinding binding : draft.triggers()) {
            TriggerSource source = binding.source() != null
                    ? binding.source()
                    : TriggerSource.infer(binding.triggerId());
            String contextRef = "binding:" + binding.triggerId();

            String triggerToken = binding.token();
            if (source == TriggerSource.PLATFORM && (triggerToken == null || triggerToken.isBlank())) {
                triggerToken = tokenService.issueTriggerToken(deviceId, binding.triggerId(), contextRef);
                issued.add(triggerToken);
            }

            List<ResponseStep> steps = new ArrayList<>(binding.responses().size());
            for (ResponseStep step : binding.responses()) {
                if (!V2Names.ACTION_PLATFORM_REPORT.equals(step.action())) {
                    steps.add(step);
                    continue;
                }
                Map<String, Object> params = new LinkedHashMap<>(step.params());
                Object existing = params.get("token");
                if (existing == null || String.valueOf(existing).isBlank()) {
                    String reportToken = tokenService.issueReportToken(deviceId, binding.triggerId(), contextRef);
                    params.put("token", reportToken);
                    issued.add(reportToken);
                }
                steps.add(step.withParams(params));
            }

            bindings.add(new TriggerBinding(binding.triggerId(), source, triggerToken, steps));
        }

        return new Prepared(draft.withTriggers(bindings).withVersion(version), issued);
    }

    // ── 下发 ────────────────────────────────────────────────────────────────

    /**
     * 全量原子替换业务配置。
     *
     * <p>顺序：能力准入 → 注入 token 与版本 → 校验 → 下发 → 成功后提交本地状态并撤销旧 token。
     * 任一步失败都不改变本地生效状态，也不部分应用。</p>
     */
    public CompletableFuture<PlatformRequestService.Outcome> apply(String deviceId, BusinessConfig draft) {
        try {
            capabilities.requireBusinessAllowed(deviceId);
        } catch (RuntimeException e) {
            log.warn("业务配置下发被拒绝: device={}, reason={}", deviceId, e.getMessage());
            return CompletableFuture.completedFuture(
                    PlatformRequestService.Outcome.failure(ProtocolErrors.RESOURCE_EXHAUSTED));
        }

        Prepared prepared = prepare(deviceId, draft);
        BusinessConfigValidator.ValidationResult result =
                validator.validate(capabilities.schemaFor(deviceId).orElse(null), prepared.config());
        if (!result.ok()) {
            tokenService.revokeAll(prepared.issuedTokens());
            log.warn("业务配置校验失败，整体拒绝: device={}, errors={}", deviceId, result.errorSummary());
            return CompletableFuture.completedFuture(
                    PlatformRequestService.Outcome.failure(ProtocolErrors.CONFIG_INVALID));
        }

        BusinessConfig config = prepared.config();
        return requests.send(deviceId, V2Names.BUSINESS_UPDATE, config.toWireBody())
                .thenApply(outcome -> {
                    if (outcome.ok()) {
                        tokenService.retainOnly(deviceId, prepared.issuedTokens());
                        activeByDevice.put(deviceId, new ActiveBusinessState(config, Instant.now()));
                        log.info("业务配置已生效: device={}, version={}, bindings={}, issuedTokens={}",
                                deviceId, config.configVersion(), config.triggers().size(),
                                prepared.issuedTokens().size());
                    } else {
                        tokenService.revokeAll(prepared.issuedTokens());
                        log.warn("业务配置下发失败，本地状态未改变: device={}, error={}", deviceId, outcome.error());
                    }
                    return outcome;
                });
    }

    /**
     * 清理业务运行状态。
     *
     * <p>01§6.1 明确该操作**不**清理网络连接、设备身份、配网、电源、系统诊断和缓存资源等
     * 系统基础状态。因此这里只撤销本设备业务 token 并移除业务配置。</p>
     */
    public CompletableFuture<PlatformRequestService.Outcome> reset(String deviceId) {
        return requests.send(deviceId, V2Names.BUSINESS_RESET, Map.of())
                .thenApply(outcome -> {
                    if (outcome.ok()) {
                        List<String> revoked = tokenService.revokeAllOfDevice(deviceId);
                        activeByDevice.remove(deviceId);
                        eventPublisher.publishEvent(new BusinessClearedEvent(
                                deviceId, BusinessClearedEvent.Reason.RESET_REQUESTED));
                        log.info("业务已清理并进入无活动业务状态: device={}, revokedTokens={}",
                                deviceId, revoked.size());
                    } else {
                        log.warn("业务清理失败，保留本地状态: device={}, error={}", deviceId, outcome.error());
                    }
                    return outcome;
                });
    }

    /**
     * 业务切换：{@code business.reset → business.update}。
     *
     * <p>01§6.3：由平台控制，首期不提供把二者封装为事务的组合命令。若 reset 成功后在 update 前
     * 断线或更新失败，"终端保持无活动业务状态，不恢复已经清理的旧业务"，平台重连后重新下发完整
     * 配置即可恢复。因此这里的失败处理是"不补偿"，只如实返回错误。</p>
     */
    public CompletableFuture<PlatformRequestService.Outcome> switchBusiness(String deviceId, BusinessConfig draft) {
        return reset(deviceId).thenCompose(resetOutcome -> {
            if (!resetOutcome.ok()) {
                return CompletableFuture.completedFuture(resetOutcome);
            }
            return apply(deviceId, draft);
        });
    }

    /**
     * 按 token 触发终端预设的响应序列。
     *
     * <p>01§3：找不到 token、当前无活动业务或执行资源不足时返回错误；平台请求"只接收整条响应序列的
     * 一次最终成功或错误结果"。</p>
     */
    public CompletableFuture<PlatformRequestService.Outcome> trigger(String deviceId, String token) {
        ActiveBusinessState state = activeByDevice.get(deviceId);
        if (state == null) {
            return CompletableFuture.completedFuture(
                    PlatformRequestService.Outcome.failure(ProtocolErrors.BUSINESS_NOT_ACTIVE));
        }
        if (tokenService.resolveTrigger(token).isEmpty() || state.config().findBindingByToken(token) == null) {
            return CompletableFuture.completedFuture(
                    PlatformRequestService.Outcome.failure(ProtocolErrors.BINDING_NOT_FOUND));
        }
        return requests.send(deviceId, V2Names.BUSINESS_TRIGGER, Map.of("token", token));
    }

    // ── 上行事件 ────────────────────────────────────────────────────────────

    /**
     * 处理终端的业务交互上报。
     *
     * <p>只信任平台自己签发的 token，不接受终端自造的 token；解析出的 {@code triggerId} 与
     * {@code contextRef} 供业务层恢复上下文。</p>
     *
     * @return 是否成功受理
     */
    public boolean handleInteractionReport(String deviceId, String token) {
        Optional<InteractionTokenService.TokenRef> resolved = tokenService.resolveReport(token);
        if (resolved.isEmpty()) {
            log.warn("收到未登记的业务上报 token，已丢弃: device={}, token={}", deviceId, mask(token));
            return false;
        }
        InteractionTokenService.TokenRef ref = resolved.get();
        if (!ref.deviceId().equals(deviceId)) {
            log.warn("业务上报 token 与来源设备不匹配，已丢弃: device={}, tokenDevice={}",
                    deviceId, ref.deviceId());
            return false;
        }
        if (!activeByDevice.containsKey(deviceId)) {
            log.warn("当前无活动业务，业务上报已丢弃: device={}, triggerId={}", deviceId, ref.triggerId());
            return false;
        }
        BusinessInteraction interaction =
                new BusinessInteraction(deviceId, token, ref.triggerId(), ref.contextRef(), Instant.now());
        eventPublisher.publishEvent(interaction);
        log.info("业务交互上报已受理: device={}, triggerId={}, contextRef={}",
                deviceId, ref.triggerId(), ref.contextRef());
        return true;
    }

    // ── 连接接管 ────────────────────────────────────────────────────────────

    /**
     * 连接接管后幂等重发当前完整配置。
     *
     * <p>04§3：不使用 {@code boot_id}，网络重连与终端重启都重新发送完整配置。由于
     * {@code business.update} 不等于 {@code business.reset}，重新下发不会停止终端仍在运行的持续动作。</p>
     */
    public void resendIfActive(String deviceId) {
        ActiveBusinessState state = activeByDevice.get(deviceId);
        if (state == null) {
            log.debug("设备当前无生效业务配置，接管后无需重发: device={}", deviceId);
            return;
        }
        if (!capabilities.businessAllowed(deviceId)) {
            log.info("设备能力尚未同步，暂缓重发业务配置: device={}", deviceId);
            return;
        }
        requests.send(deviceId, V2Names.BUSINESS_UPDATE, state.config().toWireBody())
                .thenAccept(outcome -> log.info("接管后业务配置重发结果: device={}, version={}, ok={}, error={}",
                        deviceId, state.config().configVersion(), outcome.ok(), outcome.error()));
    }

    // ── 查询 ────────────────────────────────────────────────────────────────

    public Optional<ActiveBusinessState> activeState(String deviceId) {
        return Optional.ofNullable(activeByDevice.get(deviceId));
    }

    public boolean hasActiveBusiness(String deviceId) {
        return activeByDevice.containsKey(deviceId);
    }

    /** 平台侧彻底遗忘该设备（设备注销时使用）。 */
    public void forget(String deviceId) {
        tokenService.revokeAllOfDevice(deviceId);
        activeByDevice.remove(deviceId);
        eventPublisher.publishEvent(new BusinessClearedEvent(
                deviceId, BusinessClearedEvent.Reason.DEVICE_FORGOTTEN));
    }

    public int activeDeviceCount() {
        return activeByDevice.size();
    }

    private static String mask(String token) {
        if (token == null || token.length() <= 6) {
            return "***";
        }
        return token.substring(0, 6) + "***";
    }

    /** 便于测试与调试：当前配置上限。 */
    public int maxBindingsPerConfig() {
        return properties.getMaxBindingsPerConfig();
    }
}
