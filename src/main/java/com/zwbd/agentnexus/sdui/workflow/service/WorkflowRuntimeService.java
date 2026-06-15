package com.zwbd.agentnexus.sdui.workflow.service;

import com.zwbd.agentnexus.sdui.DeviceSessionManager;
import com.zwbd.agentnexus.sdui.dto.SduiControlDispatchResult;
import com.zwbd.agentnexus.sdui.event.EventPayload;
import com.zwbd.agentnexus.sdui.model.SduiDeviceCommand;
import com.zwbd.agentnexus.sdui.repo.SduiDeviceCommandRepository;
import com.zwbd.agentnexus.sdui.section.SectionData;
import com.zwbd.agentnexus.sdui.section.SectionDataCodec;
import com.zwbd.agentnexus.sdui.section.SectionEntry;
import com.zwbd.agentnexus.sdui.section.SectionLayout;
import com.zwbd.agentnexus.sdui.section.SectionOrchestrationService;
import com.zwbd.agentnexus.sdui.section.SectionPatch;
import com.zwbd.agentnexus.sdui.section.SectionScene;
import com.zwbd.agentnexus.sdui.section.SectionType;
import com.zwbd.agentnexus.sdui.service.CommandService;
import com.zwbd.agentnexus.sdui.service.PlatformCapabilityRuntimeService;
import com.zwbd.agentnexus.sdui.workflow.model.WorkflowBinding;
import com.zwbd.agentnexus.sdui.workflow.model.WorkflowDefinition;
import com.zwbd.agentnexus.sdui.workflow.model.WorkflowRun;
import com.zwbd.agentnexus.sdui.workflow.model.WorkflowRunStep;
import com.zwbd.agentnexus.sdui.workflow.repo.WorkflowBindingRepository;
import com.zwbd.agentnexus.sdui.workflow.repo.WorkflowDefinitionRepository;
import com.zwbd.agentnexus.sdui.workflow.repo.WorkflowRunRepository;
import com.zwbd.agentnexus.sdui.workflow.repo.WorkflowRunStepRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Slf4j
@Service
@RequiredArgsConstructor
public class WorkflowRuntimeService {

    private final WorkflowDefinitionRepository definitionRepository;
    private final WorkflowBindingRepository bindingRepository;
    private final WorkflowRunRepository runRepository;
    private final WorkflowRunStepRepository stepRepository;
    private final CommandService commandService;
    private final PlatformCapabilityRuntimeService platformRuntimeService;
    private final SectionOrchestrationService sectionOrchestrationService;
    private final SectionDataCodec sectionDataCodec;
    private final DeviceSessionManager sessionManager;
    private final WorkflowExpressionService expressionService;

    @Transactional
    public List<WorkflowRun> handleDeviceEvent(EventPayload payload) {
        if (payload == null || payload.deviceId() == null) {
            return List.of();
        }
        List<WorkflowRun> runs = new ArrayList<>();
        for (WorkflowBinding binding : bindingRepository.findByDeviceIdAndEnabledTrueAndBindingStatus(payload.deviceId(), "ACTIVE")) {
            definitionRepository.findById(binding.getWorkflowId())
                    .filter(def -> "ACTIVE".equals(def.getStatus()))
                    .filter(def -> matchesDeviceEvent(def, payload))
                    .ifPresent(def -> runs.add(run(def, binding, "device.ui.event", payload.toLegacyMap())));
        }
        return runs;
    }

    @Transactional
    public List<WorkflowRun> handleCommandEvent(CommandLifecycleEvent event) {
        if (event == null || event.deviceId() == null) {
            return List.of();
        }
        List<WorkflowRun> runs = new ArrayList<>();
        for (WorkflowBinding binding : bindingRepository.findByDeviceIdAndEnabledTrueAndBindingStatus(event.deviceId(), "ACTIVE")) {
            definitionRepository.findById(binding.getWorkflowId())
                    .filter(def -> "ACTIVE".equals(def.getStatus()))
                    .filter(def -> matchesCommandEvent(def, event))
                    .ifPresent(def -> runs.add(run(def, binding, "command.lifecycle", event.payload())));
        }
        return runs;
    }

