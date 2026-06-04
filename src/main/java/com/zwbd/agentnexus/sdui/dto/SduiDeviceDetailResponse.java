package com.zwbd.agentnexus.sdui.dto;

import lombok.Builder;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
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
    private String sizeClass;
    private Set<String> availableCommands;
    private String capabilitiesSnapshot;
    private List<RecentCommand> recentCommands;

    // ── Health ──
    private Integer healthScore;
    private String healthLevel;
    private List<String> healthWarnings;

    // ── Latest telemetry summary ──
    private Map<String, Object> lastTelemetry;

    // ── Connection tracking ──
    private LocalDateTime connectedAt;
    private String sessionId;
    private Integer connectionCount;
    private Long totalUptimeS;

    private LocalDateTime lastSeenAt;
    private LocalDateTime claimedAt;
    private LocalDateTime createdAt;

    @Data
    @Builder
    public static class RecentCommand {
        private String cmdId;
        private String action;
        private String status;
        private String reason;
        private LocalDateTime createdAt;
    }
}
