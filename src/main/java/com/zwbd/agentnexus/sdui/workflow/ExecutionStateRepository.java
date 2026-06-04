package com.zwbd.agentnexus.sdui.workflow;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface ExecutionStateRepository extends JpaRepository<ExecutionStateEntity, String> {

    List<ExecutionStateEntity> findByDeviceIdAndStatus(String deviceId, String status);

    Optional<ExecutionStateEntity> findByDeviceIdAndDefinitionIdAndTriggerIdAndStatus(
            String deviceId, String definitionId, String triggerId, String status);

    List<ExecutionStateEntity> findByStatus(String status);

    void deleteByDeviceIdAndDefinitionId(String deviceId, String definitionId);
}
