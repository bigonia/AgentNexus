package com.zwbd.agentnexus.sdui.workflow;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.zwbd.agentnexus.sdui.capability.CapabilityRegistry;
import com.zwbd.agentnexus.sdui.capability.CapabilityValidator;
import com.zwbd.agentnexus.sdui.event.EventPayload;
import com.zwbd.agentnexus.sdui.event.EventRegistry;
import com.zwbd.agentnexus.sdui.section.SectionScene;
import com.zwbd.agentnexus.sdui.section.SectionOrchestrationService;
import com.zwbd.agentnexus.sdui.section.SectionTypeCatalog;
import com.zwbd.agentnexus.sdui.service.SduiCapabilityService;
import com.zwbd.agentnexus.sdui.workflow.node.CapabilityNodeRegistry;
import com.zwbd.agentnexus.sdui.workflow.node.NodeSchema;
import jakarta.annotation.PostConstruct;
import jakarta.transaction.Transactional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.*;

@Slf4j
@Service
@RequiredArgsConstructor
public class WorkflowService {

    private final WorkflowDefinitionRepository definitionRepo;
    private final WorkflowInstanceRepository instanceRepo;
    private final ExecutionRecordRepository executionRecordRepo;
    private final ActionExecutor actionExecutor;
    private final TriggerScheduler triggerScheduler;
    private final SectionOrchestrationService sectionService;
    private final DagExecutor dagExecutor;
    private final SduiCapabilityService capabilityService;
    private final CapabilityRegistry capabilityRegistry;
    private final CapabilityNodeRegistry nodeRegistry;
    private final CapabilityValidator capabilityValidator;
    private final EventRegistry eventRegistry;
    private final ObjectMapper objectMapper;

    // key = "deviceId:definitionId"
    private final Map<String, WorkflowInstance> runningInstances = new LinkedHashMap<>();
    private final Map<String, WorkflowDefinition> loadedDefinitions = new LinkedHashMap<>();
    private final Map<String, Map<String, String>> envConfigs = new LinkedHashMap<>();

    @PostConstruct
    public void recoverRunningInstances() {
        List<WorkflowInstanceEntity> all = instanceRepo.findAll();
        int recovered = 0;
        for (WorkflowInstanceEntity ie : all) {
            if (!"RUNNING".equals(ie.getStatus())) continue;
            try {
                WorkflowDefinitionEntity entity = definitionRepo.findById(ie.getDefinitionId()).orElse(null);
                if (entity == null) {
                    log.warn("Recovery: definition {} not found for device {}, marking STOPPED",
                            ie.getDefinitionId(), ie.getDeviceId());
                    ie.setStatus("STOPPED");
                    instanceRepo.save(ie);
                    continue;
                }
                WorkflowDefinition def = objectMapper.readValue(entity.getDefinitionJson(), WorkflowDefinition.class);
                WorkflowInstance instance = new WorkflowInstance(
                        ie.getDeviceId(), ie.getDefinitionId(), entity.getName());
                instance.activePage(ie.getActivePage());
                String key = instanceKey(ie.getDeviceId(), ie.getDefinitionId());
                loadedDefinitions.put(key, def);
                runningInstances.put(key, instance);

                if (ie.getVariablesJson() != null) {
                    @SuppressWarnings("unchecked")
                    Map<String, Object> vars = objectMapper.readValue(ie.getVariablesJson(), Map.class);
                    instance.variables().putAll(vars);
                }

                Map<String, String> env = envConfigs.getOrDefault(ie.getDefinitionId(), Map.of());
                triggerScheduler.registerTriggers(ie.getDeviceId(), def, instance, actionExecutor, env);
                recovered++;
                log.info("Recovered workflow instance: device={} definition={}", ie.getDeviceId(), ie.getDefinitionId());
            } catch (Exception e) {
                log.error("Failed to recover workflow instance for device {}: {}", ie.getDeviceId(), e.getMessage());
            }
        }
        log.info("Workflow recovery complete: {} instances rehydrated", recovered);
    }

    // ── Definition CRUD ──

    public List<WorkflowDefinitionEntity> listDefinitions() {
        return definitionRepo.findAll();
    }

    public Optional<WorkflowDefinitionEntity> getDefinition(String id) {
        return definitionRepo.findById(id);
    }

    @Transactional
    public WorkflowDefinitionEntity saveDefinition(WorkflowDefinitionEntity entity) {
        return definitionRepo.save(entity);
    }

    @Transactional
    public void deleteDefinition(String id) {
        definitionRepo.deleteById(id);
    }

    // ── Instance management ──

    /**
     * Load a workflow onto a device. Supports multiple workflows per device.
     * Rejects if any Section slots conflict with already-running workflows.
     * Default mode is "strict" — all required capabilities must match.
     */
    @Transactional
    public Map<String, Object> loadWorkflow(String deviceId, String definitionId) {
        return loadWorkflow(deviceId, definitionId, "strict");
    }

    /**
     * Load a workflow onto a device with a specific deployment mode.
     *
     * @param mode "strict" (reject on any capability mismatch),
     *             "adaptive" (skip unsupported optional triggers/actions),
     *             "force" (skip all unsupported elements with warnings)
     */
    @Transactional
    public Map<String, Object> loadWorkflow(String deviceId, String definitionId, String mode) {
        WorkflowDefinitionEntity entity = definitionRepo.findById(definitionId)
                .orElseThrow(() -> new IllegalArgumentException("Workflow definition not found: " + definitionId));

        WorkflowDefinition def;
        try {
            def = objectMapper.readValue(entity.getDefinitionJson(), WorkflowDefinition.class);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("Failed to parse workflow definition JSON", e);
        }

        // Pre-deployment capability validation
        List<Map<String, Object>> capabilityIssues = validateWorkflowCapabilities(deviceId, def);
        List<Map<String, Object>> hardErrors = capabilityIssues.stream()
                .filter(i -> "error".equals(i.get("severity")))
                .toList();
        List<Map<String, Object>> warnings = capabilityIssues.stream()
                .filter(i -> "warning".equals(i.get("severity")))
                .toList();

        DeploymentDecision decision = evaluateDeployment(mode, hardErrors, warnings);

        if (!decision.canDeploy()) {
            return Map.of("status", "capability_mismatch", "deviceId", deviceId,
                    "definitionId", definitionId, "mode", mode,
                    "issues", capabilityIssues,
                    "hint", "工作流引用了设备不支持的能力。连接设备后可调用 GET /console/capabilities 查看设备能力。");
        }

        // Section conflict detection
        List<Map<String, String>> conflicts = detectSectionConflicts(deviceId, def);
        if (!conflicts.isEmpty()) {
            return Map.of("status", "conflict", "deviceId", deviceId, "definitionId", definitionId,
                    "conflicts", conflicts);
        }

        String key = instanceKey(deviceId, definitionId);
        WorkflowInstance instance = new WorkflowInstance(deviceId, definitionId, entity.getName());
        Map<String, String> env = envConfigs.getOrDefault(definitionId, Map.of());

        loadedDefinitions.put(key, def);
        runningInstances.put(key, instance);

        triggerScheduler.registerTriggers(deviceId, def, instance, actionExecutor, env);

        if (def.pages() != null && !def.pages().isEmpty()) {
            PageDef firstPage = def.pages().get(0);
            instance.activePage(firstPage.id());
            sendPageToDevice(deviceId, firstPage, instance, Map.of(), env);
        }

        WorkflowInstanceEntity ie = new WorkflowInstanceEntity();
        ie.setDeviceId(deviceId);
        ie.setDefinitionId(definitionId);
        ie.setDefinitionName(entity.getName());
        ie.setActivePage(instance.activePage());
        ie.setStatus("RUNNING");
        ie.setInstalledAt(LocalDateTime.now());
        instanceRepo.deleteByDeviceIdAndDefinitionId(deviceId, definitionId);
        instanceRepo.save(ie);

        log.info("Workflow loaded: deviceId={} definitionId={} mode={}", deviceId, definitionId, mode);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("status", decision.hasWarnings() ? "loaded_adaptive" : "loaded");
        result.put("deviceId", deviceId);
        result.put("definitionId", definitionId);
        result.put("definitionName", entity.getName());
        result.put("activePage", instance.activePage());
        result.put("mode", mode);
        if (decision.hasWarnings()) {
            result.put("warnings", warnings);
            result.put("skippedCount", warnings.size());
        }
        return result;
    }

