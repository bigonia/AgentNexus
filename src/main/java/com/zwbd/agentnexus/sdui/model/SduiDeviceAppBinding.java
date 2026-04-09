package com.zwbd.agentnexus.sdui.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.Data;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.TenantId;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.LocalDateTime;

@Data
@Entity
@Table(name = "sdui_device_app_binding")
public class SduiDeviceAppBinding {

    @TenantId
    @Column(name = "space_id", nullable = false, updatable = false)
    private String spaceId;

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(length = 64)
    private String id;

    @Column(nullable = false, length = 64)
    private String deviceId;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "app_id", nullable = false)
    private SduiApp app;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "app_version_id", nullable = false)
    private SduiAppVersion appVersion;

    @Column(nullable = false)
    private Boolean active = true;

    @CreationTimestamp
    private LocalDateTime boundAt;

    @UpdateTimestamp
    private LocalDateTime updatedAt;
}

