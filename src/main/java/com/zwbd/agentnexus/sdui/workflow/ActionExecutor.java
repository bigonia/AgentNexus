package com.zwbd.agentnexus.sdui.workflow;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.zwbd.agentnexus.sdui.event.EventPayload;
import com.zwbd.agentnexus.sdui.service.SduiCapabilityService;
import com.zwbd.agentnexus.sdui.workflow.node.CapabilityNode;
import com.zwbd.agentnexus.sdui.workflow.node.CapabilityNodeRegistry;
import com.zwbd.agentnexus.sdui.workflow.node.NodeContext;
import com.zwbd.agentnexus.sdui.workflow.node.NodeResult;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

import java.time.Duration;
import java.util.*;

@Slf4j
@Component
@RequiredArgsConstructor
public class ActionExecutor {

    public record ExecutionReport(
            int actionCount,
            List<Map<String, Object>> nodeResults,
            String failedNode,
            String failureReason
    ) {}

    private final ObjectMapper objectMapper;
    private final ExecutionStateStore stateStore;
    private final CapabilityNodeRegistry nodeRegistry;
    private final WorkflowPageRuntimeService pageRuntimeService;
    private final SduiCapabilityService capabilityService;

    private final RestTemplate restTemplate = createRestTemplate();

    private static RestTemplate createRestTemplate() {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(Duration.ofSeconds(5));
        factory.setReadTimeout(Duration.ofSeconds(10));
        return new RestTemplate(factory);
    }

    public ExecutionReport execute(List<ActionDef> actions, WorkflowInstance instance,
                        Map<String, Object> triggerPayload, Map<String, String> env) {
        return execute(actions, instance, triggerPayload, env, null);
    }

    public ExecutionReport execute(List<ActionDef> actions, WorkflowInstance instance,
                        Map<String, Object> triggerPayload, Map<String, String> env,
                        EventPayload eventPayload) {
        boolean variablesChanged = false;
        List<Map<String, Object>> nodeResults = new ArrayList<>();
        String failedNode = null;
        String failureReason = null;
        for (ActionDef action : actions) {
            Set<String> changedVars = new LinkedHashSet<>();
            try {
                Map<String, Object> result = dispatch(action, instance, triggerPayload, env, changedVars, eventPayload);
                if (result != null) {
                    nodeResults.add(result);
                    if ("ERROR".equals(result.get("status")) && failedNode == null) {
                        failedNode = String.valueOf(result.getOrDefault("nodeType", result.getOrDefault("actionType", "unknown")));
                        failureReason = String.valueOf(result.getOrDefault("error", "execution_failed"));
                    }
                }
            } catch (Exception e) {
                log.error("Action execution failed for device {}: action type={}, error={}",
                        instance.deviceId(), action.getClass().getSimpleName(), e.getMessage());
                if (failedNode == null) {
                    failedNode = action.getClass().getSimpleName();
                    failureReason = e.getMessage();
                }
            }
            if (!changedVars.isEmpty()) {
                variablesChanged = true;
                if (!instance.watcher().isEmpty()) {
                    autoRebind(instance, changedVars, triggerPayload, env);
                }
            }
        }
        if (variablesChanged) {
            persistVariables(instance);
        }
        return new ExecutionReport(actions != null ? actions.size() : 0, nodeResults, failedNode, failureReason);
    }

    private void persistVariables(WorkflowInstance instance) {
        try {
            String varsJson = objectMapper.writeValueAsString(instance.variablesAsMap());
            stateStore.saveVariablesSnapshot(instance.deviceId(), instance.workflowId(), varsJson);
        } catch (Exception e) {
            log.warn("Failed to persist variables for device {}: {}", instance.deviceId(), e.getMessage());
        }
    }

    private Map<String, Object> dispatch(ActionDef action, WorkflowInstance instance,
                          Map<String, Object> triggerPayload, Map<String, String> env,
                          Set<String> changedVars) {
        return dispatch(action, instance, triggerPayload, env, changedVars, null);
    }

