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
@Table(name = "sdui_node_workflow_deployment")
public class NodeWorkflowDeploymentEntity {

    @TenantId
    @Column(name = "user_id", updatable = false)
    private String userId;

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(length = 64)
    private String id;

    @Column(nullable = false, length = 64)
    private String workflowId;

    @Column(nullable = false)
    private Integer workflowVersion = 1;

    @Column(nullable = false, length = 24)
    private String status = "active";

    @Convert(converter = JsonMapConverter.class)
    @Column(nullable = false, columnDefinition = "text")
    private Map<String, Object> slotBindings = new LinkedHashMap<>();

    @CreationTimestamp
    private LocalDateTime deployedAt;

    private LocalDateTime stoppedAt;
}
