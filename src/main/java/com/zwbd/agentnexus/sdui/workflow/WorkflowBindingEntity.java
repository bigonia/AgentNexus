package com.zwbd.agentnexus.sdui.workflow;

import jakarta.persistence.*;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@Entity
@Table(name = "sdui_workflow_binding", indexes = {
        @Index(name = "idx_binding_device", columnList = "deviceId"),
        @Index(name = "idx_binding_device_def", columnList = "deviceId, definitionId", unique = true)
})
public class WorkflowBindingEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private String id;

    @Column(nullable = false)
    private String deviceId;

    @Column(nullable = false)
    private String definitionId;

    @Column(nullable = false)
    private String definitionName;

    private Integer version;

    @Column(nullable = false)
    private String status = "active";

    @Column(columnDefinition = "TEXT")
    private String bindingsJson;

    @Column(columnDefinition = "TEXT")
    private String envConfigJson;

    @Column(nullable = false)
    private LocalDateTime installedAt = LocalDateTime.now();

    private LocalDateTime updatedAt;
}
