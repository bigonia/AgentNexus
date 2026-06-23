package com.zwbd.agentnexus.sdui.workflow.repo;

import com.zwbd.agentnexus.sdui.workflow.entity.NodeWorkflowRunStepEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface NodeWorkflowRunStepRepository extends JpaRepository<NodeWorkflowRunStepEntity, String> {
    List<NodeWorkflowRunStepEntity> findByRunIdOrderByCreatedAtAsc(String runId);
}
