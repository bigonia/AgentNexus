package com.zwbd.agentnexus.sdui.repo;

import com.zwbd.agentnexus.sdui.model.DeviceConnectionLog;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.List;

public interface DeviceConnectionLogRepository extends JpaRepository<DeviceConnectionLog, Long> {

    List<DeviceConnectionLog> findTop50ByDeviceIdOrderByEventAtDesc(String deviceId);

    List<DeviceConnectionLog> findByDeviceIdAndEventAtBetweenOrderByEventAtAsc(
            String deviceId, LocalDateTime start, LocalDateTime end);

    long countByDeviceIdAndEventType(String deviceId, String eventType);

    void deleteByDeviceId(String deviceId);

    @Query("SELECT d.disconnectReason, COUNT(d) FROM DeviceConnectionLog d " +
           "WHERE d.deviceId = :deviceId AND d.eventType = 'DISCONNECTED' " +
           "GROUP BY d.disconnectReason")
    List<Object[]> countDisconnectReasonsByDeviceId(@Param("deviceId") String deviceId);
}
