package com.zwbd.agentnexus.sdui.workflow;

import com.zwbd.agentnexus.sdui.v2.business.BusinessInteraction;
import com.zwbd.agentnexus.sdui.workflow.entity.NodeWorkflowDeploymentEntity;
import com.zwbd.agentnexus.sdui.workflow.entity.NodeWorkflowRunEntity;
import com.zwbd.agentnexus.sdui.workflow.entity.NodeWorkflowRunStepEntity;
import com.zwbd.agentnexus.sdui.workflow.model.NodeWorkflowDefinition;
import com.zwbd.agentnexus.sdui.workflow.model.NodeWorkflowNode;
import com.zwbd.agentnexus.sdui.workflow.repo.NodeWorkflowDeploymentRepository;
import com.zwbd.agentnexus.sdui.workflow.repo.NodeWorkflowRunRepository;
import com.zwbd.agentnexus.sdui.workflow.repo.NodeWorkflowRunStepRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 工作流运行时：由终端交互上报驱动的"云端续接"。
 *
 * <h2>与旧运行时的区别</h2>
 * <p>旧实现监听终端输入事件（TLV，{@code msgType 9}），收到事件后把触发节点的<b>整条</b>执行计划逐条
 * 下发设备命令——平台遥控设备走完每一个节点。新模型的闭环是（01_INTERACTION_MODEL.md §1–§4）：</p>
 * <pre>
 * 终端本地执行静态响应序列 → platform.interaction.report(token)
 *   → 平台按 token 恢复上下文 → 执行留在平台的步骤 → 以新的独立命令下发结果
 * </pre>
 * <p>因此本类只做两件事：<b>恢复上下文</b>和<b>执行平台步骤</b>。终端的本地那一段平台不参与、
 * 也不感知其过程，只在结束时收到一条上报。</p>
 *
 * <h2>上下文怎么恢复</h2>
 * <p>终端上报的只有 {@code device_id + token}（01§4）。token 在平台侧登记时带上了
 * {@link WorkflowContextRef}（形如 {@code wf:<workflowId>:<triggerNodeId>}），因此这里能直接定位到
 * 是哪一次部署的哪个触发节点，不需要按 triggerId 反查活动部署。见 {@code TriggerBinding.contextRef}。</p>
 *
 * <h2>平台步骤从哪来</h2>
 * <p>来自部署时固化的 {@code businessConfigs}，而不是按当前能力 Schema 重新组装。设备侧的配置在部署
 * 那一刻已经下发并生效，平台侧必须与那一份严格对应；重新组装会引入漂移。</p>
 *
 * <h2>已知限制</h2>
 * <p>平台步骤在连接的消息线程上同步执行。步骤里包含 TTS 合成与音频下行，可能占用秒级时间。
 * 移出该线程需要把租户上下文一起传递（{@code GlobalContext} 是 ThreadLocal，异步线程会退化成
 * {@code default} 租户），属于独立议题。旧实现在这一点上同样是同步的，因此这里没有引入行为退化。</p>
 */
@Slf4j
@Service
public class NodeWorkflowRuntimeService {

    /** 一次运行的全部平台步骤完成。 */
    private static final String RUN_PASSED = "passed";
    /** 某个平台步骤失败，后续步骤不再执行。 */
    private static final String RUN_FAILED = "failed";
    /** 该触发节点没有留给平台的步骤——正常情况下不会发生（无平台步骤时不会配置上报动作）。 */
    private static final String RUN_NOOP = "noop";

    private final NodeWorkflowService workflowService;
    private final NodeWorkflowDeploymentService deploymentService;
    private final NodeWorkflowDeploymentRepository deploymentRepository;
    private final NodeWorkflowRunRepository runRepository;
    private final NodeWorkflowRunStepRepository stepRepository;
    private final WorkflowPlatformStepExecutor executor;
    private final NodeWorkflowRunContextService contextService;
    private final NodeWorkflowParameterResolver parameterResolver;

