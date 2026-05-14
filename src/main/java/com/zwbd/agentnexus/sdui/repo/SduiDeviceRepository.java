package com.zwbd.agentnexus.sdui.repo;

import com.zwbd.agentnexus.sdui.model.SduiDevice;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.List;

public interface SduiDeviceRepository extends JpaRepository<SduiDevice, String> {
    List<SduiDevice> findByOwnerSpaceId(String ownerSpaceId);

    long countByOwnerSpaceId(String ownerSpaceId);

    long countByOwnerSpaceIdAndStatusIgnoreCase(String ownerSpaceId, String status);

    List<SduiDevice> findByRegistrationStatus(String registrationStatus);

    @Modifying
    @Query("UPDATE SduiDevice d SET d.status = :newStatus WHERE d.lastSeenAt < :threshold AND d.status = :currentStatus")
    int markOfflineDevices(@Param("threshold") LocalDateTime threshold,
                           @Param("newStatus") String newStatus,
                           @Param("currentStatus") String currentStatus);
}
