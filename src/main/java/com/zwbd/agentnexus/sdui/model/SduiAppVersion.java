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
@Table(name = "sdui_app_version")
public class SduiAppVersion {

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

    @Column(nullable = false)
    private Integer versionNo;

    @Column(nullable = false, columnDefinition = "text")
    private String scriptContent;

    @Column(columnDefinition = "text")
    private String llmPromptSnapshot;

    @Column(columnDefinition = "text")
    private String validationReport;

    @Column(nullable = false)
    private Boolean published = false;

    @CreationTimestamp
    private LocalDateTime createdAt;
}

