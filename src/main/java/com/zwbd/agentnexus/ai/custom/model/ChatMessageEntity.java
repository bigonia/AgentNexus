package com.zwbd.agentnexus.ai.custom.model;

import jakarta.persistence.*;
import lombok.Data;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * @Author: wnli
 * @Date: 2025/9/18 15:47
 * @Desc: 手动管理历史记录
 */
@Entity
@Table(name = "conversation_history")
@Data
public class ChatMessageEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.AUTO)
    @Column(name = "message_id", updatable = false, nullable = false)
    private UUID messageId;

    @Column(name = "session_id", nullable = false)
    private String sessionId;

    @Column(name = "message_order", nullable = false)
    private int messageOrder;

    @Column(name = "role", nullable = false)
    private String role;

    @Column(name = "content", columnDefinition = "TEXT")
    private String content;

    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;

    @PrePersist
    protected void onCreate() {
        createdAt = OffsetDateTime.now();
    }

}
