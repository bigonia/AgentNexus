package com.zwbd.agentnexus.sdui.dto;

import lombok.Builder;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.Set;

@Data
@Builder
public class SduiDeviceDetailResponse {
    private String deviceId;
    private String name;
    private String status;
    private String registrationStatus;
    private String board;
    private String screenShape;
    private int screenWidth;
    private int screenHeight;
    private String inputMode;
    private Set<String> availableCommands;
    private String capabilitiesSnapshot;
    private LocalDateTime lastSeenAt;
    private LocalDateTime claimedAt;
    private LocalDateTime createdAt;
}
