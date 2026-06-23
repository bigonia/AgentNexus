package com.zwbd.agentnexus.sdui.workflow;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.zwbd.agentnexus.sdui.ui.WorkflowUiContextService;
import com.zwbd.agentnexus.sdui.workflow.entity.NodeWorkflowDefinitionEntity;
import com.zwbd.agentnexus.sdui.workflow.model.NodeWorkflowDefinition;
import com.zwbd.agentnexus.sdui.workflow.repo.NodeWorkflowDefinitionRepository;
import com.zwbd.agentnexus.sdui.workflow.repo.NodeWorkflowDeploymentRepository;
import jakarta.transaction.Transactional;
import org.springframework.stereotype.Service;

import java.util.*;

@Service
public class NodeWorkflowService {

    private final NodeWorkflowDefinitionRepository workflowRepository;
    private final NodeWorkflowDeploymentRepository deploymentRepository;
    private final WorkflowUiContextService uiContextService;
    private final ObjectMapper objectMapper;

    public NodeWorkflowService(NodeWorkflowDefinitionRepository workflowRepository,
                               NodeWorkflowDeploymentRepository deploymentRepository,
                               WorkflowUiContextService uiContextService,
                               ObjectMapper objectMapper) {
        this.workflowRepository = workflowRepository;
        this.deploymentRepository = deploymentRepository;
        this.uiContextService = uiContextService;
        this.objectMapper = objectMapper;
    }

    @Transactional
    public Map<String, Object> create(NodeWorkflowDefinition raw) {
        NodeWorkflowDefinition workflow = NodeWorkflowSupport.normalize(raw);
        requireValid(workflow);
        NodeWorkflowDefinitionEntity entity = new NodeWorkflowDefinitionEntity();
        entity.setName(workflow.name().isBlank() ? "Untitled workflow" : workflow.name());
        entity.setDefinition(NodeWorkflowSupport.toDefinitionMap(objectMapper, workflow));
        return toMap(workflowRepository.save(entity));
    }

    public List<Map<String, Object>> list() {
        return workflowRepository.findAllByOrderByUpdatedAtDesc().stream().map(this::toMap).toList();
    }

    public Map<String, Object> get(String workflowId) {
        return toMap(requireEntity(workflowId));
    }

    @Transactional
    public Map<String, Object> update(String workflowId, NodeWorkflowDefinition raw) {
        NodeWorkflowDefinition workflow = NodeWorkflowSupport.normalize(raw);
        requireValid(workflow);
        NodeWorkflowDefinitionEntity entity = requireEntity(workflowId);
        entity.setName(workflow.name().isBlank() ? entity.getName() : workflow.name());
        entity.setVersion(entity.getVersion() + 1);
        entity.setDefinition(NodeWorkflowSupport.toDefinitionMap(objectMapper, workflow));
        return toMap(workflowRepository.save(entity));
    }

    @Transactional
    public Map<String, Object> delete(String workflowId) {
        if (!deploymentRepository.findByWorkflowIdAndStatusOrderByDeployedAtDesc(workflowId, "active").isEmpty()) {
            throw new IllegalArgumentException("cannot delete workflow with active deployments");
        }
        NodeWorkflowDefinitionEntity entity = requireEntity(workflowId);
        workflowRepository.delete(entity);
        return Map.of("deleted", true, "workflowId", workflowId);
    }

    public Map<String, Object> validate(NodeWorkflowDefinition raw) {
        List<String> errors = new ArrayList<>(NodeWorkflowSupport.validateDefinition(raw));
        if (errors.isEmpty()) {
            errors.addAll(uiContextService.validateUiNodes(NodeWorkflowSupport.normalize(raw)));
        }
        return Map.of("valid", errors.isEmpty(), "errors", errors);
    }

    NodeWorkflowDefinition workflow(String workflowId) {
        NodeWorkflowDefinitionEntity entity = requireEntity(workflowId);
        return NodeWorkflowSupport.fromDefinitionMap(objectMapper, entity.getId(), entity.getName(), entity.getDefinition());
    }

    NodeWorkflowDefinitionEntity requireEntity(String workflowId) {
        return workflowRepository.findById(workflowId)
                .orElseThrow(() -> new IllegalArgumentException("workflow not found: " + workflowId));
    }

    Map<String, Object> toMap(NodeWorkflowDefinitionEntity entity) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("workflowId", entity.getId());
        data.put("id", entity.getId());
        data.put("name", entity.getName());
        data.put("status", entity.getStatus());
        data.put("version", entity.getVersion());
        data.put("source", entity.getSource());
        data.put("definition", entity.getDefinition());
        data.put("createdAt", entity.getCreatedAt() != null ? entity.getCreatedAt().toString() : null);
        data.put("updatedAt", entity.getUpdatedAt() != null ? entity.getUpdatedAt().toString() : null);
        return data;
    }

    private void requireValid(NodeWorkflowDefinition workflow) {
        List<String> errors = new ArrayList<>(NodeWorkflowSupport.validateDefinition(workflow));
        if (errors.isEmpty()) {
            errors.addAll(uiContextService.validateUiNodes(workflow));
        }
        if (!errors.isEmpty()) {
            throw new IllegalArgumentException(String.join("; ", errors));
        }
    }
}