    @Transactional
    public Map<String, Object> unloadWorkflow(String deviceId) {
        List<String> definitionIds = new ArrayList<>();
        String prefix = deviceId + ":";
        loadedDefinitions.keySet().stream()
                .filter(k -> k.startsWith(prefix))
                .forEach(k -> definitionIds.add(k.substring(prefix.length())));

        for (String defId : definitionIds) {
            unloadWorkflow(deviceId, defId);
        }
        log.info("All workflows unloaded: deviceId={}", deviceId);
        return Map.of("status", "unloaded", "deviceId", deviceId, "removedWorkflows", definitionIds);
    }

    @Transactional
    public Map<String, Object> unloadWorkflow(String deviceId, String definitionId) {
        String key = instanceKey(deviceId, definitionId);
        triggerScheduler.unregisterWorkflow(deviceId, definitionId);
        WorkflowInstance instance = runningInstances.remove(key);
        if (instance != null) {
            instance.watcher().unregisterAll();
        }
        loadedDefinitions.remove(key);
        instanceRepo.deleteByDeviceIdAndDefinitionId(deviceId, definitionId);
        log.info("Workflow unloaded: deviceId={} definitionId={}", deviceId, definitionId);
        return Map.of("status", "unloaded", "deviceId", deviceId, "definitionId", definitionId);
    }

    @Transactional
    public Map<String, Object> pauseWorkflow(String deviceId, String definitionId) {
        String key = instanceKey(deviceId, definitionId);
        WorkflowInstance instance = runningInstances.get(key);
        if (instance == null) {
            return Map.of("status", "not_found", "deviceId", deviceId, "definitionId", definitionId);
        }

        triggerScheduler.unregisterWorkflow(deviceId, definitionId);
        instance.status(WorkflowInstance.Status.PAUSED);

        instanceRepo.findByDeviceIdAndDefinitionId(deviceId, definitionId).ifPresent(ie -> {
            ie.setStatus("PAUSED");
            instanceRepo.save(ie);
        });

        log.info("Workflow paused: deviceId={} definitionId={}", deviceId, definitionId);
        return Map.of("status", "paused", "deviceId", deviceId, "definitionId", definitionId);
    }

    @Transactional
    public Map<String, Object> resumeWorkflow(String deviceId, String definitionId) {
        String key = instanceKey(deviceId, definitionId);
        WorkflowInstance instance = runningInstances.get(key);
        WorkflowDefinition def = loadedDefinitions.get(key);
        if (instance == null || def == null) {
            return Map.of("status", "not_found", "deviceId", deviceId, "definitionId", definitionId);
        }

        Map<String, String> env = envConfigs.getOrDefault(definitionId, Map.of());
        triggerScheduler.registerTriggers(deviceId, def, instance, actionExecutor, env);
        instance.status(WorkflowInstance.Status.RUNNING);

        instanceRepo.findByDeviceIdAndDefinitionId(deviceId, definitionId).ifPresent(ie -> {
            ie.setStatus("RUNNING");
            instanceRepo.save(ie);
        });

        log.info("Workflow resumed: deviceId={} definitionId={}", deviceId, definitionId);
        return Map.of("status", "resumed", "deviceId", deviceId, "definitionId", definitionId);
    }

    // ── Query ──

    public List<Map<String, Object>> listDeviceWorkflows(String deviceId) {
        List<Map<String, Object>> result = new ArrayList<>();
        String prefix = deviceId + ":";
        for (var entry : runningInstances.entrySet()) {
            if (entry.getKey().startsWith(prefix)) {
                WorkflowInstance inst = entry.getValue();
                result.add(Map.of(
                        "definitionId", inst.workflowId(),
                        "name", inst.definitionName(),
                        "status", inst.status().name(),
                        "activePage", inst.activePage() != null ? inst.activePage() : "",
                        "installedAt", inst.installedAt().toString()
                ));
            }
        }
        return result;
    }

    public Map<String, Object> getWorkflowStatus(String deviceId, String definitionId) {
        String key = instanceKey(deviceId, definitionId);
        WorkflowInstance instance = runningInstances.get(key);
        WorkflowDefinition def = loadedDefinitions.get(key);
        if (instance == null) {
            return Map.of("deviceId", deviceId, "definitionId", definitionId, "status", "not_found");
        }
        return Map.of(
                "deviceId", deviceId,
                "definitionId", definitionId,
                "name", instance.definitionName(),
                "status", instance.status().name(),
                "activePage", instance.activePage() != null ? instance.activePage() : "",
                "installedAt", instance.installedAt().toString(),
                "triggers", def != null ? def.triggers().size() : 0
        );
    }

    public Map<String, Object> getSlotStatus(String deviceId) {
        return sectionService.getSlotStatus(deviceId);
    }

    public Map<String, Object> getDeviceStatus(String deviceId) {
        List<Map<String, Object>> workflows = listDeviceWorkflows(deviceId);
        if (workflows.isEmpty()) {
            return Map.of("deviceId", deviceId, "status", "no_workflow", "workflows", List.of());
        }
        return Map.of("deviceId", deviceId, "status", "active", "workflows", workflows);
    }

    // ── Execution records ──

    public List<ExecutionRecordEntity> getExecutionRecords(String deviceId, int limit) {
        return executionRecordRepo.findByDeviceIdOrderByStartTimeDesc(
                deviceId, PageRequest.of(0, limit));
    }

    public List<ExecutionRecordEntity> getExecutionRecords(String deviceId, String definitionId, int limit) {
        return executionRecordRepo.findByDeviceIdAndDefinitionIdOrderByStartTimeDesc(
                deviceId, definitionId, PageRequest.of(0, limit));
    }

    // ── Variables ──

    public Map<String, Object> getVariables(String deviceId, String definitionId) {
        String key = instanceKey(deviceId, definitionId);
        WorkflowInstance instance = runningInstances.get(key);
        if (instance == null) {
            return Map.of("deviceId", deviceId, "definitionId", definitionId,
                    "status", "not_found", "variables", Map.of());
        }
        return Map.of(
                "deviceId", deviceId,
                "definitionId", definitionId,
                "name", instance.definitionName(),
                "variables", new LinkedHashMap<>(instance.variablesAsMap()));
    }

    // ── Validation ──

    /**
     * Validate that a workflow's triggers and actions reference capabilities
     * supported by the target device. Returns list of issues; empty = all good.
     */
    private List<Map<String, Object>> validateWorkflowCapabilities(String deviceId,
                                                                   WorkflowDefinition def) {
        List<Map<String, Object>> issues = new ArrayList<>();

        if (def.triggers() != null) {
            for (TriggerDef trigger : def.triggers()) {
                if (trigger instanceof TriggerDef.DeviceEventTrigger t) {
                    var result = capabilityValidator.validateEvent(deviceId, t.event());
                    if (!result.valid()) {
                        String severity = t.optional() ? "warning" : "error";
                        issues.add(Map.of(
                                "element", "trigger",
                                "triggerType", "device_event",
                                "triggerId", t.id(),
                                "capability", t.event(),
                                "issue", result.issue(),
                                "severity", severity,
                                "optional", t.optional(),
                                "suggestions", result.suggestions()
                        ));
                    }
                } else if (trigger instanceof TriggerDef.DeviceCommandTrigger t) {
                    var result = capabilityValidator.validateCommand(deviceId, t.command());
                    if (!result.valid()) {
                        String severity = t.optional() ? "warning" : "error";
                        issues.add(Map.of(
                                "element", "trigger",
                                "triggerType", "device_command",
                                "triggerId", t.id(),
                                "capability", t.command(),
                                "issue", result.issue(),
                                "severity", severity,
                                "optional", t.optional(),
                                "suggestions", result.suggestions()
                        ));
                    }
                }
            }
        }

        if (def.actions() != null) {
            for (var entry : def.actions().entrySet()) {
                for (ActionDef action : entry.getValue()) {
                    if (action instanceof ActionDef.ControlAction a) {
                        var result = capabilityValidator.validateCommand(deviceId, a.command());
                        if (!result.valid()) {
                            issues.add(Map.of(
                                    "element", "action",
                                    "actionType", "control",
                                    "actionGroup", entry.getKey(),
                                    "capability", a.command(),
                                    "issue", result.issue(),
                                    "severity", "error",
                                    "suggestions", result.suggestions()
                            ));
                        }
                    } else if (action instanceof ActionDef.NodeActionDef a) {
                        // NodeActionDef references a node type — check if it's resolvable
                        if (nodeRegistry.resolve(deviceId, a.nodeType()) == null) {
                            issues.add(Map.of(
                                    "element", "action",
                                    "actionType", "node",
                                    "actionGroup", entry.getKey(),
                                    "capability", a.nodeType(),
                                    "issue", "Unknown node type: " + a.nodeType(),
                                    "severity", "error"
                            ));
                        }
                    }
                }
            }
        }

        if (!issues.isEmpty()) {
            log.warn("Capability validation found {} issues for device {} workflow {}",
                    issues.size(), deviceId, def.id());
        }
        return issues;
    }

