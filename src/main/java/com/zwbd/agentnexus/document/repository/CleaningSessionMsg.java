package com.zwbd.agentnexus.document.repository;

import jakarta.persistence.*;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * @Author: wnli
 * @Date: 2025/12/9 10:58
 * @Desc:
 */
@Entity
@Data
@Table(name = "cleaning_session_msg")
public class CleaningSessionMsg {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private Long jobId;

    @Enumerated(EnumType.STRING)
    private Role role;

    @Lob
    @Column(columnDefinition = "TEXT")
    private String content;

    private LocalDateTime createdAt;

    public enum Role {
        USER,   // 用户输入
        AI,     // AI 回复的解释或代码
        SYSTEM  // 系统自动捕获的报错日志
    }
}