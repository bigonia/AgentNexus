package com.zwbd.agentnexus.sdui.ui;

import com.zwbd.agentnexus.sdui.statemachine.model.StateMachineJsonConverter;
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
@Table(name = "sdui_workflow_ui_context")
public class WorkflowUiContextEntity {

    @TenantId
    @Column(name = "space_id", nullable = false, updatable = false)
    private String spaceId;

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(length = 64)
    private String id;

    @Column(nullable = false, length = 64)
    private String workflowId;

    @Column(nullable = false, length = 64)
    private String deploymentId;

    @Column(nullable = false, length = 64)
    private String slotId;

    @Column(nullable = false, length = 128)
    private String templateKey;

    @Column(nullable = false, length = 128)
    private String deviceId;

    @Column(nullable = false, length = 128)
    private String activePageId = "main";

    @Convert(converter = StateMachineJsonConverter.class)
    @Column(nullable = false, columnDefinition = "text")
    private Map<String, Object> context = new LinkedHashMap<>();

    @CreationTimestamp
    private LocalDateTime createdAt;

    @UpdateTimestamp
    private LocalDateTime updatedAt;
}
