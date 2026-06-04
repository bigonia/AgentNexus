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
@Table(name = "sdui_device_connection_log")
public class DeviceConnectionLog {

    @TenantId
    @Column(name = "space_id", nullable = false, updatable = false)
    private String spaceId;

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 64)
    private String deviceId;

    @Column(nullable = false, length = 16)
    private String eventType;   // CONNECTED, DISCONNECTED

    @Column(length = 128)
    private String sessionId;

    @Column(length = 45)
    private String ipAddress;

    @Column(length = 64)
    private String disconnectReason; // session_closed, timeout, server_shutdown, duplicate_login

    @Column(nullable = false)
    private LocalDateTime eventAt;

    @CreationTimestamp
    private LocalDateTime createdAt;
}