    /**
     * Validate a workflow definition against a device without loading it.
     * Returns all capability issues with severity (error/warning).
     */
    public List<Map<String, Object>> validateWorkflowCapabilities(String deviceId, String definitionId) {
        WorkflowDefinitionEntity entity = definitionRepo.findById(definitionId)
                .orElseThrow(() -> new IllegalArgumentException("Workflow definition not found: " + definitionId));
        WorkflowDefinition def;
        try {
            def = objectMapper.readValue(entity.getDefinitionJson(), WorkflowDefinition.class);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("Failed to parse workflow definition JSON", e);
        }
        return validateWorkflowCapabilities(deviceId, def);
    }

    /**
     * Deployment decision: whether to proceed based on mode and validation results.
     */
    private record DeploymentDecision(boolean canDeploy, boolean hasWarnings) {}

    /**
     * Evaluate whether deployment should proceed given the mode and validation results.
     */
    private DeploymentDecision evaluateDeployment(String mode,
                                                   List<Map<String, Object>> hardErrors,
                                                   List<Map<String, Object>> warnings) {
        return switch (mode != null ? mode : "strict") {
            case "force" -> new DeploymentDecision(true, !hardErrors.isEmpty() || !warnings.isEmpty());
            case "adaptive" -> new DeploymentDecision(hardErrors.isEmpty(), !warnings.isEmpty());
            default -> new DeploymentDecision(hardErrors.isEmpty() && warnings.isEmpty(), false);
        };
    }

    public Map<String, Object> validateDefinition(WorkflowDefinitionEntity entity) {
        List<String> errors = new ArrayList<>();
        List<String> warnings = new ArrayList<>();

        WorkflowDefinition def;
        try {
            def = objectMapper.readValue(entity.getDefinitionJson(), WorkflowDefinition.class);
        } catch (JsonProcessingException e) {
            return Map.of("valid", false, "errors", List.of("Invalid JSON: " + e.getMessage()), "warnings", List.of());
        }

        if (def.name() == null || def.name().isBlank()) {
            errors.add("Workflow name is required.");
        }

        // Validate DAG structure
        if (def.actions() != null && def.triggers() != null) {
            DagValidator.DagResult dagResult = DagValidator.buildAndValidate(
                    def.actions(), def.edges(), def.triggers());
            if (!dagResult.isValid() && dagResult.error() != null) {
                errors.add(dagResult.error());
            }
        }

        // Check for dangling references: actions map keys not referenced in triggers
        if (def.actions() != null && def.triggers() != null) {
            Set<String> referencedActionIds = new LinkedHashSet<>();
            for (TriggerDef trigger : def.triggers()) {
                if (def.actions().containsKey(trigger.id())) {
                    referencedActionIds.add(trigger.id());
                }
            }
            for (String actionId : def.actions().keySet()) {
                if (!referencedActionIds.contains(actionId)) {
                    warnings.add("Action group '" + actionId + "' is not referenced by any trigger.");
                }
            }
        }

        // Check for edges referencing non-existent nodes
        if (def.edges() != null && def.actions() != null) {
            Set<String> nodeIds = def.actions().keySet();
            if (def.triggers() != null) {
                for (TriggerDef t : def.triggers()) nodeIds.add(t.id());
            }
            for (EdgeDef edge : def.edges()) {
                if (!nodeIds.contains(edge.from())) {
                    errors.add("Edge references unknown source node: " + edge.from());
                }
                if (!nodeIds.contains(edge.to())) {
                    errors.add("Edge references unknown target node: " + edge.to());
                }
            }
        }

        if (def.pages() == null || def.pages().isEmpty()) {
            warnings.add("Workflow has no pages defined. No UI will be shown on load.");
        }

        boolean valid = errors.isEmpty();
        return Map.of("valid", valid, "errors", errors, "warnings", warnings);
    }

    // ── Trigger status ──

    public List<Map<String, Object>> getTriggerStatus(String deviceId) {
        return triggerScheduler.getTriggerStatus(deviceId, loadedDefinitions);
    }

    // ── Trigger execution ──

    public boolean triggerManually(String deviceId, String triggerId) {
        // Find which workflow on this device has this trigger
        String prefix = deviceId + ":";
        for (var entry : loadedDefinitions.entrySet()) {
            if (!entry.getKey().startsWith(prefix)) continue;
            WorkflowDefinition def = entry.getValue();
            String definitionId = entry.getKey().substring(prefix.length());
            String key = instanceKey(deviceId, definitionId);
            WorkflowInstance instance = runningInstances.get(key);
            if (instance == null || instance.status() != WorkflowInstance.Status.RUNNING) continue;

            List<ActionDef> actions = def.actions() != null ? def.actions().get(triggerId) : null;
            if (actions == null || actions.isEmpty()) continue;

            Map<String, String> env = envConfigs.getOrDefault(def.id(), Map.of());
            executeTrigger(def, instance, triggerId, Map.of(), env);
            return true;
        }
        log.warn("No running workflow found for device {} with trigger {}", deviceId, triggerId);
        return false;
    }

    /**
     * Receive a device message from external system or another device.
     * Finds matching DeviceMessageTriggers on the target device and executes actions.
     */
    public Map<String, Object> receiveMessage(String deviceId, DeviceMessage message) {
        String prefix = deviceId + ":";
        int fired = 0;
        List<String> matchedWorkflows = new ArrayList<>();

        for (var entry : loadedDefinitions.entrySet()) {
            if (!entry.getKey().startsWith(prefix)) continue;
            String definitionId = entry.getKey().substring(prefix.length());
            String key = instanceKey(deviceId, definitionId);
            WorkflowInstance instance = runningInstances.get(key);
            if (instance == null || instance.status() != WorkflowInstance.Status.RUNNING) continue;

            WorkflowDefinition def = entry.getValue();
            Map<String, String> env = envConfigs.getOrDefault(def.id(), Map.of());

            for (TriggerDef trigger : def.triggers()) {
                if (!(trigger instanceof TriggerDef.DeviceMessageTrigger t)) continue;
                if (t.messageType() != null && !t.messageType().equals(message.messageType())) continue;
                if (t.sourceType() != null && !t.sourceType().equals(message.sourceType())) continue;
                if (t.sourceId() != null && !t.sourceId().equals(message.sourceId())) continue;

                List<ActionDef> actions = def.actions().get(trigger.id());
                if (actions != null) {
                    executeTrigger(def, instance, trigger.id(), message.payload(), env);
                    fired++;
                    matchedWorkflows.add(definitionId);
                }
            }
        }

        if (fired == 0) {
            return Map.of("status", "no_matching_trigger", "deviceId", deviceId,
                    "messageId", message.messageId());
        }
        return Map.of("status", "delivered", "deviceId", deviceId,
                "messageId", message.messageId(), "matchedWorkflows", matchedWorkflows);
    }

