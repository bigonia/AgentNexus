package com.zwbd.agentnexus.sdui.workflow;

import java.util.Optional;

/**
 * 平台侧上下文引用：把"哪次部署的哪个触发节点"编码进 token 注册表。
 *
 * <p>01_INTERACTION_MODEL.md §4 要求终端只上报 {@code device_id + token}，"平台根据设备和 token
 * 恢复业务上下文"。{@code contextRef} 就是这个恢复抓手——它随 token 一起登记在
 * {@code InteractionTokenService}，终端不感知也不回传。</p>
 *
 * <p>形态固定为 {@code wf:<workflowId>:<triggerNodeId>}。为什么不只记 {@code triggerId}：
 * 一个工作流里不同的部署可以把同一个物理按钮绑到不同 slot，仅凭 {@code triggerId} 无法区分
 * 是哪一次部署在等我；而 {@code triggerNodeId} 在一次工作流定义内唯一，配合 {@code workflowId}
 * 可以唯一定位。</p>
 *
 * <p>解析失败一律返回空而不是抛异常：{@code contextRef} 可能是非工作流场景写入的
 * {@code binding:<triggerId>}，运行时遇到这种引用应当安静跳过而不是让整条上报链失败。</p>
 *
 * @param workflowId    工作流定义 id
 * @param triggerNodeId 触发节点 id
 */
public record WorkflowContextRef(String workflowId, String triggerNodeId) {

    public static final String PREFIX = "wf:";

    public static WorkflowContextRef of(String workflowId, String triggerNodeId) {
        return new WorkflowContextRef(workflowId, triggerNodeId);
    }

    /** 供 {@code TriggerBinding.contextRef} 使用的字符串形式。 */
    public String encode() {
        return PREFIX + workflowId + ":" + triggerNodeId;
    }

    /**
     * 解析上下文引用。
     *
     * <p>{@code workflowId} 与 {@code triggerNodeId} 都不允许为空；两者之间以第一个 {@code ':'} 分隔，
     * 多余的冒号归入 {@code triggerNodeId}，避免节点 id 中出现冒号时解析错位。</p>
     */
    public static Optional<WorkflowContextRef> parse(String contextRef) {
        if (contextRef == null || !contextRef.startsWith(PREFIX)) {
            return Optional.empty();
        }
        String body = contextRef.substring(PREFIX.length());
        int separator = body.indexOf(':');
        if (separator <= 0 || separator == body.length() - 1) {
            return Optional.empty();
        }
        String workflowId = body.substring(0, separator);
        String triggerNodeId = body.substring(separator + 1);
        if (workflowId.isBlank() || triggerNodeId.isBlank()) {
            return Optional.empty();
        }
        return Optional.of(new WorkflowContextRef(workflowId, triggerNodeId));
    }
}
