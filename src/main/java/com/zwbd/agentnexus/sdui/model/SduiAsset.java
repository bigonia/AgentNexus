package com.zwbd.agentnexus.sdui.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.zwbd.agentnexus.utils.StringListConverter;
import jakarta.persistence.Column;
import jakarta.persistence.Convert;
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
import java.util.ArrayList;
import java.util.List;

@Data
@Entity
@Table(name = "sdui_asset")
@JsonIgnoreProperties({"hibernateLazyInitializer", "handler"})
public class SduiAsset {

    @TenantId
    @Column(name = "space_id", nullable = false, updatable = false)
    private String spaceId;

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(length = 64)
    private String id;

    @Column(nullable = false)
    private Long fileId;

    @Column(nullable = false, length = 32)
    private String assetType;

    @Column(nullable = false, length = 120)
    private String name;

    @Convert(converter = StringListConverter.class)
    @Column(columnDefinition = "text")
    private List<String> tags = new ArrayList<>();

    @Column(nullable = false, length = 16)
    private String processedStatus = "READY";

    @Column(columnDefinition = "text")
    private String processedPayload = "{}";

    @CreationTimestamp
    private LocalDateTime createdAt;

    @UpdateTimestamp
    private LocalDateTime updatedAt;
}