    /**
     * Receive a command from the terminal (slash command or natural language).
     * Resolves DeviceCommandTriggers on the target device and executes matching actions.
     */
    public Map<String, Object> receiveCommand(String deviceId, String command, Map<String, Object> params, String rawText) {
        if ("*".equals(deviceId)) {
            // Broadcast to all devices
            List<Map<String, Object>> deviceResults = new ArrayList<>();
            int totalFired = 0;
            for (var entry : new LinkedHashMap<>(runningInstances).entrySet()) {
                String key = entry.getKey();
                int idx = key.indexOf(':');
                if (idx <= 0) continue;
                String devId = key.substring(0, idx);
                Map<String, Object> devResult = receiveCommand(devId, command, params, rawText);
                deviceResults.add(Map.of("deviceId", devId, "result", devResult));
                if ("executed".equals(devResult.get("status"))) {
                    totalFired++;
                }
            }
            return Map.of("status", totalFired > 0 ? "executed" : "no_matching_command",
                    "deviceId", "*", "command", command, "devicesAffected", totalFired,
                    "deviceResults", deviceResults);
        }

        String prefix = deviceId + ":";
        int fired = 0;
        List<String> matchedWorkflows = new ArrayList<>();

        for (var entry : loadedDefinitions.entrySet()) {
            if (!entry.getKey().startsWith(prefix)) continue;
            String definitionId = entry.getKey().substring(prefix.length());
            String key = instanceKey(deviceId, definitionId);
            WorkflowInstance instance = runningInstances.get(key);
            if (instance == null || instance.status() != WorkflowInstance.Status.RUNNING) continue;

            WorkflowDefinition def = entry.getValue();
            Map<String, String> env = envConfigs.getOrDefault(def.id(), Map.of());

            for (TriggerDef trigger : def.triggers()) {
                if (!(trigger instanceof TriggerDef.DeviceCommandTrigger t)) continue;
                if (!t.command().equals(command)) continue;

                Map<String, Object> triggerPayload = new LinkedHashMap<>();
                triggerPayload.put("text", rawText != null ? rawText : "");
                triggerPayload.put("command", command);
                if (params != null) {
                    triggerPayload.putAll(params);
                }

                List<ActionDef> actions = def.actions().get(trigger.id());
                if (actions != null) {
                    executeTrigger(def, instance, trigger.id(), triggerPayload, env);
                    fired++;
                    matchedWorkflows.add(definitionId);
                }
            }
        }

        if (fired == 0) {
            return Map.of("status", "no_matching_command", "deviceId", deviceId, "command", command);
        }
        return Map.of("status", "executed", "deviceId", deviceId, "command", command,
                "matchedWorkflows", matchedWorkflows);
    }

    /**
     * Discover all commands available on a device (from running workflows' DeviceCommandTriggers).
     */
    public List<Map<String, Object>> discoverDeviceCommands(String deviceId) {
        List<Map<String, Object>> commands = new ArrayList<>();
        String prefix = deviceId + ":";

        for (var entry : loadedDefinitions.entrySet()) {
            if (!entry.getKey().startsWith(prefix)) continue;
            String definitionId = entry.getKey().substring(prefix.length());
            WorkflowDefinition def = entry.getValue();

            for (TriggerDef trigger : def.triggers()) {
                if (!(trigger instanceof TriggerDef.DeviceCommandTrigger t)) continue;
                List<Map<String, String>> paramList = new ArrayList<>();
                if (t.params() != null) {
                    for (CommandParam p : t.params()) {
                        paramList.add(Map.of("name", p.name(), "type", p.type(), "description",
                                p.description() != null ? p.description() : ""));
                    }
                }
                commands.add(Map.of(
                        "command", t.command(),
                        "params", paramList,
                        "description", def.name(),
                        "source", "workflow:" + definitionId
                ));
            }
        }
        return commands;
    }

    /**
     * Discover all commands across all online devices in the current space.
     */
    public Map<String, Object> discoverSpaceCommands() {
        Map<String, List<Map<String, Object>>> byDevice = new LinkedHashMap<>();
        for (var entry : runningInstances.entrySet()) {
            String key = entry.getKey();
            int idx = key.indexOf(':');
            if (idx <= 0) continue;
            String deviceId = key.substring(0, idx);
            List<Map<String, Object>> deviceCommands = discoverDeviceCommands(deviceId);
            if (!deviceCommands.isEmpty()) {
                byDevice.put(deviceId, deviceCommands);
            }
        }
        return Map.of("commandsByDevice", byDevice);
    }

    /**
     * Fire a device event using the structured {@link EventPayload}.
     * This is the primary entry point — called by {@code EventInputHandler}.
     *
     * Resolves the event's namespaced ID and matches against
     * {@link TriggerDef.DeviceEventTrigger} entries, respecting
     * sectionId and nodeId filters.
     */
    public int fireEvent(String deviceId, EventPayload payload) {
        String prefix = deviceId + ":";
        // Resolve the namespaced event ID for trigger matching
        String eventId = payload.eventId();
        if (eventId == null) return 0;

        // Also try legacy name for backward compatibility with existing triggers
        String legacyName = eventRegistry.resolveEventId(eventId);
        // For matching, use both namespaced and legacy forms
        String sectionId = payload.hasSectionContext() ? payload.sectionId() : null;
        String nodeId = !payload.nodeId().isEmpty() ? payload.nodeId() : null;

        int fired = 0;
        Map<String, Object> legacyPayload = payload.toLegacyMap();

        for (var entry : loadedDefinitions.entrySet()) {
            if (!entry.getKey().startsWith(prefix)) continue;
            String definitionId = entry.getKey().substring(prefix.length());
            String key = instanceKey(deviceId, definitionId);
            WorkflowInstance instance = runningInstances.get(key);
            if (instance == null || instance.status() != WorkflowInstance.Status.RUNNING) continue;

            WorkflowDefinition def = entry.getValue();
            Map<String, String> env = envConfigs.getOrDefault(def.id(), Map.of());

            for (TriggerDef trigger : def.triggers()) {
                if (!(trigger instanceof TriggerDef.DeviceEventTrigger d)) continue;
                // Match against both namespaced and legacy event names
                if (!matchesEvent(d.event(), eventId, legacyName)) continue;
                if (!matchesFilter(d.sectionId(), sectionId)) continue;
                if (!matchesFilter(d.nodeId(), nodeId)) continue;

                List<ActionDef> actions = def.actions().get(trigger.id());
                if (actions != null) {
                    executeTrigger(def, instance, trigger.id(), legacyPayload, env, payload);
                    fired++;
                }
            }

            // fallback: legacy event-only triggers with blank filters
            if (fired == 0 && (sectionId != null || nodeId != null)) {
                for (TriggerDef trigger : def.triggers()) {
                    if (!(trigger instanceof TriggerDef.DeviceEventTrigger d)) continue;
                    if (!matchesEvent(d.event(), eventId, legacyName)) continue;
                    if (isBlank(d.sectionId()) && isBlank(d.nodeId())) {
                        List<ActionDef> actions = def.actions().get(trigger.id());
                        if (actions != null) {
                            executeTrigger(def, instance, trigger.id(), legacyPayload, env, payload);
                            fired++;
                        }
                    }
                }
            }
        }

        return fired;
    }

    /**
     * @deprecated Use {@link #fireEvent(String, EventPayload)} instead.
     * Kept for backward compatibility with existing callers.
     */
    @Deprecated
    public int fireEvent(String deviceId, String eventType, Map<String, Object> payload) {
        EventPayload eventPayload = EventPayload.fromLegacyMap(deviceId, eventType, payload);
        return fireEvent(deviceId, eventPayload);
    }

    /** Match trigger event name against both namespaced and legacy forms. */
    private boolean matchesEvent(String triggerEvent, String eventId, String legacyName) {
        if (triggerEvent.equals(eventId)) return true;
        if (triggerEvent.equals(legacyName)) return true;
        // Also try matching just the last segment (e.g., "single_click" → "hardware:buttons.boot.single_click")
        if (eventId != null) {
            int lastDot = eventId.lastIndexOf('.');
            if (lastDot >= 0) {
                String lastSegment = eventId.substring(lastDot + 1);
                if (triggerEvent.equals(lastSegment)) return true;
            }
            int lastColon = eventId.lastIndexOf(':');
            if (lastColon >= 0) {
                String afterColon = eventId.substring(lastColon + 1);
                if (triggerEvent.equals(afterColon)) return true;
            }
        }
        return false;
    }

    // ── Node types catalog ──

    /**
     * Get node types catalog. Without deviceId returns global catalog;
     * with deviceId includes device-specific enums from capability reports.
     */
    public Map<String, Object> getNodeTypes() {
        return buildNodeTypesCatalog(null, null);
    }

    public Map<String, Object> getNodeTypes(String deviceId) {
        return buildNodeTypesCatalog(deviceId, null);
    }

    /**
     * Get node types filtered by a device type (auto-discovered capability profile).
     * Useful for workflow editing when no specific device is selected but a
     * device type is known.
     */
    public Map<String, Object> getNodeTypesForDeviceType(String deviceTypeKey) {
        return buildNodeTypesCatalog(null, deviceTypeKey);
    }

