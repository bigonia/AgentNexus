package com.zwbd.agentnexus.sdui.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Data;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.LocalDateTime;

@Data
@Entity
@Table(name = "sdui_device")
public class SduiDevice {

    @Column(name = "space_id", nullable = false)
    private String ownerSpaceId = "";

    @Id
    @Column(name = "device_id", length = 64)
    private String deviceId;

    @Column(nullable = false, length = 100)
    private String name = "unknown-device";

    @Column(nullable = false, length = 16)
    private String status = "ONLINE";

    private LocalDateTime lastSeenAt;

    @Column(length = 64)
    private String currentAppId;

    @Column(length = 64)
    private String currentPageId;

    @Column(nullable = false, length = 24)
    private String registrationStatus = "UNCLAIMED";

    @Column(length = 12)
    private String claimCode;

    private LocalDateTime claimCodeExpireAt;

    private LocalDateTime claimedAt;

    @CreationTimestamp
    private LocalDateTime createdAt;

    @UpdateTimestamp
    private LocalDateTime updatedAt;
}
