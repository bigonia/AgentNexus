package com.zwbd.agentnexus.sdui.workflow;

import com.zwbd.agentnexus.sdui.workflow.entity.NodeWorkflowDeploymentEntity;
import com.zwbd.agentnexus.sdui.workflow.entity.NodeWorkflowRunEntity;
import com.zwbd.agentnexus.sdui.workflow.model.NodeWorkflowDefinition;
import com.zwbd.agentnexus.sdui.workflow.model.NodeWorkflowNode;
import com.zwbd.agentnexus.sdui.artifact.SduiArtifactService;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.Map;

@Service
public class NodeWorkflowRunContextService {

    private final SduiArtifactService artifactService;

    public NodeWorkflowRunContextService(SduiArtifactService artifactService) {
        this.artifactService = artifactService;
    }

    public Map<String, Object> create(NodeWorkflowRunEntity run,
                                      NodeWorkflowDeploymentEntity deployment,
                                      NodeWorkflowDefinition workflow,
                                      NodeWorkflowNode trigger,
                                      Map<String, Object> event) {
        Map<String, Object> context = new LinkedHashMap<>();
        context.put("event", new LinkedHashMap<>(event));
        context.put("slots", slotContext(deployment));
        context.put("nodes", new LinkedHashMap<String, Object>());
        context.put("artifacts", new LinkedHashMap<String, Object>());
        context.put("run", Map.of(
                "runId", run.getId(),
                "workflowId", workflow.id(),
                "deploymentId", deployment.getId(),
                "triggerNodeId", trigger.nodeId()
        ));
        putTriggerResult(context, trigger, event);
        return context;
    }

    @SuppressWarnings("unchecked")
    public void putNodeResult(Map<String, Object> context,
                              NodeWorkflowRunEntity run,
                              NodeWorkflowNode node,
                              String deviceId,
                              Map<String, Object> result) {
        Map<String, Object> normalized = new LinkedHashMap<>();
        normalized.put("nodeId", node.nodeId());
        normalized.put("nodeType", node.nodeType());
        normalized.put("slotId", node.slotId());
        normalized.put("deviceId", deviceId);
        normalized.putAll(result == null ? Map.of() : result);

        ((Map<String, Object>) context.get("nodes")).put(node.nodeId(), normalized);
        Object artifact = normalized.get("artifact");
        if (artifact instanceof Map<?, ?> artifactMap) {
            Map<String, Object> value = new LinkedHashMap<>();
            for (var entry : artifactMap.entrySet()) {
                value.put(String.valueOf(entry.getKey()), entry.getValue());
            }
            ((Map<String, Object>) context.get("artifacts")).put(node.nodeId(), value);
            artifactService.attachToRun(run.getWorkflowId(), run.getId(), node.nodeId(), value);
        }
    }

    private Map<String, Object> slotContext(NodeWorkflowDeploymentEntity deployment) {
        Map<String, Object> slots = new LinkedHashMap<>();
        for (var entry : NodeWorkflowSupport.stringMap(deployment.getSlotBindings()).entrySet()) {
            slots.put(entry.getKey(), Map.of(
                    "slotId", entry.getKey(),
                    "deviceId", entry.getValue()
            ));
        }
        return slots;
    }

    @SuppressWarnings("unchecked")
    private void putTriggerResult(Map<String, Object> context, NodeWorkflowNode trigger, Map<String, Object> event) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("nodeId", trigger.nodeId());
        result.put("nodeType", trigger.nodeType());
        result.put("slotId", trigger.slotId());
        result.put("event", event);
        result.put("deviceId", event.get("deviceId"));
        result.put("eventId", event.get("eventId"));
        result.put("inputNodeId", event.get("nodeId"));
        ((Map<String, Object>) context.get("nodes")).put(trigger.nodeId(), result);
    }
}
