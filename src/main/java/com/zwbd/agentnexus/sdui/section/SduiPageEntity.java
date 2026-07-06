package com.zwbd.agentnexus.sdui.section;

import jakarta.persistence.*;
import lombok.Data;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.TenantId;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.LocalDateTime;
import java.util.UUID;

@Data
@Entity
@Table(name = "sdui_page")
public class SduiPageEntity {

    @TenantId
    @Column(name = "user_id", updatable = false)
    private String userId;

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(length = 64, nullable = false, updatable = false)
    private String id;

    @Column(length = 128, nullable = false, unique = true)
    private String pageId;

    @Column(length = 128, nullable = false)
    private String name;

    @Column(length = 32, nullable = false)
    private String layout;

    @Column(nullable = false)
    private boolean autoScroll;

    @Column(nullable = false)
    private int autoScrollMs;

    @Column(columnDefinition = "TEXT")
    private String sectionsJson;

    @Column(length = 24, nullable = false)
    private String status;

    @CreationTimestamp
    @Column(updatable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    private LocalDateTime updatedAt;

    public static String generatePageId() {
        return "page_" + UUID.randomUUID().toString().substring(0, 8);
    }
}
