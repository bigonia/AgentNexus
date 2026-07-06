package com.zwbd.agentnexus.sdui.workflow;

import org.springframework.stereotype.Service;

import java.util.*;

/**
 * Resolves {@code {"$ref": "nodeId"}} references in node parameters.
 *
 * <p>The resolver matches a parameter's declared type (from {@link NodeTypeRegistry})
 * against the referenced node's output types, then extracts the bindable value
 * using the output's {@code extractPath}.</p>
 *
 * <p>Static values (strings, numbers, booleans) pass through unchanged.
 * {@code $ref} resolution is only performed at the top level of params
 * (nested {@code $ref} inside scene/patch config is not yet supported).</p>
 */
@Service
public class NodeWorkflowParameterResolver {

    private final NodeTypeRegistry typeRegistry;

    public NodeWorkflowParameterResolver(NodeTypeRegistry typeRegistry) {
        this.typeRegistry = typeRegistry;
    }

    // ── runtime resolution ───────────────────────────────────────────

    /**
     * Resolve all {@code $ref} objects in the given params.
     * Each top-level param entry is resolved individually so the parameter's
     * declared type can be matched against the referenced node's output types.
     */
    public Map<String, Object> resolveParams(Map<String, Object> params,
                                              String nodeType,
                                              Map<String, Object> context) {
        if (params == null || params.isEmpty()) return Map.of();
        Map<String, Object> resolved = new LinkedHashMap<>();
        for (var entry : params.entrySet()) {
            resolved.put(entry.getKey(),
                    resolveParamRef(entry.getKey(), entry.getValue(), nodeType, context));
        }
        return resolved;
    }

    /**
     * Resolve a single parameter value that may be a {@code $ref}.
     *
     * @return the resolved value, or empty string if resolution fails (lenient)
     */
    public Object resolveParamRef(String paramName, Object paramValue,
                                   String nodeType, Map<String, Object> context) {
        if (!isRef(paramValue)) return paramValue;

        String refNodeId = String.valueOf(((Map<?, ?>) paramValue).get("$ref"));
        NodeTypeRegistry.ParamDef paramDef = typeRegistry.getParam(nodeType, paramName);
        if (paramDef == null) return "";

        Map<String, Object> nodes = getMap(context, "nodes");
        Map<String, Object> refNode = getMap(nodes, refNodeId);
        if (refNode.isEmpty()) return "";

        String refNodeType = string(refNode.get("nodeType"));
        NodeTypeRegistry.OutputDef output = typeRegistry.findOutput(refNodeType, paramDef.type());
        if (output == null) return "";

        return navigate(refNode, output.extractPath());
    }

    // ── definition validation ────────────────────────────────────────

    /**
     * Validate all {@code $ref} references in a single node's params.
     *
     * @param params          raw node parameters
     * @param nodeType        the node's type (for parameter type lookup)
     * @param nodeId          the node's ID (for upstream reachability check)
     * @param allNodeIds      set of all node IDs in the workflow
     * @param upstreamByNode  nodeId → set of upstream node IDs
     * @return list of error messages, empty if valid
     */
    public List<String> validateReferences(Map<String, Object> params,
                                            String nodeType,
                                            String nodeId,
                                            Set<String> allNodeIds,
                                            Map<String, Set<String>> upstreamByNode) {
        List<String> errors = new ArrayList<>();
        collectRefErrors(params, nodeType, nodeId, allNodeIds, upstreamByNode, errors);
        return errors;
    }

    // ── internal ─────────────────────────────────────────────────────

    @SuppressWarnings("unchecked")
    private void collectRefErrors(Object value, String nodeType, String nodeId,
                                   Set<String> allNodeIds,
                                   Map<String, Set<String>> upstreamByNode,
                                   List<String> errors) {
        if (value instanceof Map<?, ?> map) {
            if (isRef(value)) {
                String refNodeId = String.valueOf(map.get("$ref"));
                if (refNodeId.isBlank()) {
                    errors.add("$ref value is required: node=" + nodeId);
                } else if (!allNodeIds.contains(refNodeId)) {
                    errors.add("$ref references unknown node: " + refNodeId + " from " + nodeId);
                } else {
                    Set<String> upstream = upstreamByNode.getOrDefault(nodeId, Set.of());
                    if (!upstream.isEmpty() && !upstream.contains(refNodeId)) {
                        errors.add("$ref target is not upstream (DAG reachable): "
                                + refNodeId + " from " + nodeId);
                    }
                }
                return;
            }
            for (var entry : map.entrySet()) {
                collectRefErrors(entry.getValue(), nodeType, nodeId, allNodeIds, upstreamByNode, errors);
            }
        } else if (value instanceof List<?> list) {
            for (Object item : list) {
                collectRefErrors(item, nodeType, nodeId, allNodeIds, upstreamByNode, errors);
            }
        }
    }

    private boolean isRef(Object value) {
        return value instanceof Map<?, ?> map
                && map.size() == 1
                && map.containsKey("$ref");
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> getMap(Map<String, Object> parent, String key) {
        Object value = parent != null ? parent.get(key) : null;
        return value instanceof Map<?, ?> map ? (Map<String, Object>) map : Map.of();
    }

    private Object navigate(Map<String, Object> root, List<String> path) {
        Object current = root;
        for (String segment : path) {
            if (current instanceof Map<?, ?> map) {
                current = map.get(segment);
            } else {
                return "";
            }
        }
        return current != null ? current : "";
    }

    private String string(Object value) {
        return value == null ? "" : String.valueOf(value);
    }
}
