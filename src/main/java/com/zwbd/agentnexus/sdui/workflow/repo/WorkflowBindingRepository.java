package com.zwbd.agentnexus.sdui.workflow.repo;

import com.zwbd.agentnexus.sdui.workflow.model.WorkflowBinding;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface WorkflowBindingRepository extends JpaRepository<WorkflowBinding, String> {
    List<WorkflowBinding> findByWorkflowId(String workflowId);
    List<WorkflowBinding> findByDeviceIdAndEnabledTrueAndBindingStatus(String deviceId, String bindingStatus);
    Optional<WorkflowBinding> findFirstByWorkflowIdAndDeviceId(String workflowId, String deviceId);
}