    public NodeWorkflowRuntimeService(NodeWorkflowService workflowService,
                                      NodeWorkflowDeploymentService deploymentService,
                                      NodeWorkflowDeploymentRepository deploymentRepository,
                                      NodeWorkflowRunRepository runRepository,
                                      NodeWorkflowRunStepRepository stepRepository,
                                      WorkflowPlatformStepExecutor executor,
                                      NodeWorkflowRunContextService contextService,
                                      NodeWorkflowParameterResolver parameterResolver) {
        this.workflowService = workflowService;
        this.deploymentService = deploymentService;
        this.deploymentRepository = deploymentRepository;
        this.runRepository = runRepository;
        this.stepRepository = stepRepository;
        this.executor = executor;
        this.contextService = contextService;
        this.parameterResolver = parameterResolver;
    }

    // ── 交互上报驱动 ────────────────────────────────────────────────────────

    /**
     * 处理终端的业务交互上报。
     *
     * <p>只在平台自己签发的 token 上响应——{@code BusinessConfigService} 已校验 token 归属与设备一致性，
     * 这里再校验 {@code contextRef} 能否解析出工作流上下文，解析不出的（例如非工作流场景写入的
     * {@code binding:<triggerId>}）安静跳过。</p>
     */
    @EventListener
    public void onInteraction(BusinessInteraction interaction) {
        if (interaction == null || interaction.deviceId() == null) {
            return;
        }
        Optional<WorkflowContextRef> parsed = WorkflowContextRef.parse(interaction.contextRef());
        if (parsed.isEmpty()) {
            log.debug("业务上报不属于工作流场景，无需续接: device={}, triggerId={}, contextRef={}",
                    interaction.deviceId(), interaction.triggerId(), interaction.contextRef());
            return;
        }
        WorkflowContextRef ref = parsed.get();

        NodeWorkflowDeploymentEntity deployment = activeDeployment(ref.workflowId(), interaction.deviceId());
        if (deployment == null) {
            log.warn("业务上报找不到活动部署，已丢弃: workflow={}, device={}, triggerNode={}",
                    ref.workflowId(), interaction.deviceId(), ref.triggerNodeId());
            return;
        }

        NodeWorkflowDefinition workflow;
        try {
            workflow = workflowService.workflow(ref.workflowId());
        } catch (RuntimeException e) {
            log.warn("业务上报无法加载工作流定义，已丢弃: workflow={}, error={}",
                    ref.workflowId(), e.getMessage());
            return;
        }

        NodeWorkflowNode trigger = findNode(workflow, ref.triggerNodeId());
        if (trigger == null) {
            log.warn("业务上报的触发节点已不存在于当前工作流定义: workflow={}, triggerNode={}",
                    ref.workflowId(), ref.triggerNodeId());
            return;
        }

        List<WorkflowBusinessConfigAssembler.PlatformStep> steps =
                platformStepsOf(deployment, interaction.deviceId(), ref.triggerNodeId());
        log.info("交互上报受理，开始平台续接: workflow={}, deployment={}, trigger={}, device={}, steps={}",
                ref.workflowId(), deployment.getId(), ref.triggerNodeId(), interaction.deviceId(), steps.size());

        NodeWorkflowRunEntity run = executeContinuation(workflow, deployment, trigger, interaction, steps);
        log.info("平台续接完成: runId={}, status={}, error={}", run.getId(), run.getStatus(), run.getError());
    }

    // ── 调试入口 ────────────────────────────────────────────────────────────

    /**
     * 模拟一次交互上报，跑一遍平台续接。
     *
     * <p>刻意只跑平台步骤而不是整条执行计划：终端的本地那一段由终端负责，平台调试接口如果把它也跑了，
     * 得到的结果就不代表真实链路。这样调试所见与线上行为一致。</p>
     */
    public Map<String, Object> testTrigger(String workflowId, String deploymentId, Map<String, Object> body) {
        NodeWorkflowDefinition workflow = workflowService.workflow(workflowId);
        NodeWorkflowDeploymentEntity deployment = deploymentService.requireDeployment(workflowId, deploymentId);
        if (!"active".equals(deployment.getStatus())) {
            throw new IllegalArgumentException("deployment is not active: " + deploymentId);
        }
        String triggerNodeId = NodeWorkflowSupport.string(body.get("triggerNodeId"));
        NodeWorkflowNode trigger = triggerNodeId.isBlank()
                ? firstTriggerNode(workflow)
                : findNode(workflow, triggerNodeId);
        if (trigger == null) {
            throw new IllegalArgumentException("trigger node not found: " + triggerNodeId);
        }
        if (!NodeWorkflowSupport.isTriggerNode(trigger.nodeType())) {
            throw new IllegalArgumentException("test trigger node must be a trigger type: " + trigger.nodeId());
        }
        String deviceId = deviceForSlot(deployment, trigger.slotId());
        if (deviceId == null || deviceId.isBlank()) {
            throw new IllegalArgumentException("trigger slot is not bound to a device: " + trigger.slotId());
        }

        BusinessInteraction simulated = new BusinessInteraction(
                deviceId, null, NodeWorkflowSupport.string(trigger.params().get("eventId")),
                WorkflowContextRef.of(workflowId, trigger.nodeId()).encode(), Instant.now());
        List<WorkflowBusinessConfigAssembler.PlatformStep> steps =
                platformStepsOf(deployment, deviceId, trigger.nodeId());
        return runToMap(executeContinuation(workflow, deployment, trigger, simulated, steps), true);
    }

