package com.zwbd.agentnexus.sdui.workflow;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.zwbd.agentnexus.sdui.capability.CapabilityInvocationValidator;
import com.zwbd.agentnexus.sdui.capability.CapabilityValidator;
import com.zwbd.agentnexus.sdui.event.EventPayload;
import com.zwbd.agentnexus.sdui.section.DebugSectionWorkspaceService;
import com.zwbd.agentnexus.sdui.section.SectionOrchestrationService;
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
    private final DebugSectionWorkspaceService workspaceService;
    private final WorkflowPageRuntimeService pageRuntimeService;
    private final DagExecutor dagExecutor;
    private final SduiCapabilityService capabilityService;
    private final CapabilityNodeRegistry nodeRegistry;
    private final CapabilityValidator capabilityValidator;
    private final CapabilityInvocationValidator invocationValidator;
    private final WorkflowDefinitionNormalizer definitionNormalizer;
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
                WorkflowDefinition def = definitionNormalizer.normalize(
                        objectMapper.readValue(entity.getDefinitionJson(), WorkflowDefinition.class));
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
        if (entity.getDefinitionJson() != null && !entity.getDefinitionJson().isBlank()) {
            try {
                WorkflowDefinition definition = objectMapper.readValue(entity.getDefinitionJson(), WorkflowDefinition.class);
                entity.setDefinitionJson(objectMapper.writeValueAsString(definitionNormalizer.normalize(definition)));
            } catch (JsonProcessingException e) {
                throw new IllegalArgumentException("Failed to normalize workflow definition JSON", e);
            }
        }
        return definitionRepo.save(entity);
    }

    @Transactional
    public void deleteDefinition(String id) {
        definitionRepo.deleteById(id);
    }

    // ── Instance management ──

    /**
     * Load a workflow onto a device. Supports multiple workflows per device.
     * Rejects if any page/section bindings conflict with already-running workflows.
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
            def = definitionNormalizer.normalize(
                    objectMapper.readValue(entity.getDefinitionJson(), WorkflowDefinition.class));
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
                    "hint", "工作流引用了设备不支持的能力。可调用 GET /api/v1/sdui/capabilities/" + deviceId + " 查看设备能力。");
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
            pageRuntimeService.sendPageToDevice(deviceId, firstPage, instance, Map.of(), env);
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
                Map<String, Object> item = new LinkedHashMap<>();
                item.put("definitionId", inst.workflowId());
                item.put("name", inst.definitionName());
                item.put("status", inst.status().name());
                item.put("activePage", inst.activePage() != null ? inst.activePage() : "");
                item.put("installedAt", inst.installedAt().toString());
                item.put("variables", new LinkedHashMap<>(inst.variablesAsMap()));
                item.put("pageState", workspaceService.getWorkflowState(deviceId));
                ExecutionRecordEntity lastExecution = executionRecordRepo.findByDeviceIdAndDefinitionIdOrderByStartTimeDesc(
                        deviceId, inst.workflowId(), PageRequest.of(0, 1)).stream().findFirst().orElse(null);
                if (lastExecution != null) {
                    item.put("lastExecution", toExecutionRecordView(lastExecution));
                }
                result.add(item);
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
                "triggers", def != null ? def.triggers().size() : 0,
                "variables", new LinkedHashMap<>(instance.variablesAsMap()),
                "pageState", workspaceService.getWorkflowState(deviceId),
                "recentExecutions", getExecutionRecords(deviceId, definitionId, 10)
        );
    }

    public Map<String, Object> getPageState(String deviceId) {
        return workspaceService.getWorkflowState(deviceId);
    }

    public Map<String, Object> getDeviceStatus(String deviceId) {
        List<Map<String, Object>> workflows = listDeviceWorkflows(deviceId);
        if (workflows.isEmpty()) {
            return Map.of("deviceId", deviceId, "status", "no_workflow", "workflows", List.of());
        }
        return Map.of(
                "deviceId", deviceId,
                "status", "active",
                "workflows", workflows,
                "pageState", getPageState(deviceId),
                "recentExecutions", getExecutionRecords(deviceId, 20)
        );
    }

    // ── Execution records ──

    public List<Map<String, Object>> getExecutionRecords(String deviceId, int limit) {
        return executionRecordRepo.findByDeviceIdOrderByStartTimeDesc(
                deviceId, PageRequest.of(0, limit)).stream().map(this::toExecutionRecordView).toList();
    }

    public List<Map<String, Object>> getExecutionRecords(String deviceId, String definitionId, int limit) {
        return executionRecordRepo.findByDeviceIdAndDefinitionIdOrderByStartTimeDesc(
                deviceId, definitionId, PageRequest.of(0, limit)).stream().map(this::toExecutionRecordView).toList();
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
                if (trigger instanceof TriggerDef.DeviceUiEventTrigger t) {
                    var result = capabilityValidator.validateEvent(deviceId, t.eventType());
                    if (!result.valid()) {
                        String severity = t.optional() ? "warning" : "error";
                        issues.add(Map.of(
                                "element", "trigger",
                                "triggerType", "device.ui.event",
                                "triggerId", t.id(),
                                "capability", t.eventType(),
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
                validateActionsForDevice(deviceId, entry.getKey(), entry.getValue(), issues);
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
            def = definitionNormalizer.normalize(
                    objectMapper.readValue(entity.getDefinitionJson(), WorkflowDefinition.class));
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
        List<Map<String, Object>> triggerIssues = new ArrayList<>();
        List<Map<String, Object>> nodeIssues = new ArrayList<>();
        List<Map<String, Object>> pageIssues = new ArrayList<>();
        List<Map<String, Object>> bindingIssues = new ArrayList<>();
        List<Map<String, Object>> graphIssues = new ArrayList<>();

        WorkflowDefinition def;
        try {
            def = definitionNormalizer.normalize(
                    objectMapper.readValue(entity.getDefinitionJson(), WorkflowDefinition.class));
        } catch (JsonProcessingException e) {
            return Map.of("valid", false, "errors", List.of("Invalid JSON: " + e.getMessage()), "warnings", List.of());
        }

        if (def.name() == null || def.name().isBlank()) {
            errors.add("Workflow name is required.");
        }

        if (def.triggers() == null || def.triggers().isEmpty()) {
            Map<String, Object> issue = Map.of("issue", "Workflow has no triggers.");
            triggerIssues.add(issue);
            warnings.add("Workflow has no triggers.");
        }

        if (def.actions() != null) {
            for (var entry : def.actions().entrySet()) {
                validateActionDefinitions(entry.getKey(), entry.getValue(), errors);
                if (entry.getValue() != null) {
                    for (ActionDef action : entry.getValue()) {
                        if (action instanceof ActionDef.NodeActionDef nodeAction) {
                            nodeIssues.addAll(validateNodeDefinitionStructure(entry.getKey(), nodeAction));
                        }
                    }
                }
            }
        }

        // Validate DAG structure
        if (def.actions() != null && def.triggers() != null) {
            DagValidator.DagResult dagResult = DagValidator.buildAndValidate(
                    def.actions(), def.edges(), def.triggers());
            if (!dagResult.isValid() && dagResult.error() != null) {
                errors.add(dagResult.error());
                graphIssues.add(Map.of("issue", dagResult.error()));
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
                    graphIssues.add(Map.of("issue", "Unknown edge source", "edge", edge));
                }
                if (!nodeIds.contains(edge.to())) {
                    errors.add("Edge references unknown target node: " + edge.to());
                    graphIssues.add(Map.of("issue", "Unknown edge target", "edge", edge));
                }
            }
        }

        if (def.pages() == null || def.pages().isEmpty()) {
            warnings.add("Workflow has no pages defined. No UI will be shown on load.");
            pageIssues.add(Map.of("issue", "Workflow has no pages defined."));
        } else {
            pageIssues.addAll(validatePageDefinitions(def.pages()));
        }

        boolean valid = errors.isEmpty();
        return Map.of(
                "valid", valid,
                "errors", errors,
                "warnings", warnings,
                "triggerIssues", triggerIssues,
                "nodeIssues", nodeIssues,
                "pageIssues", pageIssues,
                "bindingIssues", bindingIssues,
                "graphIssues", graphIssues
        );
    }

    private List<Map<String, Object>> validateNodeDefinitionStructure(String actionGroup,
                                                                      ActionDef.NodeActionDef action) {
        List<Map<String, Object>> issues = new ArrayList<>();
        if (action.nodeType() == null || action.nodeType().isBlank()) {
            issues.add(Map.of(
                    "actionGroup", actionGroup,
                    "nodeType", "",
                    "issue", "nodeType is required"
            ));
        }
        if (action.params() == null) {
            issues.add(Map.of(
                    "actionGroup", actionGroup,
                    "nodeType", action.nodeType(),
                    "issue", "params should be provided as an object"
            ));
        }
        return issues;
    }

    private List<Map<String, Object>> validatePageDefinitions(List<PageDef> pages) {
        List<Map<String, Object>> issues = new ArrayList<>();
        Set<String> pageIds = new LinkedHashSet<>();
        for (PageDef page : pages) {
            if (!pageIds.add(page.id())) {
                issues.add(Map.of("pageId", page.id(), "issue", "duplicate page id"));
            }
            if (page.sections() == null || page.sections().isEmpty()) {
                issues.add(Map.of("pageId", page.id(), "issue", "page has no sections"));
                continue;
            }
            Set<String> sectionIds = new LinkedHashSet<>();
            for (SectionBindDef section : page.sections()) {
                if (!sectionIds.add(section.id())) {
                    issues.add(Map.of(
                            "pageId", page.id(),
                            "sectionId", section.id(),
                            "issue", "duplicate section id"
                    ));
                }
                if (section.type() == null || section.type().isBlank()) {
                    issues.add(Map.of(
                            "pageId", page.id(),
                            "sectionId", section.id(),
                            "issue", "section.type is required"
                    ));
                }
            }
        }
        return issues;
    }

    private Map<String, Object> toExecutionRecordView(ExecutionRecordEntity record) {
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("id", record.getId());
        view.put("deviceId", record.getDeviceId());
        view.put("definitionId", record.getDefinitionId());
        view.put("definitionName", record.getDefinitionName());
        view.put("triggerId", record.getTriggerId());
        view.put("startedAt", record.getStartTime() != null ? record.getStartTime().toString() : null);
        view.put("endedAt", record.getEndTime() != null ? record.getEndTime().toString() : null);
        view.put("status", record.getStatus());
        view.put("failedNode", record.getFailedNode());
        view.put("failureReason", record.getErrorMessage());
        view.put("nodeResults", parseNodeResults(record.getNodeResultsJson()));
        return view;
    }

    private Object parseNodeResults(String json) {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        try {
            return objectMapper.readValue(json, Object.class);
        } catch (Exception e) {
            return json;
        }
    }

    private void validateActionDefinitions(String actionGroup, List<ActionDef> actions, List<String> errors) {
        if (actions == null) {
            return;
        }
        for (ActionDef action : actions) {
            if (action instanceof ActionDef.NodeActionDef nodeAction) {
                validateNodeActionDefinition(actionGroup, nodeAction, errors);
            } else if (action instanceof ActionDef.ConditionAction conditionAction) {
                validateActionDefinitions(actionGroup + ".then", conditionAction.thenActions(), errors);
                validateActionDefinitions(actionGroup + ".else", conditionAction.elseActions(), errors);
            } else if (action instanceof ActionDef.SequenceAction sequenceAction) {
                validateActionDefinitions(actionGroup + ".sequence", sequenceAction.steps(), errors);
            }
        }
    }

    private void validateNodeActionDefinition(String actionGroup, ActionDef.NodeActionDef action, List<String> errors) {
        if (action.nodeType() == null || action.nodeType().isBlank()) {
            errors.add("Action group '" + actionGroup + "' contains node action with empty nodeType.");
            return;
        }

        CapabilityInvocationValidator.ValidationResult validation =
                invocationValidator.validateNodeAction("", action.nodeType(), action.params());
        for (String error : validation.errors()) {
            errors.add("Action group '" + actionGroup + "': " + error);
        }
    }

    private void validateActionsForDevice(String deviceId, String actionGroup, List<ActionDef> actions,
                                          List<Map<String, Object>> issues) {
        if (actions == null) {
            return;
        }
        for (ActionDef action : actions) {
            if (action instanceof ActionDef.NodeActionDef nodeAction) {
                validateNodeActionForDevice(deviceId, actionGroup, nodeAction, issues);
            } else if (action instanceof ActionDef.ConditionAction conditionAction) {
                validateActionsForDevice(deviceId, actionGroup + ".then", conditionAction.thenActions(), issues);
                validateActionsForDevice(deviceId, actionGroup + ".else", conditionAction.elseActions(), issues);
            } else if (action instanceof ActionDef.SequenceAction sequenceAction) {
                validateActionsForDevice(deviceId, actionGroup + ".sequence", sequenceAction.steps(), issues);
            }
        }
    }

    private void validateNodeActionForDevice(String deviceId, String actionGroup,
                                             ActionDef.NodeActionDef action,
                                             List<Map<String, Object>> issues) {
        var node = nodeRegistry.resolve(deviceId, action.nodeType());
        if (node == null) {
            issues.add(Map.of(
                    "element", "action",
                    "actionType", "node",
                    "actionGroup", actionGroup,
                    "capability", action.nodeType(),
                    "issue", "Unknown node type: " + action.nodeType(),
                    "severity", "error"
            ));
            return;
        }

        CapabilityInvocationValidator.ValidationResult validation =
                invocationValidator.validateNodeAction(deviceId, action.nodeType(), action.params());
        for (String error : validation.errors()) {
            issues.add(Map.of(
                    "element", "action",
                    "actionType", "node",
                    "actionGroup", actionGroup,
                    "capability", action.nodeType(),
                    "issue", error,
                    "severity", "error"
            ));
        }
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
     * {@link TriggerDef.DeviceUiEventTrigger} entries, respecting
     * pageId / sectionId / nodeId filters.
     */
    public int fireEvent(String deviceId, EventPayload payload) {
        String prefix = deviceId + ":";
        String eventId = payload.eventId();
        if (eventId == null) return 0;

        String pageId = !payload.pageId().isEmpty() ? payload.pageId() : null;
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
                if (!(trigger instanceof TriggerDef.DeviceUiEventTrigger d)) continue;
                if (!matchesEventType(d.eventType(), eventId)) continue;
                if (!matchesFilter(d.pageId(), pageId)) continue;
                if (!matchesFilter(d.sectionId(), sectionId)) continue;
                if (!matchesFilter(d.nodeId(), nodeId)) continue;

                List<ActionDef> actions = def.actions().get(trigger.id());
                if (actions != null) {
                    executeTrigger(def, instance, trigger.id(), legacyPayload, env, payload);
                    fired++;
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

    private boolean matchesEventType(String triggerEventType, String eventId) {
        return triggerEventType != null && triggerEventType.equals(eventId);
    }

    // ── Internal helpers ──

    public boolean renderPage(String deviceId, WorkflowInstance instance, String pageId,
                              Map<String, Object> triggerPayload, Map<String, String> env) {
        return pageRuntimeService.renderPage(deviceId, instance, pageId, triggerPayload, env);
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
            Map<String, Object> executionView = Map.of();
            if (def.edges() != null && !def.edges().isEmpty()) {
                executionView = dagExecutor.executeDag(def, instance, triggerPayload, env, triggerId);
            } else {
                List<ActionDef> actions = def.actions() != null ? def.actions().get(triggerId) : null;
                if (actions != null) {
                    ActionExecutor.ExecutionReport report =
                            actionExecutor.execute(actions, instance, triggerPayload, env, eventPayload);
                    executionView = Map.of(
                            "mode", "sequential",
                            "executed", report.actionCount(),
                            "nodeResults", report.nodeResults(),
                            "failedNode", report.failedNode(),
                            "failureReason", report.failureReason()
                    );
                }
            }
            record.setStatus("SUCCESS");
            record.setEndTime(LocalDateTime.now());
            record.setFailedNode((String) executionView.get("failedNode"));
            Object nodeResults = executionView.get("nodeResults");
            if (nodeResults != null) {
                record.setNodeResultsJson(objectMapper.writeValueAsString(nodeResults));
            }
            if (executionView.get("failureReason") instanceof String failure && !failure.isBlank()) {
                record.setErrorMessage(failure);
            }
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

        // Collect all page/section bindings from newly loaded definition
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

}
