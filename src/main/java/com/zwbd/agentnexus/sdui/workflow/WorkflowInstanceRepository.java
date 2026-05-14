package com.zwbd.agentnexus.sdui.workflow;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface WorkflowInstanceRepository extends JpaRepository<WorkflowInstanceEntity, String> {

    Optional<WorkflowInstanceEntity> findByDeviceIdAndStatus(String deviceId, String status);

    void deleteByDeviceId(String deviceId);
}