    public List<Map<String, Object>> listRuns(String workflowId, String deploymentId) {
        deploymentService.requireDeployment(workflowId, deploymentId);
        return runRepository.findByDeploymentIdOrderByStartedAtDesc(deploymentId)
                .stream()
                .map(run -> runToMap(run, false))
                .toList();
    }

    public Map<String, Object> getRun(String workflowId, String runId) {
        NodeWorkflowRunEntity run = runRepository.findByWorkflowIdAndId(workflowId, runId)
                .orElseThrow(() -> new IllegalArgumentException("run not found: " + runId));
        return runToMap(run, true);
    }

    // ── 执行 ────────────────────────────────────────────────────────────────

    private NodeWorkflowRunEntity executeContinuation(NodeWorkflowDefinition workflow,
                                                      NodeWorkflowDeploymentEntity deployment,
                                                      NodeWorkflowNode trigger,
                                                      BusinessInteraction interaction,
                                                      List<WorkflowBusinessConfigAssembler.PlatformStep> steps) {
        NodeWorkflowRunEntity run = new NodeWorkflowRunEntity();
        run.setWorkflowId(workflow.id());
        run.setDeploymentId(deployment.getId());
        run.setTriggerNodeId(trigger.nodeId());
        run.setTriggerEvent(triggerEvent(interaction));
        run = runRepository.save(run);

        Map<String, Object> context = contextService.create(run);
        run.setContext(context);
        run = runRepository.save(run);

        if (steps.isEmpty()) {
            run.setStatus(RUN_NOOP);
            run.setCompletedAt(LocalDateTime.now());
            return runRepository.save(run);
        }

        String status = RUN_PASSED;
        String error = null;
        for (WorkflowBusinessConfigAssembler.PlatformStep step : steps) {
            NodeWorkflowRunStepEntity stepEntity = executeStep(run, deployment, step, context);
            run.setContext(context);
            run = runRepository.save(run);
            if (!WorkflowPlatformStepExecutor.STATUS_FAILED.equals(stepEntity.getStatus())) {
                continue;
            }
            status = RUN_FAILED;
            error = stepEntity.getError() != null
                    ? stepEntity.getError()
                    : "node " + step.nodeId() + " returned " + stepEntity.getStatus();
            break;
        }
        run.setStatus(status);
        run.setError(error);
        run.setCompletedAt(LocalDateTime.now());
        return runRepository.save(run);
    }

