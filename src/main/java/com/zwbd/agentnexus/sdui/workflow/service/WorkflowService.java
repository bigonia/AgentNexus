package com.zwbd.agentnexus.sdui.workflow.service;

import com.zwbd.agentnexus.sdui.workflow.model.WorkflowBinding;
import com.zwbd.agentnexus.sdui.workflow.model.WorkflowDefinition;
import com.zwbd.agentnexus.sdui.workflow.model.WorkflowRun;
import com.zwbd.agentnexus.sdui.workflow.model.WorkflowRunStep;
import com.zwbd.agentnexus.sdui.workflow.repo.WorkflowBindingRepository;
import com.zwbd.agentnexus.sdui.workflow.repo.WorkflowDefinitionRepository;
import com.zwbd.agentnexus.sdui.workflow.repo.WorkflowRunRepository;
import com.zwbd.agentnexus.sdui.workflow.repo.WorkflowRunStepRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
@RequiredArgsConstructor
public class WorkflowService {

    private final WorkflowDefinitionRepository definitionRepository;
    private final WorkflowBindingRepository bindingRepository;
    private final WorkflowRunRepository runRepository;
    private final WorkflowRunStepRepository stepRepository;
    private final WorkflowValidationService validationService;
    private final WorkflowRuntimeService runtimeService;

    public List<Map<String, Object>> list() {
        return definitionRepository.findAllByOrderByUpdatedAtDesc().stream().map(this::toDefinitionMap).toList();
    }

    public Map<String, Object> get(String id) {
        WorkflowDefinition definition = definition(id);
        Map<String, Object> result = toDefinitionMap(definition);
        result.put("bindings", bindingRepository.findByWorkflowId(id).stream().map(this::toBindingMap).toList());
        return result;
    }

    @Transactional
    public Map<String, Object> create(Map<String, Object> request) {
        WorkflowDefinition definition = new WorkflowDefinition();
        applyDefinition(definition, request, false);
        return toDefinitionMap(definitionRepository.save(definition));
    }

    @Transactional
    public Map<String, Object> update(String id, Map<String, Object> request) {
        WorkflowDefinition definition = definition(id);
        applyDefinition(definition, request, true);
        definition.setVersion(definition.getVersion() + 1);
        return toDefinitionMap(definitionRepository.save(definition));
    }

    @Transactional
    public void delete(String id) {
        definitionRepository.delete(definition(id));
    }

    @Transactional
    public Map<String, Object> bind(String workflowId, Map<String, Object> request) {
        definition(workflowId);
        String deviceId = string(request.get("deviceId"));
        if (deviceId.isBlank()) {
            throw new IllegalArgumentException("deviceId is required");
        }
        WorkflowBinding binding = bindingRepository.findFirstByWorkflowIdAndDeviceId(workflowId, deviceId)
                .orElseGet(WorkflowBinding::new);
        binding.setWorkflowId(workflowId);
        binding.setDeviceId(deviceId);
        binding.setEnabled(!Boolean.FALSE.equals(request.get("enabled")));
        binding.setBindingStatus(string(request.getOrDefault("bindingStatus", "ACTIVE")));
        return toBindingMap(bindingRepository.save(binding));
    }

    @Transactional
    public Map<String, Object> setEnabled(String workflowId, boolean enabled) {
        WorkflowDefinition definition = definition(workflowId);
        definition.setStatus(enabled ? "ACTIVE" : "DISABLED");
        definitionRepository.save(definition);
        for (WorkflowBinding binding : bindingRepository.findByWorkflowId(workflowId)) {
            binding.setEnabled(enabled);
            bindingRepository.save(binding);
        }
        return get(workflowId);
    }

    @Transactional
    public Map<String, Object> trigger(String workflowId, Map<String, Object> request) {
        String deviceId = string(request.get("deviceId"));
        if (deviceId.isBlank()) {
            throw new IllegalArgumentException("deviceId is required");
        }
        @SuppressWarnings("unchecked")
        Map<String, Object> payload = request.get("payload") instanceof Map<?, ?> map
                ? WorkflowDag.normalize(map) : new LinkedHashMap<>();
        payload.putIfAbsent("deviceId", deviceId);
        WorkflowRun run = runtimeService.triggerManual(workflowId, deviceId, payload);
        return runDetail(run.getId());
    }

