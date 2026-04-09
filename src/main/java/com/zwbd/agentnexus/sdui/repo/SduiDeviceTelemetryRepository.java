package com.zwbd.agentnexus.sdui.repo;

import com.zwbd.agentnexus.sdui.model.SduiDeviceTelemetry;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface SduiDeviceTelemetryRepository extends JpaRepository<SduiDeviceTelemetry, Long> {
    List<SduiDeviceTelemetry> findTop50ByDeviceIdOrderByCreatedAtDesc(String deviceId);
}

