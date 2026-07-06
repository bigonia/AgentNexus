package com.zwbd.agentnexus.sdui.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Data;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.TenantId;

import java.time.LocalDateTime;

@Data
@Entity
@Table(name = "sdui_device_telemetry")
public class SduiDeviceTelemetry {

    @TenantId
    @Column(name = "user_id", updatable = false)
    private String userId;

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 64)
    private String deviceId;

    // ── Network ──
    private Integer wifiRssi;
    private String ip;

    // ── Temperature ──
    private Double temperature;

    // ── Memory (internal SRAM) ──
    private Integer freeHeapInternal;
    private Integer largestHeapInternal;

    // ── Memory (DMA) ──
    private Integer freeHeapDma;
    private Integer largestHeapDma;

    // ── Memory (PSRAM) ──
    private Integer freeHeapPsram;
    private Integer largestHeapPsram;

    // ── Memory (aggregate) ──
    private Integer freeHeapTotal;

    // ── Fragmentation ──
    private Integer fragInternalPct;
    private Integer fragDmaPct;
    private Integer fragPsramPct;

    // ── Uptime ──
    private Integer uptimeS;

    // ── Power / Battery ──
    private Boolean powerSupported;
    private Integer batteryMv;
    private Integer batteryPct;
    private Boolean charging;
    private Boolean extPowerPresent;
    private Boolean extPowerCtrl;
    private Boolean extPowerOn;

    @CreationTimestamp
    private LocalDateTime createdAt;
}