    private NodeWorkflowRunStepEntity executeStep(NodeWorkflowRunEntity run,
                                                  NodeWorkflowDeploymentEntity deployment,
                                                  WorkflowBusinessConfigAssembler.PlatformStep step,
                                                  Map<String, Object> context) {
        String deviceId = deviceForSlot(deployment, step.slotId());
        NodeWorkflowRunStepEntity entity = new NodeWorkflowRunStepEntity();
        entity.setRunId(run.getId());
        entity.setWorkflowId(run.getWorkflowId());
        entity.setDeploymentId(run.getDeploymentId());
        entity.setNodeId(step.nodeId());
        entity.setSlotId(step.slotId());
        entity.setDeviceId(deviceId);
        entity.setNodeType(step.nodeType());
        try {
            Map<String, Object> resolved = resolveParams(step, context);
            entity.setInputParams(resolved);
            WorkflowPlatformStepExecutor.StepOutcome outcome = executor.execute(deviceId, step.nodeType(),
                    resolved, Map.of(
                            "workflowId", run.getWorkflowId(),
                            "deploymentId", run.getDeploymentId(),
                            "runId", run.getId(),
                            "triggerNodeId", run.getTriggerNodeId(),
                            "slotId", step.slotId(),
                            "nodeId", step.nodeId()
                    ));
            Map<String, Object> result = new LinkedHashMap<>(outcome.detail());
            result.put("platformStepReason", step.reason());
            entity.setStatus(outcome.ok()
                    ? outcome.status()
                    : WorkflowPlatformStepExecutor.STATUS_FAILED);
            entity.setResult(result);
            if (!outcome.ok()) {
                entity.setError(String.valueOf(outcome.detail().get("reason")));
            }
            putNodeResultIfPresent(context, run, step, deviceId, result);
        } catch (RuntimeException e) {
            entity.setStatus(WorkflowPlatformStepExecutor.STATUS_FAILED);
            entity.setResult(Map.of());
            entity.setError(e.getMessage());
        }
        // 用本地实例而不是 save 的返回值：状态与错误已经落在 entity 上，JPA 保存新实体返回的就是同一个
        // 实例，依赖返回值只会让调用方必须去 stub 仓储。
        stepRepository.save(entity);
        return entity;
    }

    private Map<String, Object> resolveParams(WorkflowBusinessConfigAssembler.PlatformStep step,
                                              Map<String, Object> context) {
        return parameterResolver.resolveParams(step.params(), step.nodeType(), context);
    }

    /**
     * 把步骤结果并入运行上下文，供后续步骤的 {@code $ref} 取值。
     *
     * <p>{@code NodeWorkflowRunContextService} 以节点模型为入参，而这里只有持久化的步骤快照，
     * 因此按需从当前定义取回节点。节点已被删除时不并入——此时后续的 {@code $ref} 也解析不到，
     * 与其猜测不如让它明确失败。</p>
     */
    private void putNodeResultIfPresent(Map<String, Object> context,
                                        NodeWorkflowRunEntity run,
                                        WorkflowBusinessConfigAssembler.PlatformStep step,
                                        String deviceId,
                                        Map<String, Object> result) {
        try {
            NodeWorkflowDefinition workflow = workflowService.workflow(run.getWorkflowId());
            NodeWorkflowNode node = findNode(workflow, step.nodeId());
            if (node != null) {
                contextService.putNodeResult(context, run, node, deviceId, result);
            }
        } catch (RuntimeException e) {
            log.debug("平台步骤结果未并入运行上下文: node={}, reason={}", step.nodeId(), e.getMessage());
        }
    }

    // ── 平台步骤来源 ────────────────────────────────────────────────────────

    /**
     * 取部署时固化给该设备、属于指定触发节点的平台步骤。
     *
     * <p>读部署记录而不是重新组装：设备侧的配置在部署那一刻已生效，平台侧必须与那一份对应。</p>
     */
    private List<WorkflowBusinessConfigAssembler.PlatformStep> platformStepsOf(
            NodeWorkflowDeploymentEntity deployment, String deviceId, String triggerNodeId) {
        Object raw = rawDeviceEntry(deployment, deviceId).get("platformSteps");
        if (!(raw instanceof List<?> list)) {
            return List.of();
        }
        List<WorkflowBusinessConfigAssembler.PlatformStep> steps = new ArrayList<>();
        for (Object item : list) {
            if (!(item instanceof Map<?, ?> map)) {
                continue;
            }
            WorkflowBusinessConfigAssembler.PlatformStep step =
                    WorkflowBusinessConfigAssembler.PlatformStep.fromMap(normalizeMap(map));
            if (triggerNodeId.equals(step.triggerNodeId())) {
                steps.add(step);
            }
        }
        return steps;
    }

    private Map<String, Object> rawDeviceEntry(NodeWorkflowDeploymentEntity deployment, String deviceId) {
        Map<String, Object> configs = deployment.getBusinessConfigs();
        if (configs == null || !(configs.get(deviceId) instanceof Map<?, ?> entry)) {
            return Map.of();
        }
        return normalizeMap(entry);
    }

    // ── 查找 ────────────────────────────────────────────────────────────────

