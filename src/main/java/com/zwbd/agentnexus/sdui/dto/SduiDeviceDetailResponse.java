package com.zwbd.agentnexus.sdui.dto;

import lombok.Builder;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.Map;

/**
 * 设备详情。
 *
 * <p>只承载设备台账、在线态与能力同步进度。命令/请求历史不在详情里重复暴露——那由调试域的
 * {@code /debug/{deviceId}/requests/history} 承担；完整能力 Schema 由能力域的 {@code /schema} 承担。</p>
 */
@Data
@Builder
public class SduiDeviceDetailResponse {

    private String deviceId;
    private String name;
    private String notes;
    private String status;
    private String registrationStatus;

    // ── 能力同步（v2）──
    private String board;
    private String protocolVersion;
    private String schemaVersion;
    private String capabilityHash;
    private String capabilitySyncState;
    private boolean businessAllowed;
    private Map<String, Object> capabilitySummary;
    private Map<String, Object> surface;

    // ── 连接 ──
    private LocalDateTime connectedAt;
    private String sessionId;
    private Integer connectionCount;
    private Long totalUptimeS;
    private LocalDateTime lastSeenAt;
    private LocalDateTime claimedAt;
    private LocalDateTime createdAt;

    // ── 最新遥测 ──
    private Map<String, Object> lastTelemetry;
}