    private Map<String, Object> buildNodeTypesCatalog(String deviceId, String deviceTypeKey) {
        boolean hasDevice = deviceId != null && !deviceId.isEmpty();
        boolean hasDeviceType = deviceTypeKey != null && !deviceTypeKey.isEmpty();
        // Resolve capabilities from device type if specified
        CapabilityRegistry.DeviceTypeInfo deviceTypeInfo = null;
        if (hasDeviceType) {
            deviceTypeInfo = capabilityRegistry.getDeviceType(deviceTypeKey).orElse(null);
            if (deviceTypeInfo == null) {
                log.warn("Device type not found: {}, falling back to global catalog", deviceTypeKey);
                hasDeviceType = false;
            }
        }
        boolean hasTarget = hasDevice || hasDeviceType;

        // ── Triggers ──
        List<Map<String, Object>> triggers = new ArrayList<>();

        triggers.add(Map.of("type", "manual", "label", "手动触发", "params", List.of(), "category", "基础"));

        triggers.add(Map.of("type", "cron", "label", "定时触发", "category", "基础",
                "params", List.of(
                        param("interval", "间隔秒数", "number", ""),
                        param("cron", "Cron 表达式", "string", "例如: 0 */5 * * * *")
                )));

        triggers.add(Map.of("type", "webhook", "label", "Webhook 触发", "category", "基础",
                "params", List.of(
                        param("path", "回调路径", "string", "例如: /alerts/github")
                )));

        // Device event trigger: tree-structured event selector
        List<Map<String, Object>> eventParams = new ArrayList<>();
        Map<String, Object> eventParam;
        if (hasDevice) {
            List<Map<String, Object>> eventTree = buildDeviceEventTree(deviceId);
            eventParam = paramWithTree("event", "事件类型", "event_tree", eventTree,
                    "树形事件选择器：分类 → 能力 → 事件");
        } else if (hasDeviceType && deviceTypeInfo != null) {
            List<Map<String, Object>> eventTree = buildDeviceTypeEventTree(deviceTypeInfo);
            eventParam = paramWithTree("event", "事件类型", "event_tree", eventTree,
                    "树形事件选择器（按设备类型 " + deviceTypeInfo.label() + " 过滤）");
        } else {
            List<Map<String, Object>> globalEventTree = buildGlobalEventTree();
            eventParam = paramWithTree("event", "事件类型", "event_tree", globalEventTree,
                    "通用事件树（绑定设备后可精确列表）");
        }
        eventParams.add(eventParam);
        eventParams.add(param("sectionId", "限定 Section", "string", "可选，留空则不限"));
        eventParams.add(param("nodeId", "限定控件", "string", "可选，留空则不限"));
        triggers.add(Map.of("type", "device_event", "label", "设备事件触发", "category", "终端",
                "params", eventParams));

        // Device command trigger: tree-structured command selector
        List<Map<String, Object>> cmdTriggerParams = new ArrayList<>();
        List<Map<String, Object>> cmdTree;
        if (hasDevice) {
            cmdTree = buildDeviceCommandTree(deviceId);
        } else if (hasDeviceType && deviceTypeInfo != null) {
            cmdTree = buildDeviceTypeCommandTree(deviceTypeInfo);
        } else {
            cmdTree = buildGlobalCommandTree();
        }
        cmdTriggerParams.add(paramWithTree("command", "命令名", "command_tree", cmdTree,
                "树形命令选择器：分类 → 能力 → 命令"));
        cmdTriggerParams.add(Map.of(
                "name", "params", "label", "命令参数", "type", "map", "description", "命令所需参数"));
        triggers.add(Map.of("type", "device_command", "label", "命令触发", "category", "终端",
                "params", cmdTriggerParams));

        // ── Actions ──
        List<Map<String, Object>> actions = new ArrayList<>();

        // Unified output node: references CapabilityNode types
        List<Map<String, String>> nodeTypeOptions = new ArrayList<>();
        if (hasTarget) {
            for (NodeSchema schema : hasDevice
                    ? nodeRegistry.getDeviceSchemas(deviceId)
                    : nodeRegistry.getGlobalSchemas()) {
                if ("device".equals(schema.category()) || "platform".equals(schema.category())) {
                    nodeTypeOptions.add(Map.of("value", schema.type(), "label",
                            schema.displayName() + " - " + schema.description()));
                }
            }
        } else {
            for (NodeSchema schema : nodeRegistry.getGlobalSchemas()) {
                if ("platform".equals(schema.category())) {
                    nodeTypeOptions.add(Map.of("value", schema.type(), "label",
                            schema.displayName() + " - " + schema.description()));
                }
            }
            // Add generic device node types
            nodeTypeOptions.add(Map.of("value", "device.control", "label", "执行器命令 - 发送设备控制指令"));
            nodeTypeOptions.add(Map.of("value", "device.audio.play", "label", "播放音频 - 音频预设或TTS"));
            nodeTypeOptions.add(Map.of("value", "device.section.push", "label", "Section 更新 - 下发或更新 Section"));
            nodeTypeOptions.add(Map.of("value", "device.page.update", "label", "页面更新 - 切换或重发页面"));
        }
        actions.add(Map.of("type", "node", "label", "统一输出节点", "category", "终端",
                "description", "选择具体输出行为，参数由节点 Schema 自动约束",
                "params", List.of(
                        paramWithOptions("nodeType", "输出类型", "enum", nodeTypeOptions, "选择输出行为"),
                        Map.of("name", "params", "label", "节点参数", "type", "map",
                                "description", "参数由所选输出类型的 Schema 决定")
                )));

        actions.add(Map.of("type", "fetch", "label", "HTTP 请求", "category", "数据处理",
                "params", List.of(
                        param("url", "请求地址", "string", "支持 $data.xxx / $trigger.xxx"),
                        param("method", "请求方法", "string", "GET / POST"),
                        param("save", "存储变量名", "string", "结果保存到 $data.<name>")
                )));

        actions.add(Map.of("type", "set_variable", "label", "设置变量", "category", "数据处理",
                "params", List.of(
                        param("variable", "变量名", "string", "不含 $data. 前缀"),
                        param("value", "变量值", "string",
                                "支持 $data.xxx / $trigger.xxx / $env.XXX / 字面量 / 表达式")
                ),
                "syntax", "支持表达式: $data.x + 10 / '文本' / min(a,b) / $data.x == 'playing' ? '暂停' : '播放'"));

        actions.add(Map.of("type", "condition", "label", "条件分支", "category", "流程控制",
                "params", List.of(
                        param("variable", "判断变量", "string", "$data.xxx"),
                        param("operator", "运算符", "enum",
                                "eq / neq / gt / gte / lt / lte / contains / isEmpty"),
                        param("value", "比较值", "string", "支持字面量和 $data.xxx"),
                        param("thenActions", "条件成立时执行", "actions[]", "动作数组"),
                        param("elseActions", "条件不成立时执行", "actions[]", "可选，动作数组")
                ),
                "syntax", "thenActions 和 elseActions 均为动作数组，可嵌套 condition/sequence"));

        actions.add(Map.of("type", "sequence", "label", "顺序执行", "category", "流程控制",
                "params", List.of(
                        param("steps", "步骤列表", "actions[]", "按顺序执行的动作数组")
                ),
                "syntax", "可包含任意类型动作，常用于 condition 分支内部"));

        if (hasDevice) {
            List<Map<String, Object>> controlCmdTree = buildDeviceCommandTree(deviceId);
            actions.add(Map.of("type", "control", "label", "执行器命令", "category", "终端",
                    "params", List.of(
                            paramWithTree("command", "命令名", "command_tree", controlCmdTree,
                                    "树形命令选择器：从设备能力中选取"),
                            param("value", "命令值", "string", "支持 $data.xxx / $trigger.xxx")
                    )));
        } else if (hasDeviceType && deviceTypeInfo != null) {
            List<Map<String, Object>> typeCmdTree = buildDeviceTypeCommandTree(deviceTypeInfo);
            actions.add(Map.of("type", "control", "label", "执行器命令", "category", "终端",
                    "params", List.of(
                            paramWithTree("command", "命令名", "command_tree", typeCmdTree,
                                    "树形命令选择器（按设备类型 " + deviceTypeInfo.label() + " 过滤）"),
                            param("value", "命令值", "string", "支持 $data.xxx / $trigger.xxx")
                    )));
        } else {
            List<Map<String, Object>> globalCmdTree = buildGlobalCommandTree();
            actions.add(Map.of("type", "control", "label", "执行器命令", "category", "终端",
                    "params", List.of(
                            paramWithTree("command", "命令名", "command_tree", globalCmdTree,
                                    "通用命令树（绑定设备后可精确列表）"),
                            param("value", "命令值", "string", "支持 $data.xxx / $trigger.xxx")
                    )));
        }

        actions.add(Map.of("type", "play_audio", "label", "播放音频", "category", "终端",
                "params", List.of(
                        param("preset", "预设音", "enum",
                                "notification/success/error/warning/click/beep")
                )));

        actions.add(Map.of("type", "tts", "label", "TTS 朗读", "category", "终端",
                "params", List.of(
                        param("text", "朗读文本", "string", "支持 $data.xxx / $trigger.xxx")
                )));

        actions.add(Map.of("type", "patch_section", "label", "增量更新 Section", "category", "UI",
                "params", List.of(
                        param("page", "页面 ID", "string", ""),
                        param("sectionId", "Section ID", "string", ""),
                        param("bind", "绑定数据源", "string", "支持 $data.xxx")
                )));

        actions.add(Map.of("type", "update_page", "label", "下发页面", "category", "UI",
                "params", List.of(
                        param("page", "页面 ID", "string", "")
                )));

        actions.add(Map.of("type", "switch_page", "label", "切换页面", "category", "UI",
                "params", List.of(
                        param("page", "页面 ID", "string", "")
                )));

        // ── Section types (from SectionTypeCatalog) ──
        List<Map<String, Object>> sectionTypes = new ArrayList<>();
        for (var def : SectionTypeCatalog.all().values()) {
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("type", def.type());
            entry.put("label", def.displayName());
            entry.put("interactive", def.interactive());
            entry.put("fields", SectionTypeCatalog.fieldsToMaps(def.displayFields()));
            entry.put("interactionEvents", def.interactionEvents().stream()
                    .map(e -> Map.of(
                            "eventId", e.eventId(),
                            "description", e.description(),
                            "params", e.params().stream()
                                    .map(p -> Map.of("name", p.name(), "type", p.type(),
                                            "description", p.description()))
                                    .toList()))
                    .toList());
            entry.put("constraints", def.defaultConstraints());
            if (hasTarget) {
                boolean supported = hasDevice
                        ? capabilityRegistry.supportsSectionType(deviceId, def.type())
                        : (deviceTypeInfo != null && deviceTypeInfo.sectionTypes().contains(def.type()));
                entry.put("deviceSupported", supported);
            }
            sectionTypes.add(entry);
        }

        return Map.of(
                "triggers", triggers,
                "actions", actions,
                "sectionTypes", sectionTypes
        );
    }

