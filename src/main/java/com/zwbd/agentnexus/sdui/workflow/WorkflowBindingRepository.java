package com.zwbd.agentnexus.sdui.workflow;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface WorkflowBindingRepository extends JpaRepository<WorkflowBindingEntity, String> {

    Optional<WorkflowBindingEntity> findByDeviceIdAndDefinitionId(String deviceId, String definitionId);

    List<WorkflowBindingEntity> findByDeviceId(String deviceId);

    List<WorkflowBindingEntity> findByDefinitionId(String definitionId);

    void deleteByDeviceIdAndDefinitionId(String deviceId, String definitionId);
}
