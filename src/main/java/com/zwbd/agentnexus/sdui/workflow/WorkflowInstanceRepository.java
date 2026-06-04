package com.zwbd.agentnexus.sdui.workflow;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface WorkflowInstanceRepository extends JpaRepository<WorkflowInstanceEntity, String> {

    Optional<WorkflowInstanceEntity> findByDeviceIdAndStatus(String deviceId, String status);

    Optional<WorkflowInstanceEntity> findByDeviceIdAndDefinitionId(String deviceId, String definitionId);

    List<WorkflowInstanceEntity> findByDeviceId(String deviceId);

    void deleteByDeviceId(String deviceId);

    void deleteByDeviceIdAndDefinitionId(String deviceId, String definitionId);
}