    public List<Map<String, Object>> runs(String workflowId, int limit) {
        int size = Math.max(1, Math.min(limit, 100));
        return runRepository.findByWorkflowIdOrderByCreatedAtDesc(workflowId, PageRequest.of(0, size))
                .stream().map(this::toRunMap).toList();
    }

    public Map<String, Object> runDetail(String runId) {
        WorkflowRun run = runRepository.findById(runId).orElseThrow(() -> new IllegalArgumentException("run not found"));
        Map<String, Object> result = toRunMap(run);
        result.put("steps", stepRepository.findByRunIdOrderByCreatedAtAsc(runId).stream().map(this::toStepMap).toList());
        return result;
    }

    public Map<String, Object> validate(String deviceId, Map<String, Object> request) {
        @SuppressWarnings("unchecked")
        Map<String, Object> dag = request.get("dag") instanceof Map<?, ?> map ? WorkflowDag.normalize(map) : request;
        return validationService.validate(deviceId, dag);
    }

    @SuppressWarnings("unchecked")
    private void applyDefinition(WorkflowDefinition definition, Map<String, Object> request, boolean partial) {
        String name = string(request.get("name"));
        if (!partial || !name.isBlank()) {
            if (name.isBlank()) throw new IllegalArgumentException("name is required");
            definition.setName(name);
        }
        if (request.containsKey("description")) {
            definition.setDescription(string(request.get("description")));
        }
        if (request.containsKey("status")) {
            definition.setStatus(string(request.get("status")));
        }
        if (request.containsKey("dag")) {
            if (!(request.get("dag") instanceof Map<?, ?> map)) throw new IllegalArgumentException("dag must be an object");
            definition.setDag(WorkflowDag.normalize(map));
        } else if (!partial) {
            throw new IllegalArgumentException("dag is required");
        }
        if (request.containsKey("editorModel")) {
            definition.setEditorModel(request.get("editorModel") instanceof Map<?, ?> map ? WorkflowDag.normalize(map) : Map.of());
        }
    }

    private WorkflowDefinition definition(String id) {
        return definitionRepository.findById(id).orElseThrow(() -> new IllegalArgumentException("workflow not found"));
    }

    private Map<String, Object> toDefinitionMap(WorkflowDefinition definition) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("id", definition.getId());
        map.put("name", definition.getName());
        map.put("description", definition.getDescription());
        map.put("status", definition.getStatus());
        map.put("version", definition.getVersion());
        map.put("dag", definition.getDag());
        map.put("editorModel", definition.getEditorModel());
        map.put("createdAt", definition.getCreatedAt());
        map.put("updatedAt", definition.getUpdatedAt());
        return map;
    }

    private Map<String, Object> toBindingMap(WorkflowBinding binding) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("id", binding.getId());
        map.put("workflowId", binding.getWorkflowId());
        map.put("deviceId", binding.getDeviceId());
        map.put("enabled", binding.isEnabled());
        map.put("bindingStatus", binding.getBindingStatus());
        map.put("lastRunAt", binding.getLastRunAt());
        return map;
    }

    private Map<String, Object> toRunMap(WorkflowRun run) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("id", run.getId());
        map.put("workflowId", run.getWorkflowId());
        map.put("bindingId", run.getBindingId());
        map.put("deviceId", run.getDeviceId());
        map.put("triggerType", run.getTriggerType());
        map.put("status", run.getStatus());
        map.put("inputPayload", run.getInputPayload());
        map.put("outputPayload", run.getOutputPayload());
        map.put("errorMessage", run.getErrorMessage());
        map.put("durationMs", run.getDurationMs());
        map.put("createdAt", run.getCreatedAt());
        return map;
    }

    private Map<String, Object> toStepMap(WorkflowRunStep step) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("id", step.getId());
        map.put("runId", step.getRunId());
        map.put("nodeId", step.getNodeId());
        map.put("nodeType", step.getNodeType());
        map.put("status", step.getStatus());
        map.put("inputPayload", step.getInputPayload());
        map.put("outputPayload", step.getOutputPayload());
        map.put("errorMessage", step.getErrorMessage());
        map.put("durationMs", step.getDurationMs());
        map.put("createdAt", step.getCreatedAt());
        return map;
    }

    private String string(Object value) {
        return value == null ? "" : String.valueOf(value);
    }
}
