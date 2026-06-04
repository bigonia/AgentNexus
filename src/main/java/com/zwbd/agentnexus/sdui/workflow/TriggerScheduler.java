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
    private final Map<String, Boolean> cronRunning = new ConcurrentHashMap<>();
    private final Map<String, List<DeviceEventRoute>> deviceEventRoutes = new ConcurrentHashMap<>();

    private record DeviceEventRoute(String deviceId, String definitionId, TriggerDef.DeviceEventTrigger trigger) {}

    public void registerTriggers(String deviceId, WorkflowDefinition def, WorkflowInstance instance,
                                  ActionExecutor actionExecutor, Map<String, String> env) {
        if (def.triggers() == null) return;

        for (TriggerDef trigger : def.triggers()) {
            List<ActionDef> actions = def.actions() != null ? def.actions().get(trigger.id()) : null;
            if (actions == null || actions.isEmpty()) continue;

            String taskKey = deviceId + ":" + def.id() + ":" + trigger.id();

            if (trigger instanceof TriggerDef.CronTrigger c) {
                long intervalSec = c.interval() != null ? c.interval() : 30;
                ScheduledFuture<?> future = executor.scheduleWithFixedDelay(
                        () -> {
                            if (Boolean.TRUE.equals(cronRunning.putIfAbsent(taskKey, true))) {
                                log.debug("Cron task {} skipped — previous execution still running", taskKey);
                                return;
                            }
                            try {
                                actionExecutor.execute(actions, instance, Map.of(), env);
                            } finally {
                                cronRunning.remove(taskKey);
                            }
                        },
                        intervalSec, intervalSec, TimeUnit.SECONDS);
                cronTasks.put(taskKey, future);
                log.info("Cron trigger registered: {} every {}s", taskKey, intervalSec);
            } else if (trigger instanceof TriggerDef.WebhookTrigger w) {
                webhookRoutes.computeIfAbsent(w.path(), k -> new ArrayList<>())
                        .add(deviceId + ":" + def.id());
                log.info("Webhook trigger registered: {} path={}", taskKey, w.path());
            } else if (trigger instanceof TriggerDef.DeviceEventTrigger d) {
                deviceEventRoutes.computeIfAbsent(d.event(), k -> new ArrayList<>())
                        .add(new DeviceEventRoute(deviceId, def.id(), d));
                log.info("Device event trigger registered: {} event={} sectionId={} nodeId={}",
                        taskKey, d.event(), d.sectionId(), d.nodeId());
            } else if (trigger instanceof TriggerDef.ManualTrigger m) {
                log.info("Manual trigger registered: {}", taskKey);
            }
        }
    }

    public void unregisterAll(String deviceId) {
        String prefix = deviceId + ":";
        cronTasks.entrySet().removeIf(e -> {
            if (e.getKey().startsWith(prefix)) {
                e.getValue().cancel(false);
                cronRunning.remove(e.getKey());
                return true;
            }
            return false;
        });
        webhookRoutes.values().forEach(list -> list.removeIf(v -> v.startsWith(prefix)));
        webhookRoutes.entrySet().removeIf(e -> e.getValue().isEmpty());
        deviceEventRoutes.values().forEach(list -> list.removeIf(r -> r.deviceId().equals(deviceId)));
        deviceEventRoutes.entrySet().removeIf(e -> e.getValue().isEmpty());
        log.info("All triggers unregistered for device {}", deviceId);
    }

    public void unregisterWorkflow(String deviceId, String definitionId) {
        String prefix = deviceId + ":" + definitionId + ":";
        cronTasks.entrySet().removeIf(e -> {
            if (e.getKey().startsWith(prefix)) {
                e.getValue().cancel(false);
                cronRunning.remove(e.getKey());
                return true;
            }
            return false;
        });
        String value = deviceId + ":" + definitionId;
        webhookRoutes.values().forEach(list -> list.remove(value));
        webhookRoutes.entrySet().removeIf(e -> e.getValue().isEmpty());
        deviceEventRoutes.values().forEach(list ->
                list.removeIf(r -> r.deviceId().equals(deviceId) && r.definitionId().equals(definitionId)));
        deviceEventRoutes.entrySet().removeIf(e -> e.getValue().isEmpty());
        log.info("Triggers unregistered for device={} workflow={}", deviceId, definitionId);
    }

    public String findWebhookDevice(String path) {
        List<String> entries = webhookRoutes.get(path);
        if (entries != null && !entries.isEmpty()) {
            String entry = entries.get(0);
            int idx = entry.indexOf(':');
            return idx > 0 ? entry.substring(0, idx) : entry;
        }
        return null;
    }

    /**
     * Find devices matching an event, with optional sectionId/nodeId filtering.
     * Returns a list of (deviceId, trigger) pairs that match the event.
     */
    public List<Map.Entry<String, TriggerDef.DeviceEventTrigger>> findMatchingTriggers(
            String eventType, String sectionId, String nodeId) {
        List<DeviceEventRoute> routes = deviceEventRoutes.getOrDefault(eventType, List.of());
        List<Map.Entry<String, TriggerDef.DeviceEventTrigger>> result = new ArrayList<>();
        for (DeviceEventRoute route : routes) {
            TriggerDef.DeviceEventTrigger t = route.trigger();
            if (matchesFilter(t.sectionId(), sectionId) && matchesFilter(t.nodeId(), nodeId)) {
                result.add(Map.entry(route.deviceId(), t));
            }
        }
        return result;
    }

    /**
     * @deprecated use findMatchingTriggers(eventType, sectionId, nodeId) instead
     */
    @Deprecated
    public List<String> findDeviceEventDevices(String eventType) {
        List<DeviceEventRoute> routes = deviceEventRoutes.getOrDefault(eventType, List.of());
        return routes.stream().map(DeviceEventRoute::deviceId).distinct().toList();
    }

    private boolean matchesFilter(String filterValue, String actualValue) {
        if (filterValue == null || filterValue.isEmpty()) return true;
        return filterValue.equals(actualValue);
    }

    // ── Trigger status query ──

    /**
     * Returns the status of all triggers registered for a device.
     */
    public List<Map<String, Object>> getTriggerStatus(String deviceId,
                                                       Map<String, WorkflowDefinition> loadedDefinitions) {
        List<Map<String, Object>> result = new ArrayList<>();
        String prefix = deviceId + ":";

        for (var entry : loadedDefinitions.entrySet()) {
            String key = entry.getKey();
            if (!key.startsWith(prefix)) continue;
            String definitionId = key.substring(prefix.length());
            WorkflowDefinition def = entry.getValue();
            if (def.triggers() == null) continue;

            for (TriggerDef trigger : def.triggers()) {
                Map<String, Object> status = new LinkedHashMap<>();
                status.put("triggerId", trigger.id());
                status.put("definitionId", definitionId);
                status.put("definitionName", def.name());

                String taskKey = deviceId + ":" + definitionId + ":" + trigger.id();

                if (trigger instanceof TriggerDef.CronTrigger c) {
                    status.put("type", "cron");
                    status.put("interval", c.interval());
                    status.put("cron", c.cron());
                    ScheduledFuture<?> future = cronTasks.get(taskKey);
                    status.put("active", future != null && !future.isCancelled());
                } else if (trigger instanceof TriggerDef.WebhookTrigger w) {
                    status.put("type", "webhook");
                    status.put("path", w.path());
                    status.put("url", "/api/v1/sdui/webhook/" + w.path());
                    List<String> entries = webhookRoutes.get(w.path());
                    boolean registered = entries != null && entries.contains(key);
                    status.put("registered", registered);
                } else if (trigger instanceof TriggerDef.DeviceEventTrigger d) {
                    status.put("type", "device_event");
                    status.put("event", d.event());
                    status.put("sectionId", d.sectionId());
                    status.put("nodeId", d.nodeId());
                    List<DeviceEventRoute> routes = deviceEventRoutes.get(d.event());
                    boolean registered = routes != null && routes.stream()
                            .anyMatch(r -> r.deviceId().equals(deviceId) && r.definitionId().equals(definitionId));
                    status.put("registered", registered);
                } else if (trigger instanceof TriggerDef.ManualTrigger m) {
                    status.put("type", "manual");
                    status.put("triggered", "on-demand only");
                } else if (trigger instanceof TriggerDef.DeviceCommandTrigger cmd) {
                    status.put("type", "device_command");
                    status.put("command", cmd.command());
                } else if (trigger instanceof TriggerDef.DeviceMessageTrigger msg) {
                    status.put("type", "device_message");
                    status.put("messageType", msg.messageType());
                }

                result.add(status);
            }
        }
        return result;
    }
}
