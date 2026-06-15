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

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.Map;

@Data
@Entity
@Table(name = "sdui_workflow_run_step")
public class WorkflowRunStep {

    @TenantId
    @Column(name = "space_id", nullable = false, updatable = false)
    private String spaceId;

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(length = 64)
    private String id;

    @Column(nullable = false, length = 64)
    private String runId;

    @Column(nullable = false, length = 128)
    private String nodeId;

    @Column(nullable = false, length = 48)
    private String nodeType;

    @Column(nullable = false, length = 24)
    private String status = "RUNNING";

    @Convert(converter = WorkflowJsonConverter.class)
    @Column(nullable = false, columnDefinition = "text")
    private Map<String, Object> inputPayload = new LinkedHashMap<>();

    @Convert(converter = WorkflowJsonConverter.class)
    @Column(nullable = false, columnDefinition = "text")
    private Map<String, Object> outputPayload = new LinkedHashMap<>();

    @Column(columnDefinition = "text")
    private String errorMessage;

    private Long durationMs;

    @CreationTimestamp
    private LocalDateTime createdAt;
}
