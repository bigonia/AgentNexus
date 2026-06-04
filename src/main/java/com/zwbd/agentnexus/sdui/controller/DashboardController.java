package com.zwbd.agentnexus.sdui.controller;

import com.zwbd.agentnexus.common.web.ApiResponse;
import com.zwbd.agentnexus.sdui.service.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * Device dashboard API — real-time snapshots, telemetry trends,
 * command analytics, and connection history.
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/sdui/dashboard")
@RequiredArgsConstructor
public class DashboardController {

    private final DashboardService dashboardService;
    private final TelemetryTrendService trendService;
    private final CommandStatsService commandStatsService;
    private final ConnectionLogService connectionLogService;

    /**
     * Device real-time snapshot.
     * <p>
     * With {@code deviceId}: full snapshot for a single device (connection, health,
     * telemetry, capabilities, workflow, commands, registration).
     * <p>
     * Without {@code deviceId}: summary list of all devices in the current space
     * with filtering, sorting, and pagination.
     */
    @GetMapping("/snapshot")
    public ApiResponse<Map<String, Object>> snapshot(
            @RequestParam(required = false) String deviceId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String health,
            @RequestParam(required = false) String search,
            @RequestParam(defaultValue = "lastSeenAt") String sortBy,
            @RequestParam(defaultValue = "desc") String sortDir) {

        if (deviceId != null && !deviceId.isBlank()) {
            return ApiResponse.ok(dashboardService.buildDeviceSnapshot(deviceId));
        }
        return ApiResponse.ok(dashboardService.buildDeviceListSummary(
                page, size, status, health, search, sortBy, sortDir));
    }

    /**
     * Telemetry time-series aggregation for trend charts.
     *
     * @param deviceId target device (required)
     * @param range    time range: 1h, 6h, 24h, 7d, 30d (default 24h)
     * @param bucket   aggregation bucket: 1m, 5m, 10m, 1h, 6h, auto (default auto)
     * @param metrics  metric groups: all, memory, temperature, wifi, battery (default all)
     */
    @GetMapping("/trends")
    public ApiResponse<Map<String, Object>> trends(
            @RequestParam String deviceId,
            @RequestParam(defaultValue = "24h") String range,
            @RequestParam(defaultValue = "auto") String bucket,
            @RequestParam(defaultValue = "all") String metrics) {

        int bucketSec = parseBucketSeconds(bucket, range);
        return ApiResponse.ok(trendService.buildTrends(deviceId, range, bucketSec, metrics));
    }

    /**
     * Per-device command statistics.
     *
     * @param deviceId    target device (required)
     * @param recentLimit number of recent commands to return (default 10, max 50)
     */
    @GetMapping("/command-stats")
    public ApiResponse<Map<String, Object>> commandStats(
            @RequestParam String deviceId,
            @RequestParam(defaultValue = "10") int recentLimit) {

        int limit = Math.min(recentLimit, 50);
        return ApiResponse.ok(commandStatsService.buildStats(deviceId, limit));
    }

    /**
     * Device connection/disconnection history.
     */
    @GetMapping("/connection-log")
    public ApiResponse<Map<String, Object>> connectionLog(
            @RequestParam String deviceId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {

        return ApiResponse.ok(connectionLogService.buildConnectionLog(deviceId, page, size));
    }

    // ── helpers ──

    private static int parseBucketSeconds(String bucket, String range) {
        if (!"auto".equalsIgnoreCase(bucket)) {
            return switch (bucket) {
                case "1m" -> 60;
                case "5m" -> 300;
                case "10m" -> 600;
                case "1h" -> 3600;
                case "6h" -> 21600;
                default -> TelemetryTrendService.autoBucketSecondsForRange(range);
            };
        }
        return TelemetryTrendService.autoBucketSecondsForRange(range);
    }
}
