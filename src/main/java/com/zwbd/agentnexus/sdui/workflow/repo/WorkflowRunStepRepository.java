package com.zwbd.agentnexus.sdui.workflow.repo;

import com.zwbd.agentnexus.sdui.workflow.model.WorkflowRunStep;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface WorkflowRunStepRepository extends JpaRepository<WorkflowRunStep, String> {
    List<WorkflowRunStep> findByRunIdOrderByCreatedAtAsc(String runId);
}