    private Map<String, Object> dispatch(ActionDef action, WorkflowInstance instance,
                          Map<String, Object> triggerPayload, Map<String, String> env,
                          Set<String> changedVars, EventPayload eventPayload) {
        if (action instanceof ActionDef.FetchAction a) {
            String url = resolveString(a.url(), triggerPayload, instance.variablesAsMap(), env);
            log.info("Fetch: {} -> {}", a.save(), url);
            try {
                String response = restTemplate.getForObject(url, String.class);
                Object parsed = objectMapper.readValue(response, Object.class);
                instance.putVariable(a.save(), parsed);
                changedVars.add(a.save());
                log.info("Fetch saved to $data.{}: {} bytes", a.save(),
                        response != null ? response.length() : 0);
                return Map.of("actionType", "fetch", "status", "COMPLETED", "save", a.save());
            } catch (Exception e) {
                log.error("Fetch failed for {}: {}", url, e.getMessage());
                return Map.of("actionType", "fetch", "status", "ERROR", "error", e.getMessage());
            }
        } else if (action instanceof ActionDef.SetVariableAction a) {
            String varName = a.variable();
            Object resolved = VariableResolver.resolveExpression(a.value(),
                    instance.variablesAsMap(), triggerPayload, env);
            instance.putVariable(varName, resolved);
            changedVars.add(varName);
            log.info("SetVariable: $data.{} = {}", varName, resolved);
            return Map.of("actionType", "set_variable", "status", "COMPLETED", "variable", varName);
        } else if (action instanceof ActionDef.ConditionAction a) {
            Object cond = VariableResolver.resolveExpression(a.variable(),
                    instance.variablesAsMap(), triggerPayload, env);
            boolean match = evaluateCondition(cond, a.operator(), a.value(),
                    instance.variablesAsMap(), triggerPayload, env);
            log.info("Condition: {} {} {} → {}", a.variable(), a.operator(), a.value(), match);
            List<ActionDef> branch = match && a.thenActions() != null
                    ? a.thenActions()
                    : a.elseActions() != null ? a.elseActions() : List.of();
            for (ActionDef sub : branch) {
                dispatch(sub, instance, triggerPayload, env, changedVars, eventPayload);
            }
            return Map.of("actionType", "condition", "status", "COMPLETED", "matched", match);
        } else if (action instanceof ActionDef.SequenceAction a) {
            for (ActionDef step : a.steps()) {
                dispatch(step, instance, triggerPayload, env, changedVars, eventPayload);
            }
            return Map.of("actionType", "sequence", "status", "COMPLETED", "steps", a.steps().size());
        } else if (action instanceof ActionDef.NodeActionDef a) {
            return dispatchNode(a, instance, triggerPayload, env, changedVars, eventPayload);
        }
        return null;
    }

    /**
     * Dispatch a unified NodeActionDef through the CapabilityNode plugin system.
     * Resolves the node type from CapabilityNodeRegistry, resolves variable
     * references in params, builds a NodeContext, and executes the node.
     */
    private Map<String, Object> dispatchNode(ActionDef.NodeActionDef a, WorkflowInstance instance,
                              Map<String, Object> triggerPayload, Map<String, String> env,
                              Set<String> changedVars, EventPayload eventPayload) {
        String nodeType = a.nodeType();
        CapabilityNode node = nodeRegistry.resolve(instance.deviceId(), nodeType);
        if (node == null) {
            log.error("Unknown node type '{}' for device {}", nodeType, instance.deviceId());
            return Map.of("nodeType", nodeType, "status", "ERROR", "error", "Unknown node type");
        }

        // Resolve variable references in params
        Map<String, Object> resolvedInputs = new LinkedHashMap<>();
        if (a.params() != null) {
            for (var entry : a.params().entrySet()) {
                Object resolved = VariableResolver.resolveValue(
                        entry.getValue(), instance.variablesAsMap(), triggerPayload, env);
                resolvedInputs.put(entry.getKey(), resolved);
            }
        }

        // Build execution context (capability snapshot may be null if device not connected)
        var capsOpt = capabilityService.getSectionCapability(instance.deviceId());
        Map<String, Object> capabilitySnapshot = capsOpt.map(caps -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("sectionEnabled", true);
            m.put("supportedTypes", caps.sectionTypes());
            m.put("supportedLayouts", caps.layouts());
            m.put("limits", Map.of());
            return m;
        }).orElse(Map.of());

        NodeContext ctx = NodeContext.builder(instance.deviceId(), instance)
                .resolvedInputs(resolvedInputs)
                .triggerPayload(triggerPayload)
                .env(env)
                .capabilitySnapshot(capabilitySnapshot)
                .eventPayload(eventPayload)
                .build();

