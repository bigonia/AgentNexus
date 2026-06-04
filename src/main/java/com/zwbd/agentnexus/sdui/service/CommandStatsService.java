package com.zwbd.agentnexus.sdui.service;

import com.zwbd.agentnexus.sdui.model.SduiDeviceCommand;
import com.zwbd.agentnexus.sdui.repo.SduiDeviceCommandRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;

import java.util.*;

/**
 * Per-device command analytics: success rate, breakdown by action, by hour.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CommandStatsService {

    private final SduiDeviceCommandRepository commandRepository;

    /**
     * Build command statistics for a device.
     *
     * @param deviceId target device
     * @param recentLimit how many recent commands to include in the response
     * @return stats map
     */
    public Map<String, Object> buildStats(String deviceId, int recentLimit) {
        List<SduiDeviceCommand> recent = commandRepository.findTopNByDeviceIdOrderByCreatedAtDesc(
                deviceId, PageRequest.of(0, Math.max(recentLimit, 200)));

        // Summary
        long total = recent.size();
        long acked = recent.stream().filter(c -> "ACKED".equalsIgnoreCase(c.getStatus())).count();
        long failed = recent.stream().filter(c -> "FAILED".equalsIgnoreCase(c.getStatus())).count();
        long rejected = recent.stream().filter(c -> "REJECTED".equalsIgnoreCase(c.getStatus())).count();
        long timeout = recent.stream().filter(c -> "TIMEOUT".equalsIgnoreCase(c.getStatus())).count();
        long error = recent.stream().filter(c -> "ERROR".equalsIgnoreCase(c.getStatus())).count();
        double successRate = total > 0 ? Math.round(acked * 1000.0 / total) / 10.0 : 0.0;

        // By action
        Map<String, long[]> byAction = new LinkedHashMap<>();
        for (SduiDeviceCommand c : recent) {
            String action = c.getAction() != null ? c.getAction() : "unknown";
            long[] counts = byAction.computeIfAbsent(action, k -> new long[3]); // [total, acked, failed]
            counts[0]++;
            if ("ACKED".equalsIgnoreCase(c.getStatus())) {
                counts[1]++;
            } else if ("FAILED".equalsIgnoreCase(c.getStatus()) || "TIMEOUT".equalsIgnoreCase(c.getStatus())
                    || "ERROR".equalsIgnoreCase(c.getStatus())) {
                counts[2]++;
            }
        }

        List<Map<String, Object>> actionBreakdown = new ArrayList<>();
        for (var entry : byAction.entrySet()) {
            long[] counts = entry.getValue();
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("action", entry.getKey());
            item.put("total", (int) counts[0]);
            item.put("acked", (int) counts[1]);
            item.put("failed", (int) counts[2]);
            item.put("successRate", counts[0] > 0 ? Math.round(counts[1] * 1000.0 / counts[0]) / 10.0 : 0.0);
            actionBreakdown.add(item);
        }

        // Recent commands (capped)
        List<Map<String, Object>> recentList = new ArrayList<>();
        for (SduiDeviceCommand c : recent.stream().limit(recentLimit).toList()) {
            Map<String, Object> cmd = new LinkedHashMap<>();
            cmd.put("cmdId", c.getCmdId());
            cmd.put("action", c.getAction());
            cmd.put("status", c.getStatus());
            cmd.put("reason", c.getReason());
            cmd.put("createdAt", c.getCreatedAt() != null ? c.getCreatedAt().toString() : null);
            recentList.add(cmd);
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("deviceId", deviceId);
        result.put("summary", Map.of(
                "total", (int) total,
                "acked", (int) acked,
                "failed", (int) failed,
                "rejected", (int) rejected,
                "timeout", (int) timeout,
                "error", (int) error,
                "successRate", successRate
        ));
        result.put("byAction", actionBreakdown);
        result.put("recentCommands", recentList);
        return result;
    }
}
