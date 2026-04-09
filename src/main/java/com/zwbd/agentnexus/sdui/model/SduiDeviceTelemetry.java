package com.zwbd.agentnexus.sdui.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Data;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.TenantId;

import java.time.LocalDateTime;

@Data
@Entity
@Table(name = "sdui_device_telemetry")
public class SduiDeviceTelemetry {

    @TenantId
    @Column(name = "space_id", nullable = false, updatable = false)
    private String spaceId;

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 64)
    private String deviceId;

    private Integer wifiRssi;
    private Double temperature;
    private Integer freeHeapInternal;
    private Integer freeHeapTotal;
    private Integer uptimeS;

    @CreationTimestamp
    private LocalDateTime createdAt;
}