        NodeResult result = node.execute(ctx);

        // Apply variable changes from node output
        if (result.outputs() != null) {
            // Changed variables are specified by name in changedVariables set;
            // their values are in the outputs map.
            if (result.changedVariables() != null) {
                for (String varName : result.changedVariables()) {
                    if (result.outputs().containsKey(varName)) {
                        instance.putVariable(varName, result.outputs().get(varName));
                        changedVars.add(varName);
                    }
                }
            }
            // Also handle $save convention
            if (result.outputs().containsKey("$save")) {
                Object saveVal = result.outputs().get("$save");
                if (saveVal instanceof Map<?, ?> sm) {
                    for (var entry : sm.entrySet()) {
                        String key = entry.getKey().toString();
                        instance.putVariable(key, entry.getValue());
                        changedVars.add(key);
                    }
                }
            }
        }

        if (result.status() == NodeResult.Status.ERROR) {
            log.error("Node {} failed for device {}: {}", nodeType,
                    instance.deviceId(), result.error());
        }

        log.info("NodeAction {} executed for device {}: status={}",
                nodeType, instance.deviceId(), result.status());
        Map<String, Object> nodeResult = new LinkedHashMap<>();
        nodeResult.put("nodeType", nodeType);
        nodeResult.put("status", result.status().name());
        if (result.error() != null) {
            nodeResult.put("error", result.error());
        }
        if (result.outputs() != null && !result.outputs().isEmpty()) {
            nodeResult.put("outputs", result.outputs());
        }
        return nodeResult;
    }

    // ── Auto-rebinding: when variables change, re-resolve dependent sections ──

    private void autoRebind(WorkflowInstance instance, Set<String> changedVars,
                            Map<String, Object> triggerPayload, Map<String, String> env) {
        VariableWatcher watcher = instance.watcher();
        Set<String> allSections = new LinkedHashSet<>();
        for (String var : changedVars) {
            allSections.addAll(watcher.getAffectedSections(var));
        }
        if (allSections.isEmpty()) return;

        for (String sectionId : allSections) {
            VariableWatcher.SectionBinding binding = watcher.getSectionBinding(sectionId);
            if (binding == null) continue;
            boolean patched = pageRuntimeService.patchBoundSection(
                    instance.deviceId(), instance, binding, triggerPayload, env);
            if (patched) {
                log.debug("Auto-rebound section {} for device {}", sectionId, instance.deviceId());
            }
        }
    }

    // ── Condition evaluation ──

    private boolean evaluateCondition(Object left, String operator, String rightExpr,
                                       Map<String, Object> data,
                                       Map<String, Object> triggerPayload,
                                       Map<String, String> env) {
        Object right = VariableResolver.resolveExpression(rightExpr, data, triggerPayload, env);
        if (left == null && right == null && "eq".equals(operator)) return true;
        if (left == null && right == null && "neq".equals(operator)) return false;
        if (left == null) return "neq".equals(operator);
        if (right == null) return "neq".equals(operator);

        if ("isEmpty".equals(operator)) {
            if (left instanceof String s) return s.isEmpty();
            if (left instanceof List<?> l) return l.isEmpty();
            if (left instanceof Map<?, ?> m) return m.isEmpty();
            return false;
        }

        if (left instanceof Number nl && right instanceof Number nr) {
            double l = nl.doubleValue();
            double r = nr.doubleValue();
            return switch (operator) {
                case "eq" -> l == r;
                case "neq" -> l != r;
                case "gt" -> l > r;
                case "gte" -> l >= r;
                case "lt" -> l < r;
                case "lte" -> l <= r;
                default -> false;
            };
        }

        String ls = left.toString();
        String rs = right.toString();
        return switch (operator) {
            case "eq" -> ls.equals(rs);
            case "neq" -> !ls.equals(rs);
            case "contains" -> ls.contains(rs);
            default -> false;
        };
    }

    private String resolveString(String expr, Map<String, Object> trigger,
                                  Map<String, Object> data, Map<String, String> env) {
        if (expr == null) return null;
        Object resolved = VariableResolver.resolveExpression(expr, data, trigger, env);
        return resolved != null ? resolved.toString() : expr;
    }
}
