package com.zwbd.agentnexus.sdui.ui.repo;

import com.zwbd.agentnexus.sdui.ui.DevicePrimaryUiEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.List;

public interface DevicePrimaryUiRepository extends JpaRepository<DevicePrimaryUiEntity, String> {
    Optional<DevicePrimaryUiEntity> findByDeviceId(String deviceId);
    List<DevicePrimaryUiEntity> findByDeploymentId(String deploymentId);
    Optional<DevicePrimaryUiEntity> findByDeviceIdAndDeploymentIdAndSlotIdAndTemplateKey(
            String deviceId, String deploymentId, String slotId, String templateKey);
}
