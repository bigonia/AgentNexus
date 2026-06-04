package com.zwbd.agentnexus.sdui.workflow;

import jakarta.persistence.*;
import lombok.Data;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.LocalDateTime;

@Data
@Entity
@Table(name = "sdui_workflow_definition")
public class WorkflowDefinitionEntity {

    @Id
    @Column(length = 64)
    private String id;

    @Column(nullable = false, length = 100)
    private String name;

    @Column(length = 32)
    private String icon;

    @Column(length = 32)
    private String category;

    @Column(columnDefinition = "integer default 1")
    private Integer version = 1;

    @Column(length = 16, columnDefinition = "varchar(16) default 'draft'")
    private String status = "draft";

    @Column(nullable = false, columnDefinition = "TEXT")
    private String definitionJson;

    @Column(columnDefinition = "TEXT")
    private String requiredCapsJson;

    @CreationTimestamp
    private LocalDateTime createdAt;

    @UpdateTimestamp
    private LocalDateTime updatedAt;
}
