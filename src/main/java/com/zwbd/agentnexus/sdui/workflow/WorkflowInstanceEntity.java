package com.zwbd.agentnexus.sdui.workflow;

import jakarta.persistence.*;
import lombok.Data;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.LocalDateTime;

@Data
@Entity
@Table(name = "sdui_workflow_instance")
public class WorkflowInstanceEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private String id;

    @Column(nullable = false, length = 64)
    private String deviceId;

    @Column(nullable = false, length = 64)
    private String definitionId;

    @Column(columnDefinition = "TEXT")
    private String variablesJson;

    @Column(length = 32)
    private String activePage;

    @Column(nullable = false, length = 16)
    private String status = "RUNNING";

    @CreationTimestamp
    private LocalDateTime createdAt;

    @UpdateTimestamp
    private LocalDateTime updatedAt;
}