    @Transactional
    public WorkflowRun triggerManual(String workflowId, String deviceId, Map<String, Object> payload) {
        WorkflowDefinition definition = definitionRepository.findById(workflowId)
                .orElseThrow(() -> new IllegalArgumentException("workflow not found"));
        WorkflowBinding binding = bindingRepository.findFirstByWorkflowIdAndDeviceId(workflowId, deviceId).orElse(null);
        return run(definition, binding, "manual", payload != null ? payload : Map.of());
    }

    private boolean matchesDeviceEvent(WorkflowDefinition definition, EventPayload payload) {
        for (WorkflowDag.Node node : WorkflowDag.nodes(definition.getDag())) {
            if (!"trigger".equals(node.type()) || !"device.ui.event".equals(node.kind())) {
                continue;
            }
            String eventId = WorkflowDag.string(node.config().get("eventId"));
            String nodeId = WorkflowDag.string(node.config().get("nodeId"));
            String sectionId = WorkflowDag.string(node.config().get("sectionId"));
            boolean eventMatches = eventId.isBlank() || eventId.equals(payload.eventId());
            boolean nodeMatches = nodeId.isBlank() || nodeId.equals(payload.nodeId());
            boolean sectionMatches = sectionId.isBlank() || sectionId.equals(payload.sectionId());
            if (eventMatches && nodeMatches && sectionMatches) {
                return true;
            }
        }
        return false;
    }

    private boolean matchesCommandEvent(WorkflowDefinition definition, CommandLifecycleEvent event) {
        for (WorkflowDag.Node node : WorkflowDag.nodes(definition.getDag())) {
            if (!"trigger".equals(node.type()) || !"command.lifecycle".equals(node.kind())) {
                continue;
            }
            String eventId = WorkflowDag.string(node.config().get("eventId"));
            String command = WorkflowDag.string(node.config().get("command"));
            String status = WorkflowDag.string(node.config().get("status"));
            boolean eventMatches = eventId.isBlank() || eventId.equals(event.eventId());
            boolean commandMatches = command.isBlank() || command.equals(event.command());
            boolean statusMatches = status.isBlank() || status.equals(event.status());
            if (eventMatches && commandMatches && statusMatches) {
                return true;
            }
        }
        return false;
    }

    private WorkflowRun run(WorkflowDefinition definition, WorkflowBinding binding, String triggerType, Map<String, Object> input) {
        long started = System.currentTimeMillis();
        WorkflowRun run = new WorkflowRun();
        run.setWorkflowId(definition.getId());
        run.setBindingId(binding != null ? binding.getId() : null);
        run.setDeviceId(binding != null ? binding.getDeviceId() : WorkflowDag.string(input.getOrDefault("deviceId", "")));
        run.setTriggerType(triggerType);
        run.setInputPayload(new LinkedHashMap<>(input));
        run = runRepository.save(run);

        Map<String, Object> context = new LinkedHashMap<>();
        context.put("event", input);
        context.put("vars", new LinkedHashMap<String, Object>());
        context.put("steps", new LinkedHashMap<String, Object>());

        try {
            executeDag(definition.getDag(), run, context, triggerType);
            run.setStatus("SUCCEEDED");
            run.setOutputPayload(context);
        } catch (Exception e) {
            log.warn("Workflow run failed workflow={} run={}: {}", definition.getId(), run.getId(), e.getMessage());
            run.setStatus("FAILED");
            run.setErrorMessage(e.getMessage());
            run.setOutputPayload(context);
        } finally {
            run.setDurationMs(System.currentTimeMillis() - started);
            if (binding != null) {
                binding.setLastRunAt(LocalDateTime.now());
                bindingRepository.save(binding);
            }
        }
        return runRepository.save(run);
    }

