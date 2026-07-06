package com.zwbd.agentnexus.sdui.workflow;

import com.zwbd.agentnexus.sdui.artifact.SduiArtifactService;
import com.zwbd.agentnexus.sdui.workflow.entity.NodeWorkflowRunEntity;
import com.zwbd.agentnexus.sdui.workflow.model.NodeWorkflowNode;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Manages the runtime context shared across nodes during a single workflow run.
 *
 * <p>The context is a flat map with a single {@code nodes} key whose value is
 * {@code nodeId → raw executor result (+ node metadata)}. {@code $ref} resolution
 * navigates these raw results using output {@code extractPath} definitions from
 * {@link NodeTypeRegistry}.</p>
 */
@Service
public class NodeWorkflowRunContextService {

    private final SduiArtifactService artifactService;

    public NodeWorkflowRunContextService(SduiArtifactService artifactService) {
        this.artifactService = artifactService;
    }

    /** Create a fresh context for a new run. */
    public Map<String, Object> create(NodeWorkflowRunEntity run) {
        Map<String, Object> context = new LinkedHashMap<>();
        context.put("nodes", new LinkedHashMap<String, Object>());
        return context;
    }

    /**
     * Store a node's execution result into the context.
     *
     * <p>Stores the full raw result + metadata (nodeType, deviceId, slotId, status)
     * under {@code context.nodes.<nodeId>}. Also persists any artifact to the run.</p>
     */
    @SuppressWarnings("unchecked")
    public void putNodeResult(Map<String, Object> context,
                              NodeWorkflowRunEntity run,
                              NodeWorkflowNode node,
                              String deviceId,
                              Map<String, Object> result) {
        Map<String, Object> entry = new LinkedHashMap<>();
        entry.put("nodeId", node.nodeId());
        entry.put("nodeType", node.nodeType());
        entry.put("slotId", node.slotId());
        entry.put("deviceId", deviceId);
        entry.putAll(result == null ? Map.of() : result);

        ((Map<String, Object>) context.get("nodes")).put(node.nodeId(), entry);

        // Persist artifact to run if present
        Object artifact = entry.get("artifact");
        if (artifact instanceof Map<?, ?> artifactMap) {
            Map<String, Object> value = new LinkedHashMap<>();
            for (var e : artifactMap.entrySet()) {
                value.put(String.valueOf(e.getKey()), e.getValue());
            }
            artifactService.attachToRun(run.getWorkflowId(), run.getId(), node.nodeId(), value);
        }
    }
}
