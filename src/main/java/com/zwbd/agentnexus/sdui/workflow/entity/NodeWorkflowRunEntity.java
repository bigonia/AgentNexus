package com.zwbd.agentnexus.sdui.workflow.entity;

import com.zwbd.agentnexus.common.web.JsonMapConverter;
import jakarta.persistence.*;
import lombok.Data;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.TenantId;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.Map;

@Data
@Entity
@Table(name = "sdui_node_workflow_run")
public class NodeWorkflowRunEntity {

    @TenantId
    @Column(name = "user_id", updatable = false)
    private String userId;

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(length = 64)
    private String id;

    @Column(nullable = false, length = 64)
    private String workflowId;

    @Column(nullable = false, length = 64)
    private String deploymentId;

    @Column(nullable = false, length = 64)
    private String triggerNodeId;

    @Column(nullable = false, length = 24)
    private String status = "running";

    @Convert(converter = JsonMapConverter.class)
    @Column(nullable = false, columnDefinition = "text")
    private Map<String, Object> triggerEvent = new LinkedHashMap<>();

    @Convert(converter = JsonMapConverter.class)
    @Column(nullable = false, columnDefinition = "text")
    private Map<String, Object> context = new LinkedHashMap<>();

    @Column(length = 512)
    private String error;

    @CreationTimestamp
    private LocalDateTime startedAt;

    private LocalDateTime completedAt;
}
