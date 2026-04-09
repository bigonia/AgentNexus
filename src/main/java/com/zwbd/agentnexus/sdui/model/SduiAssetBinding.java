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

import java.time.LocalDateTime;

@Data
@Entity
@Table(name = "sdui_asset_binding")
public class SduiAssetBinding {

    @TenantId
    @Column(name = "space_id", nullable = false, updatable = false)
    private String spaceId;

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(length = 64)
    private String id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "app_id", nullable = false)
    private SduiApp app;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "asset_id", nullable = false)
    private SduiAsset asset;

    @Column(nullable = false, length = 32)
    private String usageType;

    @CreationTimestamp
    private LocalDateTime createdAt;
}

