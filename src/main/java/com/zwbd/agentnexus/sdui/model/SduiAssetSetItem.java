package com.zwbd.agentnexus.sdui.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
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
@Table(name = "sdui_asset_set_item")
@JsonIgnoreProperties({"hibernateLazyInitializer", "handler"})
public class SduiAssetSetItem {

    @TenantId
    @Column(name = "space_id", nullable = false, updatable = false)
    private String spaceId;

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(length = 64)
    private String id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "asset_set_id", nullable = false)
    private SduiAssetSet assetSet;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "asset_id", nullable = false)
    private SduiAsset asset;

    @Column(nullable = false)
    private Integer itemOrder = 0;

    @CreationTimestamp
    private LocalDateTime createdAt;
}
