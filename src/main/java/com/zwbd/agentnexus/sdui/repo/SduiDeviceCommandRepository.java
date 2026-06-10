package com.zwbd.agentnexus.sdui.repo;

import com.zwbd.agentnexus.sdui.model.SduiDeviceCommand;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

public interface SduiDeviceCommandRepository extends JpaRepository<SduiDeviceCommand, String> {
    long countByStatus(String status);

    Optional<SduiDeviceCommand> findFirstByDeviceIdAndCmdIdOrderByCreatedAtDesc(String deviceId, String cmdId);

    List<SduiDeviceCommand> findByStatusAndCreatedAtBefore(String status, LocalDateTime time);

    List<SduiDeviceCommand> findTop10ByDeviceIdOrderByCreatedAtDesc(String deviceId);

    /** Fetch up to N most recent commands for a device (for stats calculation). */
    @Query("SELECT c FROM SduiDeviceCommand c WHERE c.deviceId = :deviceId ORDER BY c.createdAt DESC")
    List<SduiDeviceCommand> findTopNByDeviceIdOrderByCreatedAtDesc(@Param("deviceId") String deviceId, Pageable pageable);

    @Query("SELECT c FROM SduiDeviceCommand c WHERE c.deviceId = :deviceId ORDER BY c.createdAt DESC")
    List<SduiDeviceCommand> findHistoryByDeviceId(@Param("deviceId") String deviceId, Pageable pageable);

    void deleteByDeviceId(String deviceId);
}
