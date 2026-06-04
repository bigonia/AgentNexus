package com.zwbd.agentnexus.sdui.service;

import com.zwbd.agentnexus.sdui.DeviceSessionManager;
import com.zwbd.agentnexus.sdui.model.SduiDevice;
import com.zwbd.agentnexus.sdui.model.SduiDeviceTelemetry;
import com.zwbd.agentnexus.sdui.protocol.CapabilitySchema;
import com.zwbd.agentnexus.sdui.repo.SduiDeviceTelemetryRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.*;

/**
 * Aggregates all device information into a single dashboard snapshot.
 * Single-entry point for the frontend device dashboard page.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DashboardService {

    private final DeviceQueryService queryService;
    private final SduiCapabilityService capabilityService;
    private final DeviceSessionManager sessionManager;
    private final SduiDeviceTelemetryRepository telemetryRepository;
    private final HealthScoreEvaluator healthScoreEvaluator;
    private final CommandStatsService commandStatsService;

    /**
     * Build a full snapshot for a single device.
     */
    public Map<String, Object> buildDeviceSnapshot(String deviceId) {
        Optional<SduiDevice> deviceOpt = queryService.getDevice(deviceId, queryService.currentSpaceId());
        if (deviceOpt.isEmpty()) {
            return Map.of("error", "device not found");
        }
        SduiDevice device = deviceOpt.get();
        boolean online = sessionManager.isDeviceOnline(deviceId);
        Optional<CapabilitySchema.CapabilitySnapshot> capsOpt = capabilityService.getCapabilities(deviceId);
        SduiDeviceTelemetry latestTelemetry = telemetryRepository.findFirstByDeviceIdOrderByCreatedAtDesc(deviceId);

        Map<String, Object> snapshot = new LinkedHashMap<>();
        snapshot.put("deviceId", device.getDeviceId());
        snapshot.put("name", device.getName());
        snapshot.put("board", capsOpt.map(CapabilitySchema.CapabilitySnapshot::board).orElse(null));

        // ── Connection ──
        snapshot.put("connection", buildConnectionBlock(device, online));

        // ── Health ──
        snapshot.put("health", healthScoreEvaluator.evaluate(latestTelemetry, device.getConnectionCount(), online));

        // ── Telemetry ──
        snapshot.put("telemetry", buildTelemetryBlock(latestTelemetry, online));

        // ── Capabilities ──
        snapshot.put("capabilities", buildCapabilitiesBlock(capsOpt.orElse(null)));

        // ── Workflow ──
        Map<String, Object> workflow = new LinkedHashMap<>();
        workflow.put("currentAppId", device.getCurrentAppId());
        workflow.put("currentPageId", device.getCurrentPageId());
        snapshot.put("workflow", workflow);

        // ── Commands ──
        snapshot.put("commands", commandStatsService.buildStats(deviceId, 5));

        // ── Registration ──
        Map<String, Object> reg = new LinkedHashMap<>();
        reg.put("status", device.getRegistrationStatus());
        reg.put("claimedAt", device.getClaimedAt() != null ? device.getClaimedAt().toString() : null);
        reg.put("createdAt", device.getCreatedAt() != null ? device.getCreatedAt().toString() : null);
        snapshot.put("registration", reg);

        snapshot.put("lastSeenAt", device.getLastSeenAt() != null ? device.getLastSeenAt().toString() : null);
        return snapshot;
    }

    /**
     * Build summary list of all devices in the current space (for the dashboard overview page).
     */
    public Map<String, Object> buildDeviceListSummary(int page, int size,
                                                       String statusFilter, String healthFilter,
                                                       String search, String sortBy, String sortDir) {
        List<SduiDevice> allDevices = queryService.listDevices(queryService.currentSpaceId());
        if (search != null && !search.isBlank()) {
            String q = search.toLowerCase();
            allDevices = allDevices.stream()
                    .filter(d -> (d.getName() != null && d.getName().toLowerCase().contains(q))
                            || d.getDeviceId().toLowerCase().contains(q))
                    .toList();
        }
        if (statusFilter != null && !statusFilter.isBlank()) {
            allDevices = allDevices.stream()
                    .filter(d -> statusFilter.equalsIgnoreCase(d.getStatus()))
                    .toList();
        }

        // Build enriched device items
        List<Map<String, Object>> items = new ArrayList<>();
        for (SduiDevice d : allDevices) {
            boolean online = sessionManager.isDeviceOnline(d.getDeviceId());
            SduiDeviceTelemetry latest = telemetryRepository.findFirstByDeviceIdOrderByCreatedAtDesc(d.getDeviceId());
            Optional<CapabilitySchema.CapabilitySnapshot> capsOpt = capabilityService.getCapabilities(d.getDeviceId());
            Map<String, Object> health = healthScoreEvaluator.evaluate(latest, d.getConnectionCount(), online);

            Map<String, Object> item = new LinkedHashMap<>();
            item.put("deviceId", d.getDeviceId());
            item.put("name", d.getName());
            item.put("status", online ? "ONLINE" : "OFFLINE");
            item.put("registrationStatus", d.getRegistrationStatus());
            item.put("board", capsOpt.map(CapabilitySchema.CapabilitySnapshot::board).orElse(null));
            item.put("screenShape", capsOpt.map(c -> c.screen().shape()).orElse(null));
            item.put("inputMode", capsOpt.map(CapabilitySchema.CapabilitySnapshot::inputMode).orElse(null));
            item.put("sizeClass", capsOpt.map(c -> c.display().effectiveSizeClass()).orElse(null));
            item.put("healthScore", health.get("score"));
            item.put("healthLevel", health.get("level"));

            if (latest != null) {
                Map<String, Object> lt = new LinkedHashMap<>();
                lt.put("wifiRssi", latest.getWifiRssi());
                lt.put("batteryPct", latest.getBatteryPct());
                lt.put("temperature", latest.getTemperature());
                lt.put("freeHeapTotal", latest.getFreeHeapTotal());
                lt.put("freeHeapInternal", latest.getFreeHeapInternal());
                lt.put("fragInternalPct", latest.getFragInternalPct());
                lt.put("uptimeS", latest.getUptimeS());
                item.put("lastTelemetry", lt);
            }
            item.put("currentAppId", d.getCurrentAppId());
            item.put("lastSeenAt", d.getLastSeenAt() != null ? d.getLastSeenAt().toString() : null);
            item.put("connectedAt", d.getConnectedAt() != null ? d.getConnectedAt().toString() : null);
            item.put("connectionCount", d.getConnectionCount());
            item.put("claimedAt", d.getClaimedAt() != null ? d.getClaimedAt().toString() : null);
            item.put("createdAt", d.getCreatedAt() != null ? d.getCreatedAt().toString() : null);

            items.add(item);
        }

        // Filter by health level
        if (healthFilter != null && !healthFilter.isBlank()) {
            items = items.stream()
                    .filter(i -> healthFilter.equalsIgnoreCase((String) i.get("healthLevel")))
                    .toList();
        }

        // Sort
        Comparator<Map<String, Object>> comparator = switch (sortBy != null ? sortBy : "lastSeenAt") {
            case "name" -> Comparator.comparing(m -> (String) m.get("name"), Comparator.nullsLast(String.CASE_INSENSITIVE_ORDER));
            case "status" -> Comparator.comparing(m -> (String) m.get("status"));
            case "healthScore" -> Comparator.comparing(m -> (Integer) m.getOrDefault("healthScore", -1), Comparator.nullsLast(Comparator.reverseOrder()));
            default -> Comparator.comparing(m -> (String) m.get("lastSeenAt"), Comparator.nullsLast(Comparator.reverseOrder()));
        };
        if ("asc".equalsIgnoreCase(sortDir)) {
            comparator = comparator.reversed();
        }
        items.sort(comparator);

        // Pagination
        int total = items.size();
        int start = page * size;
        int end = Math.min(start + size, total);
        List<Map<String, Object>> pagedItems = (start < total) ? items.subList(start, end) : List.of();

        // Health summary
        long healthy = items.stream().filter(i -> "HEALTHY".equals(i.get("healthLevel"))).count();
        long warning = items.stream().filter(i -> "WARNING".equals(i.get("healthLevel"))).count();
        long critical = items.stream().filter(i -> "CRITICAL".equals(i.get("healthLevel"))).count();
        long unknown = items.stream().filter(i -> "UNKNOWN".equals(i.get("healthLevel"))).count();

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("totalDevices", total);
        result.put("onlineCount", allDevices.stream().filter(d -> sessionManager.isDeviceOnline(d.getDeviceId())).count());
        result.put("offlineCount", allDevices.stream().filter(d -> !sessionManager.isDeviceOnline(d.getDeviceId())).count());
        result.put("claimedCount", allDevices.stream().filter(d -> "CLAIMED".equalsIgnoreCase(d.getRegistrationStatus())).count());
        result.put("unclaimedCount", allDevices.stream().filter(d -> "UNCLAIMED".equalsIgnoreCase(d.getRegistrationStatus())).count());
        result.put("healthSummary", Map.of("healthy", healthy, "warning", warning, "critical", critical, "unknown", unknown));
        result.put("page", page);
        result.put("size", size);
        result.put("items", pagedItems);
        return result;
    }

    // ── Private block builders ──

    private Map<String, Object> buildConnectionBlock(SduiDevice device, boolean online) {
        Map<String, Object> conn = new LinkedHashMap<>();
        conn.put("status", online ? "ONLINE" : "OFFLINE");
        conn.put("connectedAt", device.getConnectedAt() != null ? device.getConnectedAt().toString() : null);
        if (device.getConnectedAt() != null && online) {
            conn.put("sessionDurationS", Duration.between(device.getConnectedAt(), LocalDateTime.now()).getSeconds());
        } else {
            conn.put("sessionDurationS", null);
        }
        conn.put("sessionId", device.getSessionId());
        conn.put("connectionCount", device.getConnectionCount());
        conn.put("totalUptimeS", device.getTotalUptimeS());
        return conn;
    }

    private Map<String, Object> buildTelemetryBlock(SduiDeviceTelemetry t, boolean online) {
        Map<String, Object> telem = new LinkedHashMap<>();
        if (t == null) {
            telem.put("lastHeartbeatAt", null);
            return telem;
        }

        Map<String, Object> wifi = new LinkedHashMap<>();
        wifi.put("rssi", t.getWifiRssi());
        wifi.put("ip", t.getIp());
        telem.put("wifi", wifi);

        Map<String, Object> memory = new LinkedHashMap<>();
        memory.put("freeHeapInternal", t.getFreeHeapInternal());
        memory.put("largestHeapInternal", t.getLargestHeapInternal());
        memory.put("freeHeapDma", t.getFreeHeapDma());
        memory.put("largestHeapDma", t.getLargestHeapDma());
        memory.put("freeHeapPsram", t.getFreeHeapPsram());
        memory.put("largestHeapPsram", t.getLargestHeapPsram());
        memory.put("freeHeapTotal", t.getFreeHeapTotal());
        memory.put("fragInternalPct", t.getFragInternalPct());
        memory.put("fragDmaPct", t.getFragDmaPct());
        memory.put("fragPsramPct", t.getFragPsramPct());
        memory.put("fragLevel", fragLevel(t.getFragInternalPct()));
        telem.put("memory", memory);

        Map<String, Object> temp = new LinkedHashMap<>();
        temp.put("celsius", t.getTemperature());
        temp.put("level", tempLevel(t.getTemperature()));
        telem.put("temperature", temp);

        Map<String, Object> power = new LinkedHashMap<>();
        power.put("supported", t.getPowerSupported());
        power.put("batteryMv", t.getBatteryMv());
        power.put("batteryPct", t.getBatteryPct());
        power.put("charging", t.getCharging());
        power.put("extPowerPresent", t.getExtPowerPresent());
        power.put("extPowerCtrl", t.getExtPowerCtrl());
        power.put("extPowerOn", t.getExtPowerOn());
        telem.put("power", power);
        telem.put("uptimeS", t.getUptimeS());
        telem.put("lastHeartbeatAt", t.getCreatedAt() != null ? t.getCreatedAt().toString() : null);
        return telem;
    }

    private Map<String, Object> buildCapabilitiesBlock(CapabilitySchema.CapabilitySnapshot caps) {
        Map<String, Object> cap = new LinkedHashMap<>();
        if (caps == null) return cap;
        cap.put("screenW", caps.screen() != null ? caps.screen().w() : 0);
        cap.put("screenH", caps.screen() != null ? caps.screen().h() : 0);
        cap.put("screenShape", caps.screen() != null ? caps.screen().shape() : null);
        cap.put("inputMode", caps.inputMode());
        cap.put("sizeClass", caps.display() != null ? caps.display().effectiveSizeClass() : null);
        cap.put("inputs", caps.inputs());
        cap.put("outputs", caps.outputs());
        cap.put("sectionTypes", caps.display() != null ? caps.display().sectionTypes() : List.of());
        return cap;
    }

    private static String fragLevel(Integer fragPct) {
        if (fragPct == null) return "UNKNOWN";
        if (fragPct <= 30) return "LOW";
        if (fragPct <= 60) return "MODERATE";
        return "HIGH";
    }

    private static String tempLevel(Double celsius) {
        if (celsius == null || celsius < 0) return "UNKNOWN";
        if (celsius >= 30 && celsius <= 50) return "NORMAL";
        if (celsius > 50 && celsius <= 65) return "WARM";
        if (celsius > 65) return "HOT";
        return "COLD";
    }
}
