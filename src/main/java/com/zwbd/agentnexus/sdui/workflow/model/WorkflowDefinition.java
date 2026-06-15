package com.zwbd.agentnexus.sdui.workflow.model;

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
import java.util.LinkedHashMap;
import java.util.Map;

@Data
@Entity
@Table(name = "sdui_workflow_definition")
public class WorkflowDefinition {

    @TenantId
    @Column(name = "space_id", nullable = false, updatable = false)
    private String spaceId;

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(length = 64)
    private String id;

    @Column(nullable = false, length = 128)
    private String name;

    @Column(length = 512)
    private String description;

    @Column(nullable = false, length = 24)
    private String status = "DRAFT";

    @Column(nullable = false)
    private int version = 1;

    @Convert(converter = WorkflowJsonConverter.class)
    @Column(nullable = false, columnDefinition = "text")
    private Map<String, Object> dag = new LinkedHashMap<>();

    @Convert(converter = WorkflowJsonConverter.class)
    @Column(nullable = false, columnDefinition = "text")
    private Map<String, Object> editorModel = new LinkedHashMap<>();

    @CreationTimestamp
    private LocalDateTime createdAt;

    @UpdateTimestamp
    private LocalDateTime updatedAt;
}
