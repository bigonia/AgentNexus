package com.zwbd.agentnexus.sdui.service;

import com.zwbd.agentnexus.sdui.repo.SduiDeviceTelemetryRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.*;

/**
 * Provides time-series aggregation of device telemetry for trend charts.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TelemetryTrendService {

    private final SduiDeviceTelemetryRepository telemetryRepository;

    /** bucketSeconds → bucket label */
    private static final Map<Integer, String> BUCKET_LABELS = Map.of(
            60, "1m", 300, "5m", 600, "10m", 3600, "1h", 21600, "6h"
    );

    /**
     * Determine auto bucket size based on time range.
     */
    public static int autoBucketSecondsForRange(String range) {
        return switch (range) {
            case "1h" -> 60;     // 1 minute
            case "6h" -> 300;    // 5 minutes
            case "24h" -> 600;   // 10 minutes
            case "7d" -> 3600;   // 1 hour
            case "30d" -> 21600; // 6 hours
            default -> 600;
        };
    }

    /**
     * Parse range string to a start time.
     */
    public static LocalDateTime startForRange(String range) {
        return switch (range) {
            case "1h" -> LocalDateTime.now().minusHours(1);
            case "6h" -> LocalDateTime.now().minusHours(6);
            case "24h" -> LocalDateTime.now().minusHours(24);
            case "7d" -> LocalDateTime.now().minusDays(7);
            case "30d" -> LocalDateTime.now().minusDays(30);
            default -> LocalDateTime.now().minusHours(24);
        };
    }

    /**
     * Build trend data for a device, returning per-metric series.
     *
     * @param deviceId     device ID
     * @param range        "1h" / "6h" / "24h" / "7d" / "30d"
     * @param bucketSec    bucket size in seconds, or 0 for auto
     * @param metricsParam "all" / "memory" / "temperature" / "wifi" / "battery"
     * @return structured trend data
     */
    public Map<String, Object> buildTrends(String deviceId, String range, int bucketSec, String metricsParam) {
        if (bucketSec <= 0) {
            bucketSec = autoBucketSecondsForRange(range);
        }
        LocalDateTime start = startForRange(range);
        LocalDateTime end = LocalDateTime.now();

        List<Object[]> rows = telemetryRepository.aggregateByBucket(deviceId, start, end, bucketSec);

        boolean all = "all".equals(metricsParam);
        Map<String, Object> series = new LinkedHashMap<>();

        if (all || "memory".equals(metricsParam)) {
            series.put("memory", buildMemorySeries(rows));
        }
        if (all || "temperature".equals(metricsParam)) {
            series.put("temperature", buildTemperatureSeries(rows));
        }
        if (all || "wifi".equals(metricsParam)) {
            series.put("wifi", buildWifiSeries(rows));
        }
        if (all || "battery".equals(metricsParam)) {
            series.put("battery", buildBatterySeries(rows));
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("deviceId", deviceId);
        result.put("range", range);
        result.put("bucket", BUCKET_LABELS.getOrDefault(bucketSec, bucketSec + "s"));
        result.put("pointCount", rows.size());
        result.put("series", series);
        return result;
    }

    // ── Series builders ──
    // Row columns: bucket(0), cnt(1), avg_rssi(2), min_rssi(3), max_rssi(4),
    //   avg_temp(5), min_temp(6), max_temp(7), avg_heap_int(8), min_heap_int(9),
    //   max_heap_int(10), avg_heap_total(11), avg_heap_dma(12), avg_frag_int(13),
    //   min_frag_int(14), max_frag_int(15), avg_frag_dma(16), avg_bat(17),
    //   min_bat(18), max_bat(19), avg_bat_mv(20)

    private Map<String, Object> buildMemorySeries(List<Object[]> rows) {
        Map<String, Object> memory = new LinkedHashMap<>();
        memory.put("freeHeapTotal", toSeries(rows, 11, -1, -1));
        memory.put("freeHeapInternal", toSeries(rows, 8, 9, 10));
        memory.put("fragInternalPct", toSeries(rows, 13, 14, 15));
        memory.put("freeHeapDma", toSeries(rows, 12, -1, -1));
        memory.put("fragDmaPct", toSeries(rows, 16, -1, -1));
        return memory;
    }

    private Map<String, Object> buildTemperatureSeries(List<Object[]> rows) {
        Map<String, Object> temp = new LinkedHashMap<>();
        temp.put("celsius", toSeries(rows, 5, 6, 7));
        return temp;
    }

    private Map<String, Object> buildWifiSeries(List<Object[]> rows) {
        Map<String, Object> wifi = new LinkedHashMap<>();
        wifi.put("rssi", toSeries(rows, 2, 3, 4));
        return wifi;
    }

    private Map<String, Object> buildBatterySeries(List<Object[]> rows) {
        Map<String, Object> battery = new LinkedHashMap<>();
        battery.put("batteryPct", toSeries(rows, 17, 18, 19));
        battery.put("batteryMv", toSeries(rows, 20, -1, -1));
        return battery;
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> toSeries(List<Object[]> rows, int avgIdx, int minIdx, int maxIdx) {
        List<Map<String, Object>> points = new ArrayList<>();
        for (Object[] row : rows) {
            Map<String, Object> point = new LinkedHashMap<>();
            if (row[0] instanceof Timestamp ts) {
                point.put("ts", ts.toLocalDateTime().toString());
            }
            point.put("avg", round(row[avgIdx]));
            if (minIdx >= 0) point.put("min", round(row[minIdx]));
            if (maxIdx >= 0) point.put("max", round(row[maxIdx]));
            points.add(point);
        }
        return points;
    }

    private static Object round(Object val) {
        if (val == null) return null;
        if (val instanceof BigDecimal bd) return bd.setScale(1, RoundingMode.HALF_UP).doubleValue();
        if (val instanceof Double d) return Math.round(d * 10.0) / 10.0;
        return val;
    }
}
