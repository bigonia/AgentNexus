package com.zwbd.agentnexus.sdui.workflow;

import jakarta.persistence.*;
import lombok.Data;
import org.hibernate.annotations.CreationTimestamp;

import java.time.LocalDateTime;

@Data
@Entity
@Table(name = "sdui_execution_state")
public class ExecutionStateEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private String id;

    @Column(nullable = false, length = 64)
    private String deviceId;

    @Column(nullable = false, length = 64)
    private String definitionId;

    @Column(nullable = false, length = 64)
    private String triggerId;

    @Column(length = 64)
    private String currentNode;

    @Column(length = 64)
    private String nextNode;

    @Column(columnDefinition = "TEXT")
    private String variablesJson;

    @Column(columnDefinition = "TEXT")
    private String triggerPayloadJson;

    @Column(length = 64)
    private String resumeEvent;

    private LocalDateTime timeoutAt;

    @Column(nullable = false, length = 16)
    private String status = "SUSPENDED";

    @CreationTimestamp
    private LocalDateTime createdAt;
}
