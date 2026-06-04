package com.zwbd.agentnexus.sdui.workflow;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface ExecutionRecordRepository extends JpaRepository<ExecutionRecordEntity, String> {

    List<ExecutionRecordEntity> findByDeviceIdAndDefinitionIdOrderByStartTimeDesc(
            String deviceId, String definitionId, Pageable pageable);

    List<ExecutionRecordEntity> findByDeviceIdOrderByStartTimeDesc(String deviceId, Pageable pageable);

    void deleteByDeviceId(String deviceId);

    void deleteByDeviceIdAndDefinitionId(String deviceId, String definitionId);
}
