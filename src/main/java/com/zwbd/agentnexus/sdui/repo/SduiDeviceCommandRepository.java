package com.zwbd.agentnexus.sdui.repo;

import com.zwbd.agentnexus.sdui.model.SduiDeviceCommand;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

public interface SduiDeviceCommandRepository extends JpaRepository<SduiDeviceCommand, String> {
    long countByStatus(String status);

    Optional<SduiDeviceCommand> findFirstByDeviceIdAndCmdIdOrderByCreatedAtDesc(String deviceId, String cmdId);

    List<SduiDeviceCommand> findByStatusAndCreatedAtBefore(String status, LocalDateTime time);
}
