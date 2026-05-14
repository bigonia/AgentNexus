package com.zwbd.agentnexus.sdui.workflow;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.concurrent.*;

@Slf4j
@Component
@RequiredArgsConstructor
public class TriggerScheduler {

    private final ScheduledExecutorService executor = Executors.newScheduledThreadPool(4);
    private final Map<String, ScheduledFuture<?>> cronTasks = new ConcurrentHashMap<>();
    private final Map<String, List<String>> webhookRoutes = new ConcurrentHashMap<>();
    private final Map<String, List<String>> deviceEventRoutes = new ConcurrentHashMap<>();

    public void registerTriggers(String deviceId, WorkflowDefinition def, WorkflowInstance instance,
                                  ActionExecutor actionExecutor, Map<String, String> env) {
        if (def.triggers() == null) return;

        for (TriggerDef trigger : def.triggers()) {
            List<ActionDef> actions = def.actions() != null ? def.actions().get(trigger.id()) : null;
            if (actions == null || actions.isEmpty()) continue;

            String taskKey = deviceId + ":" + trigger.id();

            if (trigger instanceof TriggerDef.CronTrigger c) {
                long intervalSec = c.interval() != null ? c.interval() : 30;
                ScheduledFuture<?> future = executor.scheduleAtFixedRate(
                        () -> actionExecutor.execute(actions, instance, Map.of(), env),
                        intervalSec, intervalSec, TimeUnit.SECONDS);
                cronTasks.put(taskKey, future);
                log.info("Cron trigger registered: {} every {}s", taskKey, intervalSec);
            } else if (trigger instanceof TriggerDef.WebhookTrigger w) {
                webhookRoutes.computeIfAbsent(w.path(), k -> new ArrayList<>()).add(deviceId);
                log.info("Webhook trigger registered: {} path={}", taskKey, w.path());
            } else if (trigger instanceof TriggerDef.DeviceEventTrigger d) {
                deviceEventRoutes.computeIfAbsent(d.event(), k -> new ArrayList<>()).add(deviceId);
                log.info("Device event trigger registered: {} event={}", taskKey, d.event());
            } else if (trigger instanceof TriggerDef.ManualTrigger m) {
                log.info("Manual trigger registered: {}", taskKey);
            }
        }
    }

    public void unregisterAll(String deviceId) {
        cronTasks.entrySet().removeIf(e -> {
            if (e.getKey().startsWith(deviceId + ":")) {
                e.getValue().cancel(false);
                return true;
            }
            return false;
        });
        webhookRoutes.values().forEach(list -> list.remove(deviceId));
        webhookRoutes.entrySet().removeIf(e -> e.getValue().isEmpty());
        deviceEventRoutes.values().forEach(list -> list.remove(deviceId));
        deviceEventRoutes.entrySet().removeIf(e -> e.getValue().isEmpty());
        log.info("All triggers unregistered for device {}", deviceId);
    }

    public String findWebhookDevice(String path) {
        List<String> devices = webhookRoutes.get(path);
        return devices != null && !devices.isEmpty() ? devices.get(0) : null;
    }

    public List<String> findDeviceEventDevices(String eventType) {
        return deviceEventRoutes.getOrDefault(eventType, List.of());
    }
}