    // ── Options builders ──

    private List<Map<String, String>> buildEventOptions(String deviceId) {
        // Use EventRegistry for structured event data
        List<Map<String, String>> options = new ArrayList<>();
        var snapshot = capabilityRegistry.getDeviceSnapshot(deviceId);
        if (snapshot.isPresent()) {
            // Device hardware events — cross-reference with EventRegistry for display names
            for (String event : snapshot.get().inputEvents()) {
                String resolvedId = eventRegistry.resolveEventId(event);
                var def = eventRegistry.getInboundEvent(resolvedId);
                String label = def.map(ed -> ed.displayName() + " (" + ed.eventId() + ")")
                        .orElse(event);
                String source = def.map(ed -> ed.category().label()).orElse("硬件");
                options.add(Map.of("value", resolvedId, "label", label, "source", source));
            }
            // Section interaction events
            for (String event : capabilityRegistry.getDeviceInteractionEvents(deviceId)) {
                String resolvedId = eventRegistry.resolveEventId(event);
                var def = eventRegistry.getInboundEvent(resolvedId);
                String label = def.map(ed -> ed.displayName() + " (" + ed.eventId() + ")")
                        .orElse(event);
                options.add(Map.of("value", resolvedId, "label", label, "source", "Section交互"));
            }
        }
        // Also include events from EventRegistry that this device supports
        for (var def : eventRegistry.getAllInboundEvents()) {
            boolean alreadyAdded = options.stream().anyMatch(o -> def.eventId().equals(o.get("value")));
            if (!alreadyAdded && snapshot.isPresent() && snapshot.get().supportsEvent(def.eventId())) {
                options.add(Map.of("value", def.eventId(), "label",
                        def.displayName() + " (" + def.eventId() + ")",
                        "source", def.category().label()));
            }
        }
        return options;
    }

    private List<Map<String, String>> buildGlobalEventOptions() {
        // Use EventRegistry for structured, categorized event options
        List<Map<String, String>> options = new ArrayList<>();
        for (var def : eventRegistry.getAllInboundEvents()) {
            options.add(Map.of(
                    "value", def.eventId(),
                    "label", def.displayName() + " (" + def.eventId() + ")",
                    "source", def.category().label()));
        }
        // Fallback if EventRegistry is empty
        if (options.isEmpty()) {
            options.add(Map.of("value", "button.click", "label", "button.click (通用)", "source", "硬件"));
            options.add(Map.of("value", "imu.shake", "label", "imu.shake (通用)", "source", "硬件"));
            options.add(Map.of("value", "action.click", "label", "action.click (通用)", "source", "Section交互"));
        }
        return options;
    }

    private List<Map<String, String>> buildCommandOptions(String deviceId) {
        List<Map<String, String>> options = new ArrayList<>();
        var snapshot = capabilityRegistry.getDeviceSnapshot(deviceId);
        if (snapshot.isPresent()) {
            for (String cmd : snapshot.get().outputCommands()) {
                options.add(Map.of("value", cmd, "label", cmd));
            }
        }
        return options;
    }

    private List<Map<String, String>> buildGlobalCommandOptions() {
        List<Map<String, String>> options = new ArrayList<>();
        for (String cmd : capabilityRegistry.getKnownCommands()) {
            options.add(Map.of("value", cmd, "label", cmd));
        }
        if (options.isEmpty()) {
            options.add(Map.of("value", "rgb.effect.set", "label", "rgb.effect.set (通用)"));
            options.add(Map.of("value", "audio.prompt.play", "label", "audio.prompt.play (通用)"));
            options.add(Map.of("value", "device.reboot", "label", "device.reboot (通用)"));
            options.add(Map.of("value", "display.section.render", "label", "display.section.render (通用)"));
        }
        return options;
    }

    private Map<String, Object> paramWithOptions(String name, String label, String type,
                                                  List<Map<String, String>> options, String description) {
        Map<String, Object> p = param(name, label, type, description);
        p.put("options", options);
        return p;
    }

    /** Build a param with a tree structure for hierarchical event/command selection. */
    private Map<String, Object> paramWithTree(String name, String label, String type,
                                               List<Map<String, Object>> tree, String description) {
        Map<String, Object> p = param(name, label, type, description);
        p.put("tree", tree);
        return p;
    }

    // ── Tree builders for event/command selectors ──

    /**
     * Build a device-specific event tree: only events the device supports,
     * enriched with EventDefinition metadata (payload schemas, descriptions).
     */
    private List<Map<String, Object>> buildDeviceEventTree(String deviceId) {
        var snapshot = capabilityRegistry.getDeviceSnapshot(deviceId);
        if (snapshot.isEmpty()) return buildGlobalEventTree();

        var caps = snapshot.get();
        List<Map<String, Object>> tree = new ArrayList<>();

        // Get all EventDefinitions and filter by device support
        for (var def : eventRegistry.getAllInboundEvents()) {
            String resolvedId = eventRegistry.resolveEventId(def.eventId());
            boolean supported = caps.supportsEvent(def.eventId())
                    || caps.supportsEvent(resolvedId)
                    || caps.inputEvents().stream().anyMatch(e ->
                        eventRegistry.resolveEventId(e).equals(def.eventId()));

            if (!supported) continue;

            addEventToTree(tree, def);
        }

        // Sort categories
        tree.sort(Comparator.comparing(m -> (String) m.get("label")));
        return tree;
    }

