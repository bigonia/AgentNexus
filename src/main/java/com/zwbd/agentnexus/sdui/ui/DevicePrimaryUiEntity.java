package com.zwbd.agentnexus.sdui.ui;

import jakarta.persistence.*;
import lombok.Data;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.TenantId;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.LocalDateTime;

@Data
@Entity
@Table(name = "sdui_device_primary_ui",
        indexes = {
                @Index(name = "idx_sdui_device_primary_ui_device", columnList = "device_id", unique = true),
                @Index(name = "idx_sdui_device_primary_ui_deployment", columnList = "deployment_id")
        })
public class DevicePrimaryUiEntity {

    @TenantId
    @Column(name = "user_id", updatable = false)
    private String userId;

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(length = 64)
    private String id;

    @Column(name = "device_id", nullable = false, length = 128)
    private String deviceId;

    @Column(name = "workflow_id", nullable = false, length = 64)
    private String workflowId;

    @Column(name = "deployment_id", nullable = false, length = 64)
    private String deploymentId;

    @Column(nullable = false, length = 64)
    private String slotId;

    @Column(nullable = false, length = 128)
    private String templateKey;

    @CreationTimestamp
    private LocalDateTime createdAt;

    @UpdateTimestamp
    private LocalDateTime updatedAt;
}
