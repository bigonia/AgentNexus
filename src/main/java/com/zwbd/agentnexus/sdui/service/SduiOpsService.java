package com.zwbd.agentnexus.sdui.service;

import com.zwbd.agentnexus.sdui.DeviceSessionManager;
import com.zwbd.agentnexus.sdui.model.SduiDevice;
import com.zwbd.agentnexus.sdui.model.SduiDeviceTelemetry;
import com.zwbd.agentnexus.sdui.protocol.CapabilitySchema;
import com.zwbd.agentnexus.sdui.repo.SduiDeviceCommandRepository;
import com.zwbd.agentnexus.sdui.repo.SduiDeviceTelemetryRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.*;

@Service
@RequiredArgsConstructor
public class SduiOpsService {

    private final SduiDeviceService deviceService;
    private final SduiCapabilityService capabilityService;
    private final SduiDeviceCommandRepository commandRepository;
    private final SduiDeviceTelemetryRepository telemetryRepository;
    private final DeviceSessionManager sessionManager;
    private final HealthScoreEvaluator healthScoreEvaluator;

    public Map<String, Object> overview() {
        deviceService.markTimedOutCommands();

        List<SduiDevice> allDevices = deviceService.listDevices();
        long totalDevices = allDevices.size();
        long onlineDevices = allDevices.stream().filter(d -> sessionManager.isDeviceOnline(d.getDeviceId())).count();
        long offlineDevices = totalDevices - onlineDevices;
        long claimedDevices = allDevices.stream().filter(d -> "CLAIMED".equalsIgnoreCase(d.getRegistrationStatus())).count();
        long unclaimedDevices = totalDevices - claimedDevices;

        // ── Health summary ──
        int healthy = 0, warning = 0, critical = 0, unknown = 0;
        List<Map<String, Object>> devicesNeedingAttention = new ArrayList<>();
        Map<String, Integer> boardDistribution = new LinkedHashMap<>();

        for (SduiDevice d : allDevices) {
            boolean online = sessionManager.isDeviceOnline(d.getDeviceId());
            SduiDeviceTelemetry latest = telemetryRepository.findFirstByDeviceIdOrderByCreatedAtDesc(d.getDeviceId());
            Map<String, Object> health = healthScoreEvaluator.evaluate(latest, d.getConnectionCount(), online);

            String level = (String) health.get("level");
            switch (level != null ? level : "UNKNOWN") {
                case "HEALTHY" -> healthy++;
                case "WARNING" -> warning++;
                case "CRITICAL" -> critical++;
                default -> unknown++;
            }

            @SuppressWarnings("unchecked")
            List<String> warnings = (List<String>) health.get("warnings");
            if (warnings != null && !warnings.isEmpty() && !warnings.contains("device_offline")) {
                Map<String, Object> alert = new LinkedHashMap<>();
                alert.put("deviceId", d.getDeviceId());
                alert.put("name", d.getName());
                alert.put("status", online ? "ONLINE" : "OFFLINE");
                alert.put("healthLevel", level);
                alert.put("healthScore", health.get("score"));
                alert.put("warnings", warnings);
                devicesNeedingAttention.add(alert);
            }

            // Board distribution
            Optional<CapabilitySchema.CapabilitySnapshot> capsOpt = capabilityService.getCapabilities(d.getDeviceId());
            String board = capsOpt.map(CapabilitySchema.CapabilitySnapshot::board).orElse("unknown");
            boardDistribution.merge(board, 1, Integer::sum);
        }

        // ── Command summary ──
        long sentCommands = commandRepository.countByStatus("SENT");
        long failedCommands = commandRepository.countByStatus("FAILED");
        long ackedCommands = commandRepository.countByStatus("ACKED");
        long rejectedCommands = commandRepository.countByStatus("REJECTED");
        long errorCommands = commandRepository.countByStatus("ERROR");
        long timeoutCommands = commandRepository.countByStatus("TIMEOUT");
        long totalCommands = sentCommands + failedCommands + ackedCommands + rejectedCommands + errorCommands + timeoutCommands;
        double overallSuccessRate = totalCommands > 0
                ? Math.round(ackedCommands * 1000.0 / totalCommands) / 10.0 : 0.0;

        // ── Average health score ──
        double avgHealthScore = allDevices.stream()
                .mapToInt(d -> {
                    boolean online = sessionManager.isDeviceOnline(d.getDeviceId());
                    SduiDeviceTelemetry latest = telemetryRepository.findFirstByDeviceIdOrderByCreatedAtDesc(d.getDeviceId());
                    Map<String, Object> h = healthScoreEvaluator.evaluate(latest, d.getConnectionCount(), online);
                    Integer score = (Integer) h.get("score");
                    return score != null ? score : 0;
                })
                .filter(s -> s > 0)
                .average()
                .orElse(0.0);

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("totalDevices", (int) totalDevices);
        data.put("onlineDevices", (int) onlineDevices);
        data.put("offlineDevices", (int) offlineDevices);
        data.put("claimedDevices", (int) claimedDevices);
        data.put("unclaimedDevices", (int) unclaimedDevices);
        data.put("healthSummary", Map.of(
                "healthy", healthy,
                "warning", warning,
                "critical", critical,
                "unknown", unknown
        ));
        data.put("commandSummary", Map.of(
                "sentCommands", (int) sentCommands,
                "failedCommands", (int) failedCommands,
                "ackedCommands", (int) ackedCommands,
                "rejectedCommands", (int) rejectedCommands,
                "errorCommands", (int) errorCommands,
                "timeoutCommands", (int) timeoutCommands,
                "overallSuccessRate", overallSuccessRate
        ));
        data.put("boardDistribution", boardDistribution);
        data.put("avgHealthScore", Math.round(avgHealthScore * 10.0) / 10.0);
        data.put("devicesNeedingAttention", devicesNeedingAttention);
        return data;
    }
}
