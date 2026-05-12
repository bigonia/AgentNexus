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
@Table(name = "trusted_script_deploy")
public class SduiTrustedScriptDeploy {

    @TenantId
    @Column(name = "space_id", nullable = false, updatable = false)
    private String spaceId;

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(length = 64)
    private String id;

    @Column(name = "app_id", nullable = false, length = 64)
    private String appId;

    @Column(name = "version_no", nullable = false)
    private Integer versionNo;

    @Column(name = "device_id", nullable = false, length = 64)
    private String deviceId;

    @Column(nullable = false, length = 24)
    private String status = "DEPLOYED";

    @Column(name = "rollout_batch", nullable = false, length = 32)
    private String rolloutBatch = "MANUAL";

    @CreationTimestamp
    private LocalDateTime createdAt;
}
