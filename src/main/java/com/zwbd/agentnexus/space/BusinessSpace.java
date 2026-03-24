package com.zwbd.agentnexus.space;

import jakarta.persistence.*;
import lombok.Data;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.LocalDateTime;

/**
 * @Author: wnli
 * @Date: 2025/11/24 10:10
 * @Desc:
 */
@Data
@Entity
@Table(name = "business_spaces")
public class BusinessSpace {
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(length = 64)
    private String id; // 空间ID
    @Column(nullable = false, length = 100)
    private String name;
    @Column(length = 500)
    private String description;
    @CreationTimestamp
    private LocalDateTime createdAt;
    @UpdateTimestamp
    private LocalDateTime updatedAt;
}