    private void executeDag(Map<String, Object> dag, WorkflowRun run, Map<String, Object> context, String triggerType) {
        List<WorkflowDag.Node> nodes = WorkflowDag.nodes(dag);
        Map<String, WorkflowDag.Node> byId = new LinkedHashMap<>();
        Map<String, List<WorkflowDag.Edge>> outgoing = new HashMap<>();
        Map<String, Integer> indegree = new HashMap<>();
        for (WorkflowDag.Node node : nodes) {
            byId.put(node.id(), node);
            indegree.put(node.id(), 0);
        }
        for (WorkflowDag.Edge edge : WorkflowDag.edges(dag)) {
            outgoing.computeIfAbsent(edge.from(), ignored -> new ArrayList<>()).add(edge);
            indegree.computeIfPresent(edge.to(), (ignored, degree) -> degree + 1);
        }

        ArrayDeque<String> queue = new ArrayDeque<>();
        nodes.stream()
                .filter(node -> "trigger".equals(node.type()))
                .filter(node -> triggerType.equals(node.kind()))
                .map(WorkflowDag.Node::id)
                .forEach(queue::add);
        if (queue.isEmpty()) {
            indegree.forEach((nodeId, degree) -> {
                if (degree == 0) queue.add(nodeId);
            });
        }
        while (!queue.isEmpty()) {
            WorkflowDag.Node node = byId.get(queue.removeFirst());
            if (node == null) continue;
            StepResult result = executeNode(run, node, context);
            if (result.terminate()) {
                return;
            }
            for (WorkflowDag.Edge edge : outgoing.getOrDefault(node.id(), List.of())) {
                if (!edge.condition().isBlank() && !expressionService.evaluateCondition(edge.condition(), context)) {
                    continue;
                }
                queue.add(edge.to());
            }
        }
    }

