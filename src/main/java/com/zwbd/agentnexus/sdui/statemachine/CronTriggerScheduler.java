package com.zwbd.agentnexus.sdui.statemachine;

import com.zwbd.agentnexus.sdui.statemachine.model.StateMachine;
import com.zwbd.agentnexus.sdui.statemachine.model.StateMachineDeployment;
import com.zwbd.agentnexus.sdui.statemachine.repo.StateMachineDeploymentRepository;
import com.zwbd.agentnexus.sdui.statemachine.repo.StateMachineRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.*;

/**
 * Scans deployed state machines for transitions with cron-type events
 * and fires them when the cron expression matches the current time.
 *
 * Deployment-based: only state machines with active deployments are scanned.
 * Each deployment independently tracks its own current state.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CronTriggerScheduler {

    private static final String CRON_EVENT_ID = "system:cron";

    private final StateMachineRepository stateMachineRepository;
    private final StateMachineDeploymentRepository deploymentRepository;
    private final StateMachineService stateMachineService;

    @Scheduled(fixedDelay = 60_000, initialDelay = 10_000)
    public void scanAndFireCronTriggers() {
        List<StateMachineDeployment> deployments = deploymentRepository.findAll();
        if (deployments.isEmpty()) return;

        long started = System.currentTimeMillis();
        int fired = 0;

        // Deduplicate: multiple deployments of the same SM only need one scan
        Set<String> scannedSmIds = new HashSet<>();
        for (StateMachineDeployment deployment : deployments) {
            String smId = deployment.getStateMachineId();
            if (!scannedSmIds.add(smId)) continue;

            try {
                StateMachine sm = stateMachineRepository.findById(smId).orElse(null);
                if (sm == null) continue;
                if (fireCronTriggers(sm, deployment)) fired++;
            } catch (Exception e) {
                log.error("Cron trigger error for state machine {}: {}", smId, e.getMessage());
            }
        }

        long elapsed = System.currentTimeMillis() - started;
        if (fired > 0) {
            log.info("Cron scan complete: {} deployed SMs scanned, {} fired in {}ms",
                    scannedSmIds.size(), fired, elapsed);
        } else {
            log.debug("Cron scan complete: {} deployed SMs scanned, 0 fired in {}ms",
                    scannedSmIds.size(), elapsed);
        }
    }

    private boolean fireCronTriggers(StateMachine sm, StateMachineDeployment deployment) {
        Map<String, Object> def = sm.getDefinition();
        Object rawTransitions = def.get("transitions");
        if (!(rawTransitions instanceof List<?> transitions)) return false;

        ZonedDateTime now = ZonedDateTime.now(ZoneId.systemDefault());
        boolean anyFired = false;

        for (int i = 0; i < transitions.size(); i++) {
            Object raw = transitions.get(i);
            if (!(raw instanceof Map<?, ?> map)) continue;
            Map<String, Object> transition = normalize(map);

            // Only check transitions that match the deployment's current state
            String fromStateId = string(transition.get("fromStateId"));
            if (!fromStateId.isBlank() && !fromStateId.equals(deployment.getCurrentStateId())) continue;

            Map<String, Object> event = transition.get("event") instanceof Map<?, ?> em
                    ? normalize(em) : Map.of();
            if (!CRON_EVENT_ID.equals(string(event.get("eventId")))) continue;

            String cronExpr = string(event.get("cron"));
            if (cronExpr.isBlank()) continue;
            if (!cronMatches(cronExpr, now)) continue;

            Map<String, Object> triggerEvent = new LinkedHashMap<>();
            triggerEvent.put("eventId", CRON_EVENT_ID);
            triggerEvent.put("cron", cronExpr);
            triggerEvent.put("triggeredAt", Instant.now().toString());
            triggerEvent.put("transitionIndex", i);

            // Use first device from deployment as trigger device
            if (!deployment.getDevices().isEmpty()) {
                triggerEvent.put("deviceId", deployment.getDevices().get(0));
            }

            try {
                stateMachineService.trigger(sm.getId(), Map.of("event", triggerEvent));
                anyFired = true;
                log.info("Cron fired: sm={} deployment={} state={} expr={}",
                        sm.getId(), deployment.getId(), deployment.getCurrentStateId(), cronExpr);
            } catch (Exception e) {
                log.error("Cron trigger failed for sm {} deployment {} transition {}: {}",
                        sm.getId(), deployment.getId(), i, e.getMessage());
            }
        }
        return anyFired;
    }

    private boolean cronMatches(String expr, ZonedDateTime now) {
        String[] fields = expr.trim().split("\\s+");
        if (fields.length != 5) {
            log.warn("Invalid cron expression (expected 5 fields): {}", expr);
            return false;
        }
        int minute = now.getMinute();
        int hour = now.getHour();
        int dayOfMonth = now.getDayOfMonth();
        int month = now.getMonthValue();
        int dayOfWeek = now.getDayOfWeek().getValue() % 7;

        return fieldMatches(fields[0], minute, 0, 59)
                && fieldMatches(fields[1], hour, 0, 23)
                && fieldMatches(fields[2], dayOfMonth, 1, 31)
                && fieldMatches(fields[3], month, 1, 12)
                && fieldMatches(fields[4], dayOfWeek, 0, 6);
    }

    private boolean fieldMatches(String field, int value, int min, int max) {
        for (String part : field.split(",")) {
            if (singleFieldMatches(part.trim(), value, min, max)) return true;
        }
        return false;
    }

    private boolean singleFieldMatches(String field, int value, int min, int max) {
        if ("*".equals(field)) return true;
        if (field.startsWith("*/")) {
            int step = Integer.parseInt(field.substring(2));
            return (value - min) % step == 0;
        }
        try {
            return Integer.parseInt(field) == value;
        } catch (NumberFormatException e) {
            return false;
        }
    }

    private Map<String, Object> normalize(Map<?, ?> raw) {
        Map<String, Object> result = new LinkedHashMap<>();
        raw.forEach((key, val) -> result.put(String.valueOf(key), val));
        return result;
    }

    private String string(Object value) {
        return value == null ? "" : String.valueOf(value);
    }
}
