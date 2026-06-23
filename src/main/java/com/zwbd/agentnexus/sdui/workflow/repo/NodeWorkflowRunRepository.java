package com.zwbd.agentnexus.sdui.workflow.repo;

import com.zwbd.agentnexus.sdui.workflow.entity.NodeWorkflowRunEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface NodeWorkflowRunRepository extends JpaRepository<NodeWorkflowRunEntity, String> {
    List<NodeWorkflowRunEntity> findByDeploymentIdOrderByStartedAtDesc(String deploymentId);
    List<NodeWorkflowRunEntity> findByWorkflowIdOrderByStartedAtDesc(String workflowId);
    List<NodeWorkflowRunEntity> findByStatusOrderByStartedAtDesc(String status);
    List<NodeWorkflowRunEntity> findAllByOrderByStartedAtDesc();
    Optional<NodeWorkflowRunEntity> findByWorkflowIdAndId(String workflowId, String id);
}