    @SuppressWarnings("unchecked")
    private StepResult executeNode(WorkflowRun run, WorkflowDag.Node node, Map<String, Object> context) {
        long started = System.currentTimeMillis();
        WorkflowRunStep step = new WorkflowRunStep();
        step.setRunId(run.getId());
        step.setNodeId(node.id());
        step.setNodeType(node.type() + "." + node.kind());
        step.setInputPayload(new LinkedHashMap<>(node.config()));
        step = stepRepository.save(step);
        try {
            Object output = switch (node.type()) {
                case "trigger", "input" -> Map.of("passed", true);
                case "control" -> executeControl(node, context);
                case "output" -> executeOutput(run.getDeviceId(), node, context);
                default -> throw new IllegalArgumentException("unsupported node type: " + node.type());
            };
            Map<String, Object> outputMap = output instanceof Map<?, ?> map ? WorkflowDag.normalize(map) : Map.of("value", output);
            ((Map<String, Object>) context.get("steps")).put(node.id(), outputMap);
            step.setOutputPayload(outputMap);
            step.setStatus("SUCCEEDED");
            return new StepResult(Boolean.TRUE.equals(outputMap.get("terminate")));
        } catch (Exception e) {
            step.setStatus("FAILED");
            step.setErrorMessage(e.getMessage());
            throw e;
        } finally {
            step.setDurationMs(System.currentTimeMillis() - started);
            stepRepository.save(step);
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> executeControl(WorkflowDag.Node node, Map<String, Object> context) {
        return switch (node.kind()) {
            case "condition" -> Map.of("matched", expressionService.evaluateCondition(WorkflowDag.string(node.config().get("expression")), context));
            case "set_variable" -> {
                String name = WorkflowDag.string(node.config().get("name"));
                if (name.isBlank()) throw new IllegalArgumentException("variable name is required");
                Object value = expressionService.resolve(node.config().get("value"), context);
                ((Map<String, Object>) context.get("vars")).put(name, value);
                yield Map.of("name", name, "value", value != null ? value : "");
            }
            case "terminate" -> Map.of("terminate", true);
            default -> throw new IllegalArgumentException("unsupported control kind: " + node.kind());
        };
    }

    private Map<String, Object> executeOutput(String deviceId, WorkflowDag.Node node, Map<String, Object> context) {
        if (!sessionManager.isDeviceOnline(deviceId)) {
            throw new IllegalStateException("device is offline");
        }
        Map<String, Object> config = resolvedConfig(node, context);
        return switch (node.kind()) {
            case "device.command" -> executeDeviceCommand(deviceId, config);
            case "platform.capability" -> executePlatformCapability(deviceId, config);
            case "section.scene" -> executeSectionScene(deviceId, config);
            case "section.patch" -> executeSectionPatch(deviceId, config);
            default -> throw new IllegalArgumentException("unsupported output kind: " + node.kind());
        };
    }

    private Map<String, Object> executeDeviceCommand(String deviceId, Map<String, Object> config) {
        String command = WorkflowDag.string(config.get("command"));
        Object params = config.getOrDefault("params", config.get("value"));
        SduiControlDispatchResult result = commandService.dispatchCommand(deviceId, command, params);
        return Map.of("deviceId", deviceId, "command", command, "cmdId", result.cmdId(), "sent", result.sent(), "status", result.status());
    }

    private Map<String, Object> executePlatformCapability(String deviceId, Map<String, Object> config) {
        String command = WorkflowDag.string(config.get("command"));
        @SuppressWarnings("unchecked")
        Map<String, Object> params = config.get("params") instanceof Map<?, ?> map ? WorkflowDag.normalize(map) : Map.of();
        Map<String, Object> result = platformRuntimeService.execute(deviceId, command, params);
        if ("ERROR".equals(result.get("status"))) {
            throw new IllegalStateException(String.valueOf(result.getOrDefault("error", "platform capability error")));
        }
        return result;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> executeSectionScene(String deviceId, Map<String, Object> config) {
        String pageId = WorkflowDag.string(config.getOrDefault("pageId", "workflow_page"));
        String layout = WorkflowDag.string(config.getOrDefault("layout", "vertical_scroll"));
        boolean autoScroll = Boolean.TRUE.equals(config.get("autoScroll"));
        int autoScrollMs = config.get("autoScrollMs") instanceof Number n ? n.intValue() : 0;
        List<SectionEntry> entries = new ArrayList<>();
        Object rawSections = config.get("sections");
        if (!(rawSections instanceof List<?> sections) || sections.isEmpty()) {
            throw new IllegalArgumentException("sections is required");
        }
        for (Object raw : sections) {
            if (!(raw instanceof Map<?, ?> map)) continue;
            Map<String, Object> section = WorkflowDag.normalize(map);
            String sectionId = WorkflowDag.string(section.get("sectionId"));
            String sectionType = WorkflowDag.string(section.getOrDefault("sectionType", section.get("type")));
            Map<String, Object> fields = section.get("fields") instanceof Map<?, ?> f ? WorkflowDag.normalize(f) : Map.of();
            SectionType type = SectionType.fromWireName(sectionType);
            SectionData data = sectionDataCodec.buildSectionData(sectionType, fields, sectionId);
            if (type == null || data == null) {
                throw new IllegalArgumentException("invalid section: " + sectionId);
            }
            entries.add(new SectionEntry(type, sectionId, data));
        }
        boolean sent = sectionOrchestrationService.sendScene(deviceId, new SectionScene(pageId, SectionLayout.fromWireName(layout), autoScroll, autoScrollMs, entries));
        return Map.of("deviceId", deviceId, "pageId", pageId, "sent", sent, "sections", entries.size());
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> executeSectionPatch(String deviceId, Map<String, Object> config) {
        String pageId = WorkflowDag.string(config.getOrDefault("pageId", "workflow_page"));
        Object rawPatches = config.get("patches");
        if (!(rawPatches instanceof List<?> patches) || patches.isEmpty()) {
            throw new IllegalArgumentException("patches is required");
        }
        List<SectionPatch.PatchEntry> entries = new ArrayList<>();
        for (Object raw : patches) {
            if (!(raw instanceof Map<?, ?> map)) continue;
            Map<String, Object> patch = WorkflowDag.normalize(map);
            String op = WorkflowDag.string(patch.getOrDefault("op", "update"));
            String sectionId = WorkflowDag.string(patch.get("sectionId"));
            String sectionType = WorkflowDag.string(patch.getOrDefault("sectionType", patch.get("type")));
            if (sectionType.isBlank() && !"remove".equals(op)) {
                sectionType = sectionOrchestrationService.findSectionType(deviceId, pageId, sectionId);
            }
            Map<String, Object> fields = patch.get("fields") instanceof Map<?, ?> f ? WorkflowDag.normalize(f) : Map.of();
            SectionData data = fields.isEmpty() ? null : sectionDataCodec.buildSectionData(sectionType, fields, sectionId);
            entries.add(new SectionPatch.PatchEntry(sectionId, op, sectionType.isBlank() ? null : sectionType, data));
        }
        boolean sent = sectionOrchestrationService.sendPatch(deviceId, new SectionPatch(pageId, entries));
        return Map.of("deviceId", deviceId, "pageId", pageId, "sent", sent, "patches", entries.size());
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> resolvedConfig(WorkflowDag.Node node, Map<String, Object> context) {
        Object resolved = expressionService.resolve(node.config(), context);
        return resolved instanceof Map<?, ?> map ? WorkflowDag.normalize(map) : Map.of();
    }

    private record StepResult(boolean terminate) {}
}