    /** Build an event tree filtered by a device type's capabilities. */
    private List<Map<String, Object>> buildDeviceTypeEventTree(
            CapabilityRegistry.DeviceTypeInfo deviceTypeInfo) {
        List<Map<String, Object>> tree = new ArrayList<>();
        for (var def : eventRegistry.getAllInboundEvents()) {
            String resolvedId = eventRegistry.resolveEventId(def.eventId());
            boolean supported = deviceTypeInfo.inputEvents().contains(def.eventId())
                    || deviceTypeInfo.inputEvents().contains(resolvedId)
                    || deviceTypeInfo.inputEvents().stream().anyMatch(e ->
                        eventRegistry.resolveEventId(e).equals(def.eventId()));
            if (!supported) continue;
            addEventToTree(tree, def);
        }
        tree.sort(Comparator.comparing(m -> (String) m.get("label")));
        return tree;
    }

    /** Build a global event tree: all known events from EventRegistry. */
    private List<Map<String, Object>> buildGlobalEventTree() {
        List<Map<String, Object>> tree = new ArrayList<>();
        for (var def : eventRegistry.getAllInboundEvents()) {
            addEventToTree(tree, def);
        }
        tree.sort(Comparator.comparing(m -> (String) m.get("label")));

        // Fallback if registry is empty
        if (tree.isEmpty()) {
            Map<String, Object> fallback = new LinkedHashMap<>();
            fallback.put("category", "HARDWARE_BUTTON");
            fallback.put("label", "物理按钮");
            fallback.put("capabilities", List.of(
                    Map.of("capability", "buttons.boot", "label", "BOOT 按钮", "events", List.of(
                            eventLeaf("hardware:buttons.boot.single_click", "单击", "buttons.boot"),
                            eventLeaf("hardware:buttons.boot.double_click", "双击", "buttons.boot"),
                            eventLeaf("hardware:buttons.boot.long_press_start", "长按开始", "buttons.boot")
                    ))
            ));
            tree.add(fallback);
        }
        return tree;
    }

    private void addEventToTree(List<Map<String, Object>> tree, com.zwbd.agentnexus.sdui.event.EventDefinition def) {
        String catLabel = def.category().label();
        String catName = def.category().name();

        // Find or create category node
        Map<String, Object> catNode = tree.stream()
                .filter(m -> catName.equals(m.get("category")))
                .findFirst().orElse(null);
        if (catNode == null) {
            catNode = new LinkedHashMap<>();
            catNode.put("category", catName);
            catNode.put("label", catLabel);
            catNode.put("capabilities", new ArrayList<Map<String, Object>>());
            tree.add(catNode);
        }

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> capabilities = (List<Map<String, Object>>) catNode.get("capabilities");

        // Find or create capability node
        String capName = def.sourceCapability();
        Map<String, Object> capNode = capabilities.stream()
                .filter(m -> capName.equals(m.get("capability")))
                .findFirst().orElse(null);
        if (capNode == null) {
            capNode = new LinkedHashMap<>();
            capNode.put("capability", capName);
            capNode.put("label", def.displayName());
            capNode.put("description", def.description());
            capNode.put("events", new ArrayList<Map<String, Object>>());
            capabilities.add(capNode);
        }

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> events = (List<Map<String, Object>>) capNode.get("events");

        // Add event leaf
        events.add(eventLeaf(def.eventId(), def.displayName(), def.sourceCapability()));
    }

    private Map<String, Object> eventLeaf(String eventId, String displayName, String source) {
        Map<String, Object> leaf = new LinkedHashMap<>();
        leaf.put("eventId", eventId);
        leaf.put("displayName", displayName);
        leaf.put("source", source);

        // Enrich with EventDefinition if available
        var def = eventRegistry.getInboundEvent(eventId);
        def.ifPresent(d -> {
            leaf.put("description", d.description());
            leaf.put("category", d.category().name());
            leaf.put("payloadSchema", d.payloadSchema().stream()
                    .map(pd -> Map.of("name", pd.name(), "type", pd.type(),
                            "required", pd.required(), "description",
                            pd.description() != null ? pd.description() : ""))
                    .toList());
        });
        return leaf;
    }

    /** Build device-specific command tree. */
    private List<Map<String, Object>> buildDeviceCommandTree(String deviceId) {
        var snapshot = capabilityRegistry.getDeviceSnapshot(deviceId);
        if (snapshot.isEmpty()) return buildGlobalCommandTree();

        var caps = snapshot.get();
        List<Map<String, Object>> tree = new ArrayList<>();

        for (var def : eventRegistry.getAllOutboundEvents()) {
            if (!caps.supportsCommand(def.eventId())) continue;

            addCommandToTree(tree, def);
        }
        tree.sort(Comparator.comparing(m -> (String) m.get("label")));
        return tree;
    }

    /** Build a command tree filtered by a device type's capabilities. */
    private List<Map<String, Object>> buildDeviceTypeCommandTree(
            CapabilityRegistry.DeviceTypeInfo deviceTypeInfo) {
        List<Map<String, Object>> tree = new ArrayList<>();
        for (var def : eventRegistry.getAllOutboundEvents()) {
            if (!deviceTypeInfo.outputCommands().contains(def.eventId())) continue;
            addCommandToTree(tree, def);
        }
        tree.sort(Comparator.comparing(m -> (String) m.get("label")));
        return tree;
    }

    /** Build global command tree. */
    private List<Map<String, Object>> buildGlobalCommandTree() {
        List<Map<String, Object>> tree = new ArrayList<>();
        for (var def : eventRegistry.getAllOutboundEvents()) {
            addCommandToTree(tree, def);
        }
        tree.sort(Comparator.comparing(m -> (String) m.get("label")));

        if (tree.isEmpty()) {
            Map<String, Object> fallback = new LinkedHashMap<>();
            fallback.put("category", "LIGHTING");
            fallback.put("label", "灯光控制");
            fallback.put("capabilities", List.of(
                    Map.of("capability", "rgb.effect", "label", "RGB 灯光", "commands", List.of(
                            commandLeaf("rgb.effect.set", "灯光效果", "rgb.effect"),
                            commandLeaf("rgb.off", "关闭灯光", "rgb.effect")
                    ))
            ));
            tree.add(fallback);
        }
        return tree;
    }

    private void addCommandToTree(List<Map<String, Object>> tree, com.zwbd.agentnexus.sdui.event.EventDefinition def) {
        String catLabel = def.category().label();
        String catName = def.category().name();

        Map<String, Object> catNode = tree.stream()
                .filter(m -> catName.equals(m.get("category")))
                .findFirst().orElse(null);
        if (catNode == null) {
            catNode = new LinkedHashMap<>();
            catNode.put("category", catName);
            catNode.put("label", catLabel);
            catNode.put("capabilities", new ArrayList<Map<String, Object>>());
            tree.add(catNode);
        }

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> capabilities = (List<Map<String, Object>>) catNode.get("capabilities");

        String capName = def.sourceCapability();
        Map<String, Object> capNode = capabilities.stream()
                .filter(m -> capName.equals(m.get("capability")))
                .findFirst().orElse(null);
        if (capNode == null) {
            capNode = new LinkedHashMap<>();
            capNode.put("capability", capName);
            capNode.put("label", def.displayName());
            capNode.put("description", def.description());
            capNode.put("commands", new ArrayList<Map<String, Object>>());
            capabilities.add(capNode);
        }

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> commands = (List<Map<String, Object>>) capNode.get("commands");
        commands.add(commandLeaf(def.eventId(), def.displayName(), def.sourceCapability()));
    }

    private Map<String, Object> commandLeaf(String commandId, String displayName, String source) {
        Map<String, Object> leaf = new LinkedHashMap<>();
        leaf.put("commandId", commandId);
        leaf.put("displayName", displayName);
        leaf.put("source", source);

        var def = eventRegistry.getOutboundEvent(commandId);
        def.ifPresent(d -> {
            leaf.put("description", d.description());
            leaf.put("category", d.category().name());
            leaf.put("payloadSchema", d.payloadSchema().stream()
                    .map(pd -> Map.of("name", pd.name(), "type", pd.type(),
                            "required", pd.required(), "description",
                            pd.description() != null ? pd.description() : ""))
                    .toList());
        });
        return leaf;
    }

    // ── Internal helpers ──

