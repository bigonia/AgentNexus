package com.zwbd.agentnexus.sdui.workflow.repo;

import com.zwbd.agentnexus.sdui.workflow.model.WorkflowRun;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface WorkflowRunRepository extends JpaRepository<WorkflowRun, String> {
    List<WorkflowRun> findByWorkflowIdOrderByCreatedAtDesc(String workflowId, Pageable pageable);
}
