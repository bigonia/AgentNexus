package com.zwbd.agentnexus.sdui.service;

import com.zwbd.agentnexus.sdui.model.SduiDeviceTelemetry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Evaluates device health based on latest telemetry snapshot.
 * Five dimensions: memory (30%), battery (25%), signal (20%), temperature (15%), connection stability (10%).
 */
@Slf4j
@Component
public class HealthScoreEvaluator {

    public enum HealthLevel { HEALTHY, WARNING, CRITICAL, UNKNOWN }

    public static final int SCORE_HEALTHY_MIN = 80;
    public static final int SCORE_WARNING_MIN = 50;

    // ── Threshold constants ──
    private static final int WIFI_EXCELLENT = -50;
    private static final int WIFI_GOOD = -60;
    private static final int WIFI_FAIR = -70;
    private static final int WIFI_POOR = -80;

    private static final double TEMP_IDEAL_MAX = 50.0;
    private static final double TEMP_WARM_MAX = 65.0;
    private static final double TEMP_HOT_MAX = 75.0;

    private static final int HEAP_SAFE_MIN = 20_000;
    private static final int FRAG_SAFE_MAX = 30;
    private static final int FRAG_WARNING = 60;

    private static final int BATTERY_LOW = 15;
    private static final int BATTERY_CRITICAL = 5;

    /**
     * Evaluate health score for a device based on its latest telemetry.
     *
     * @param telemetry        latest telemetry snapshot (may be null for offline devices)
     * @param connectionCount  total connection count for stability scoring
     * @param isOnline         whether the device is currently online
     * @return health map with score, level, factor breakdown, and warnings
     */
    public Map<String, Object> evaluate(SduiDeviceTelemetry telemetry, Integer connectionCount, boolean isOnline) {
        if (!isOnline || telemetry == null) {
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("score", null);
            result.put("level", HealthLevel.UNKNOWN.name());
            result.put("factors", Map.of());
            result.put("warnings", List.of("device_offline"));
            return result;
        }

        int memoryScore = scoreMemory(telemetry);
        int batteryScore = scoreBattery(telemetry);
        int signalScore = scoreSignal(telemetry);
        int tempScore = scoreTemperature(telemetry);
        int stabilityScore = scoreStability(connectionCount);

        int overall = (int) Math.round(
                memoryScore * 0.30 +
                batteryScore * 0.25 +
                signalScore * 0.20 +
                tempScore * 0.15 +
                stabilityScore * 0.10
        );

        HealthLevel level;
        if (overall >= SCORE_HEALTHY_MIN) {
            level = HealthLevel.HEALTHY;
        } else if (overall >= SCORE_WARNING_MIN) {
            level = HealthLevel.WARNING;
        } else {
            level = HealthLevel.CRITICAL;
        }

        List<String> warnings = buildWarnings(telemetry, connectionCount);

        Map<String, Object> factors = new LinkedHashMap<>();
        factors.put("memory", memoryScore);
        factors.put("battery", batteryScore);
        factors.put("signal", signalScore);
        factors.put("temperature", tempScore);
        factors.put("stability", stabilityScore);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("score", overall);
        result.put("level", level.name());
        result.put("factors", factors);
        result.put("warnings", warnings);
        return result;
    }

    // ── Dimension scorers ──

    private int scoreMemory(SduiDeviceTelemetry t) {
        Integer frag = t.getFragInternalPct();
        Integer free = t.getFreeHeapInternal();

        if (frag == null && free == null) return 80; // default when no data

        if (frag != null && frag > 70) return 20;
        if (frag != null && frag > FRAG_WARNING) return 50;

        if (free != null && free < 10_000) return 30;
        if (free != null && free < HEAP_SAFE_MIN) return 60;

        if (frag != null && frag <= FRAG_SAFE_MAX && free != null && free >= HEAP_SAFE_MIN) return 100;
        return 80;
    }

    private int scoreBattery(SduiDeviceTelemetry t) {
        Integer pct = t.getBatteryPct();
        Boolean charging = t.getCharging();
        Boolean supported = t.getPowerSupported();

        if (supported == null || !supported || pct == null) return 100; // no battery = assume wall power
        if (Boolean.TRUE.equals(charging)) return 100;

        if (pct <= BATTERY_CRITICAL) return 10;
        if (pct <= BATTERY_LOW) return 25;
        if (pct <= 30) return 50;
        if (pct <= 50) return 75;
        return 100;
    }

    private int scoreSignal(SduiDeviceTelemetry t) {
        Integer rssi = t.getWifiRssi();
        if (rssi == null) return 80;
        if (rssi > WIFI_EXCELLENT) return 100;
        if (rssi > WIFI_GOOD) return 90;
        if (rssi > WIFI_FAIR) return 70;
        if (rssi > WIFI_POOR) return 40;
        return 10;
    }

    private int scoreTemperature(SduiDeviceTelemetry t) {
        Double temp = t.getTemperature();
        if (temp == null || temp < 0) return 80;
        if (temp >= 30 && temp <= TEMP_IDEAL_MAX) return 100;
        if (temp > TEMP_IDEAL_MAX && temp <= TEMP_WARM_MAX) return 70;
        if (temp > TEMP_WARM_MAX && temp <= TEMP_HOT_MAX) return 40;
        return 10; // > 75°C or < 30°C
    }

    private int scoreStability(Integer connectionCount) {
        if (connectionCount == null) return 80;
        if (connectionCount <= 2) return 100;
        if (connectionCount <= 5) return 80;
        if (connectionCount <= 10) return 60;
        return 40;
    }

    private List<String> buildWarnings(SduiDeviceTelemetry t, Integer connectionCount) {
        List<String> warnings = new ArrayList<>();

        Integer pct = t.getBatteryPct();
        Boolean charging = t.getCharging();
        Boolean supported = t.getPowerSupported();
        if (supported != null && supported && pct != null && pct <= BATTERY_LOW && !Boolean.TRUE.equals(charging)) {
            warnings.add("battery_low");
        }

        Integer frag = t.getFragInternalPct();
        if (frag != null && frag > FRAG_WARNING) {
            warnings.add("memory_fragmented");
        }

        Double temp = t.getTemperature();
        if (temp != null && temp > TEMP_HOT_MAX) {
            warnings.add("overheating");
        }

        Integer rssi = t.getWifiRssi();
        if (rssi != null && rssi <= WIFI_POOR) {
            warnings.add("weak_signal");
        }

        Integer heap = t.getFreeHeapInternal();
        if (heap != null && heap < 10_000) {
            warnings.add("memory_low");
        }

        if (connectionCount != null && connectionCount >= 10) {
            warnings.add("unstable_connection");
        }

        return warnings;
    }
}
