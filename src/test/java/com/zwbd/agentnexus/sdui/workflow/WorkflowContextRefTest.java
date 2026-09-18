package com.zwbd.agentnexus.sdui.workflow;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 平台侧上下文引用的编解码。
 *
 * <p>它是"终端只上报 device_id + token"与"平台需要恢复业务上下文"之间唯一的桥，因此边界情况
 * （非工作流引用、缺字段、节点 id 里带冒号）必须固定住。</p>
 */
class WorkflowContextRefTest {

    @Test
    @DisplayName("编码后能原样解回")
    void roundTrip() {
        WorkflowContextRef ref = WorkflowContextRef.of("wf-1", "source_button");
        assertEquals("wf:wf-1:source_button", ref.encode());
        assertEquals(Optional.of(ref), WorkflowContextRef.parse(ref.encode()));
    }

    @Test
    @DisplayName("节点 id 含冒号时只按第一个冒号切分")
    void keepsColonsInNodeId() {
        Optional<WorkflowContextRef> ref = WorkflowContextRef.parse("wf:wf-1:slot:button");
        assertTrue(ref.isPresent());
        assertEquals("wf-1", ref.get().workflowId());
        assertEquals("slot:button", ref.get().triggerNodeId());
    }

    @Test
    @DisplayName("非工作流引用解析为空，由调用方安静跳过")
    void rejectsNonWorkflowRefs() {
        assertTrue(WorkflowContextRef.parse("binding:button.ok").isEmpty());
        assertTrue(WorkflowContextRef.parse(null).isEmpty());
        assertTrue(WorkflowContextRef.parse("").isEmpty());
        assertTrue(WorkflowContextRef.parse("wf:").isEmpty());
        assertTrue(WorkflowContextRef.parse("wf:wf-1").isEmpty());
        assertTrue(WorkflowContextRef.parse("wf:wf-1:").isEmpty());
        assertTrue(WorkflowContextRef.parse("wf::trigger").isEmpty());
    }
}
