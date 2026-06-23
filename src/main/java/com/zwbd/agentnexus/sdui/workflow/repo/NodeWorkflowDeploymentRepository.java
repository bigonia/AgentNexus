package com.zwbd.agentnexus.sdui.workflow.repo;

import com.zwbd.agentnexus.sdui.workflow.entity.NodeWorkflowDeploymentEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface NodeWorkflowDeploymentRepository extends JpaRepository<NodeWorkflowDeploymentEntity, String> {
    List<NodeWorkflowDeploymentEntity> findAllByOrderByDeployedAtDesc();
    List<NodeWorkflowDeploymentEntity> findByWorkflowIdOrderByDeployedAtDesc(String workflowId);
    List<NodeWorkflowDeploymentEntity> findByWorkflowIdAndStatusOrderByDeployedAtDesc(String workflowId, String status);
    List<NodeWorkflowDeploymentEntity> findByStatus(String status);
    List<NodeWorkflowDeploymentEntity> findByStatusOrderByDeployedAtDesc(String status);
}
