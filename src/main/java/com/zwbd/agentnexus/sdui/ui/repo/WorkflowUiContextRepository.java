package com.zwbd.agentnexus.sdui.ui.repo;

import com.zwbd.agentnexus.sdui.ui.WorkflowUiContextEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface WorkflowUiContextRepository extends JpaRepository<WorkflowUiContextEntity, String> {
    List<WorkflowUiContextEntity> findByDeploymentIdOrderByUpdatedAtDesc(String deploymentId);
    List<WorkflowUiContextEntity> findByWorkflowIdAndDeploymentIdOrderByUpdatedAtDesc(String workflowId, String deploymentId);
    Optional<WorkflowUiContextEntity> findByDeploymentIdAndSlotIdAndTemplateKey(String deploymentId, String slotId, String templateKey);
    List<WorkflowUiContextEntity> findByDeploymentIdAndSlotId(String deploymentId, String slotId);
}
