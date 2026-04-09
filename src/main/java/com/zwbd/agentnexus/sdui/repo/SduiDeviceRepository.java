package com.zwbd.agentnexus.sdui.repo;

import com.zwbd.agentnexus.sdui.model.SduiDevice;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface SduiDeviceRepository extends JpaRepository<SduiDevice, String> {
    List<SduiDevice> findByOwnerSpaceId(String ownerSpaceId);

    long countByOwnerSpaceId(String ownerSpaceId);

    long countByOwnerSpaceIdAndStatusIgnoreCase(String ownerSpaceId, String status);

    List<SduiDevice> findByRegistrationStatus(String registrationStatus);
}