    /**
     * 找该设备当前生效、且属于指定工作流的部署。
     *
     * <p>先按设备筛 slotBindings，再取最近一次。同一设备可被多个工作流绑到不同 slot，但同一 slot 只有
     * 一个活动部署（部署时会停掉冲突的旧部署），所以取最新即可。</p>
     */
    private NodeWorkflowDeploymentEntity activeDeployment(String workflowId, String deviceId) {
        List<NodeWorkflowDeploymentEntity> active =
                deploymentRepository.findByWorkflowIdAndStatusOrderByDeployedAtDesc(workflowId, "active");
        for (NodeWorkflowDeploymentEntity deployment : active) {
            if (hasSlotForDevice(deployment, deviceId)) {
                return deployment;
            }
        }
        return null;
    }

    private boolean hasSlotForDevice(NodeWorkflowDeploymentEntity deployment, String deviceId) {
        return NodeWorkflowSupport.stringMap(deployment.getSlotBindings()).containsValue(deviceId);
    }

    private String deviceForSlot(NodeWorkflowDeploymentEntity deployment, String slotId) {
        return NodeWorkflowSupport.stringMap(deployment.getSlotBindings()).get(slotId);
    }

    private NodeWorkflowNode firstTriggerNode(NodeWorkflowDefinition workflow) {
        return workflow.nodes().stream()
                .filter(node -> NodeWorkflowSupport.isTriggerNode(node.nodeType()))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("workflow has no trigger node"));
    }

    /**
     * 按 id 取节点，取不到时返回 {@code null}。
     *
     * <p>{@code NodeWorkflowSupport.nodeById} 取不到会抛异常；运行时面对的是"部署时的定义"与
     * "当前定义"可能不一致的现实，节点被删属于可预期的正常分支，不该以异常表达。</p>
     */
    private NodeWorkflowNode findNode(NodeWorkflowDefinition workflow, String nodeId) {
        if (nodeId == null || nodeId.isBlank()) {
            return null;
        }
        return workflow.nodes().stream()
                .filter(node -> nodeId.equals(node.nodeId()))
                .findFirst()
                .orElse(null);
    }

    // ── 序列化 ──────────────────────────────────────────────────────────────

    private Map<String, Object> triggerEvent(BusinessInteraction interaction) {
        Map<String, Object> event = new LinkedHashMap<>();
        event.put("source", "platform.interaction");
        event.put("deviceId", interaction.deviceId());
        event.put("triggerId", interaction.triggerId());
        event.put("contextRef", interaction.contextRef());
        event.put("receivedAt", interaction.receivedAt() != null ? interaction.receivedAt().toString() : null);
        return event;
    }

    private Map<String, Object> runToMap(NodeWorkflowRunEntity run, boolean includeSteps) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("runId", run.getId());
        data.put("workflowId", run.getWorkflowId());
        data.put("deploymentId", run.getDeploymentId());
        data.put("triggerNodeId", run.getTriggerNodeId());
        data.put("status", run.getStatus());
        data.put("event", run.getTriggerEvent());
        data.put("context", run.getContext());
        data.put("error", run.getError());
        data.put("startedAt", run.getStartedAt() != null ? run.getStartedAt().toString() : null);
        data.put("completedAt", run.getCompletedAt() != null ? run.getCompletedAt().toString() : null);
        if (includeSteps) {
            data.put("steps", stepRepository.findByRunIdOrderByCreatedAtAsc(run.getId()).stream()
                    .map(this::stepToMap)
                    .toList());
        }
        return data;
    }

    private Map<String, Object> stepToMap(NodeWorkflowRunStepEntity step) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("stepId", step.getId());
        data.put("runId", step.getRunId());
        data.put("nodeId", step.getNodeId());
        data.put("slotId", step.getSlotId());
        data.put("deviceId", step.getDeviceId());
        data.put("nodeType", step.getNodeType());
        data.put("status", step.getStatus());
        data.put("inputParams", step.getInputParams());
        data.put("resolvedParams", step.getInputParams());
        data.put("result", step.getResult());
        data.put("error", step.getError());
        data.put("createdAt", step.getCreatedAt() != null ? step.getCreatedAt().toString() : null);
        return data;
    }

    private static Map<String, Object> normalizeMap(Map<?, ?> raw) {
        Map<String, Object> map = new LinkedHashMap<>();
        for (Map.Entry<?, ?> entry : raw.entrySet()) {
            map.put(String.valueOf(entry.getKey()), entry.getValue());
        }
        return map;
    }
}
