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
@Table(name = "trusted_script_instance")
public class SduiTrustedScriptInstance {

    @TenantId
    @Column(name = "space_id", nullable = false, updatable = false)
    private String spaceId;

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(length = 64)
    private String id;

    @Column(name = "app_id", nullable = false, length = 64)
    private String appId;

    @Column(name = "device_id", nullable = false, length = 64)
    private String deviceId;

    @Column(name = "version_no", nullable = false)
    private Integer versionNo;

    @Column(length = 120)
    private String host;

    @Column
    private Long pid;

    @Column(nullable = false, length = 24)
    private String state = "CREATED";

    @Column(nullable = false, length = 24)
    private String health = "UNKNOWN";

    @Column(name = "restart_count", nullable = false)
    private Integer restartCount = 0;

    @Column(name = "last_seen_at")
    private LocalDateTime lastSeenAt;

    @CreationTimestamp
    private LocalDateTime createdAt;

    @UpdateTimestamp
    private LocalDateTime updatedAt;
}
