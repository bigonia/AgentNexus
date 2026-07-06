package com.zwbd.agentnexus.sdui.workflow.entity;

import com.zwbd.agentnexus.common.web.JsonMapConverter;
import jakarta.persistence.*;
import lombok.Data;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.TenantId;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.Map;

@Data
@Entity
@Table(name = "sdui_node_workflow_definition")
public class NodeWorkflowDefinitionEntity {

    @TenantId
    @Column(name = "user_id", updatable = false)
    private String userId;

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(length = 64)
    private String id;

    @Column(nullable = false, length = 128)
    private String name;

    @Column(nullable = false, length = 24)
    private String status = "draft";

    @Column(nullable = false)
    private Integer version = 1;

    @Column(nullable = false, length = 32)
    private String source = "api";

    @Convert(converter = JsonMapConverter.class)
    @Column(nullable = false, columnDefinition = "text")
    private Map<String, Object> definition = new LinkedHashMap<>();

    @CreationTimestamp
    private LocalDateTime createdAt;

    @UpdateTimestamp
    private LocalDateTime updatedAt;
}
