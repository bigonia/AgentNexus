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
import org.hibernate.annotations.UpdateTimestamp;

import java.time.LocalDateTime;

@Data
@Entity
@Table(name = "sdui_device_command")
public class SduiDeviceCommand {

    @TenantId
    @Column(name = "space_id", nullable = false, updatable = false)
    private String spaceId;

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(length = 64)
    private String id;

    @Column(nullable = false, length = 64)
    private String deviceId;

    @Column(nullable = false, length = 64)
    private String topic;

    @Column(nullable = false, length = 64)
    private String cmdId;

    @Column(nullable = false, length = 32)
    private String action;

    private Integer requestedValue;
    private Integer appliedValue;

    @Column(length = 256)
    private String reason;

    private Long ackTs;

    @Column(nullable = false, columnDefinition = "text")
    private String payload;

    @Column(nullable = false, length = 16)
    private String status = "SENT";

    @CreationTimestamp
    private LocalDateTime createdAt;

    @UpdateTimestamp
    private LocalDateTime updatedAt;
}