    public void sendPageToDevice(String deviceId, PageDef page, WorkflowInstance instance,
                                  Map<String, Object> triggerPayload, Map<String, String> env) {
        SectionScene scene = actionExecutor.buildPageSceneWithBindings(
                page, instance.variablesAsMap(), triggerPayload, env, instance.watcher());
        sectionService.sendScene(deviceId, scene);
    }

    /**
     * Execute trigger actions using DAG mode if edges are defined, otherwise legacy sequential mode.
     */
    private void executeTrigger(WorkflowDefinition def, WorkflowInstance instance,
                                String triggerId, Map<String, Object> triggerPayload,
                                Map<String, String> env) {
        executeTrigger(def, instance, triggerId, triggerPayload, env, null);
    }

    private void executeTrigger(WorkflowDefinition def, WorkflowInstance instance,
                                String triggerId, Map<String, Object> triggerPayload,
                                Map<String, String> env, EventPayload eventPayload) {
        ExecutionRecordEntity record = new ExecutionRecordEntity();
        record.setDeviceId(instance.deviceId());
        record.setDefinitionId(instance.workflowId());
        record.setDefinitionName(instance.definitionName());
        record.setTriggerId(triggerId);
        record.setStartTime(LocalDateTime.now());
        record.setStatus("RUNNING");
        record = executionRecordRepo.save(record);

        try {
            if (def.edges() != null && !def.edges().isEmpty()) {
                dagExecutor.executeDag(def, instance, triggerPayload, env, triggerId);
            } else {
                List<ActionDef> actions = def.actions() != null ? def.actions().get(triggerId) : null;
                if (actions != null) {
                    actionExecutor.execute(actions, instance, triggerPayload, env, eventPayload);
                }
            }
            record.setStatus("SUCCESS");
            record.setEndTime(LocalDateTime.now());
            executionRecordRepo.save(record);
        } catch (Exception e) {
            log.error("Trigger execution failed: device={} workflow={} trigger={} error={}",
                    instance.deviceId(), instance.workflowId(), triggerId, e.getMessage());
            record.setStatus("ERROR");
            record.setEndTime(LocalDateTime.now());
            record.setErrorMessage(e.getMessage());
            executionRecordRepo.save(record);
        }
    }

    /**
     * Detect Section conflicts between a new workflow definition and
     * all currently running workflows on the target device.
     * Returns a list of conflict descriptions.
     */
    private List<Map<String, String>> detectSectionConflicts(String deviceId, WorkflowDefinition newDef) {
        List<Map<String, String>> conflicts = new ArrayList<>();
        if (newDef.pages() == null || newDef.pages().isEmpty()) return conflicts;

        // Collect all section slots from newly loaded definition
        Set<String> newSlots = new LinkedHashSet<>();
        for (PageDef page : newDef.pages()) {
            if (page.sections() == null) continue;
            for (SectionBindDef s : page.sections()) {
                newSlots.add(page.id() + "/" + s.id());
            }
        }

        // Check against running workflows on this device
        String prefix = deviceId + ":";
        for (var entry : loadedDefinitions.entrySet()) {
            if (!entry.getKey().startsWith(prefix)) continue;
            String existingDefId = entry.getKey().substring(prefix.length());
            WorkflowDefinition existingDef = entry.getValue();
            if (existingDef.pages() == null) continue;

            for (PageDef page : existingDef.pages()) {
                if (page.sections() == null) continue;
                for (SectionBindDef s : page.sections()) {
                    String slot = page.id() + "/" + s.id();
                    if (newSlots.contains(slot)) {
                        conflicts.add(Map.of(
                                "slot", slot,
                                "sectionType", s.type(),
                                "ownedBy", existingDefId,
                                "ownedByName", existingDef.name()
                        ));
                    }
                }
            }
        }
        return conflicts;
    }

    private String instanceKey(String deviceId, String definitionId) {
        return deviceId + ":" + definitionId;
    }

    private boolean matchesFilter(String filterValue, String actualValue) {
        if (filterValue == null || filterValue.isEmpty()) return true;
        return filterValue.equals(actualValue);
    }

    private boolean isBlank(String s) {
        return s == null || s.isBlank();
    }

    // ── App Store ──

    @Transactional
    public Map<String, Object> publishDefinition(String id) {
        WorkflowDefinitionEntity entity = definitionRepo.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Definition not found: " + id));
        entity.setStatus("published");
        definitionRepo.save(entity);
        log.info("Workflow published to app store: {}", id);
        return Map.of("status", "published", "id", id, "version", entity.getVersion());
    }

    @Transactional
    public Map<String, Object> unpublishDefinition(String id) {
        WorkflowDefinitionEntity entity = definitionRepo.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Definition not found: " + id));
        entity.setStatus("draft");
        definitionRepo.save(entity);
        log.info("Workflow unpublished from app store: {}", id);
        return Map.of("status", "draft", "id", id);
    }

    public List<Map<String, Object>> browseStore(String category, String deviceId) {
        List<WorkflowDefinitionEntity> all = definitionRepo.findAll();
        List<Map<String, Object>> result = new ArrayList<>();

        for (WorkflowDefinitionEntity entity : all) {
            if (!"published".equals(entity.getStatus())) continue;
            if (category != null && !category.isEmpty()
                    && !category.equals(entity.getCategory())) continue;

            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("id", entity.getId());
            entry.put("name", entity.getName());
            entry.put("icon", entity.getIcon());
            entry.put("category", entity.getCategory());
            entry.put("version", entity.getVersion());
            entry.put("createdAt", entity.getCreatedAt() != null ? entity.getCreatedAt().toString() : null);

            if (deviceId != null && !deviceId.isEmpty()) {
                entry.put("compatible", checkDeviceCompatibility(deviceId, entity));
            }
            result.add(entry);
        }
        return result;
    }

    public List<String> getStoreCategories() {
        return definitionRepo.findAll().stream()
                .filter(e -> "published".equals(e.getStatus()))
                .map(WorkflowDefinitionEntity::getCategory)
                .filter(c -> c != null && !c.isEmpty())
                .distinct()
                .sorted()
                .toList();
    }

    public Map<String, Object> checkCompatibility(String definitionId, String deviceId) {
        WorkflowDefinitionEntity entity = definitionRepo.findById(definitionId)
                .orElseThrow(() -> new IllegalArgumentException("Definition not found: " + definitionId));
        boolean compatible = checkDeviceCompatibility(deviceId, entity);

        // Check section conflicts
        String key = instanceKey(deviceId, definitionId);
        WorkflowDefinition def = loadedDefinitions.get(key);
        List<Map<String, String>> conflicts = List.of();
        if (def == null) {
            try {
                def = objectMapper.readValue(entity.getDefinitionJson(), WorkflowDefinition.class);
            } catch (Exception e) {
                return Map.of("compatible", false, "error", "Failed to parse definition");
            }
        }
        conflicts = detectSectionConflicts(deviceId, def);

        return Map.of(
                "definitionId", definitionId,
                "deviceId", deviceId,
                "compatible", compatible && conflicts.isEmpty(),
                "capabilityCompatible", compatible,
                "sectionConflicts", conflicts
        );
    }

    private boolean checkDeviceCompatibility(String deviceId, WorkflowDefinitionEntity entity) {
        if (entity.getRequiredCapsJson() == null || entity.getRequiredCapsJson().isEmpty()) {
            return true;
        }
        try {
            @SuppressWarnings("unchecked")
            List<String> required = objectMapper.readValue(entity.getRequiredCapsJson(), List.class);
            var capsOpt = capabilityService.getCapabilities(deviceId);
            if (capsOpt.isEmpty()) return false;

            var caps = capsOpt.get();
            Set<String> availableCaps = new LinkedHashSet<>();
            if (caps.inputs() != null) {
                availableCaps.addAll(caps.inputs());
            }
            if (caps.outputs() != null) {
                availableCaps.addAll(caps.outputs());
            }
            if (caps.display() != null) {
                availableCaps.add("display");
                availableCaps.add("section");
            }

            for (String cap : required) {
                if (!availableCaps.contains(cap)) return false;
            }
            return true;
        } catch (Exception e) {
            log.warn("Failed to parse required caps for {}: {}", entity.getId(), e.getMessage());
            return true;
        }
    }

    private Map<String, Object> param(String name, String label, String type, String syntax) {
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("name", name);
        p.put("label", label);
        p.put("type", type);
        if (syntax != null && !syntax.isEmpty()) p.put("syntax", syntax);
        return p;
    }
}
