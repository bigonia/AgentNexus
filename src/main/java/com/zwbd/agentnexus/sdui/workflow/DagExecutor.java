package com.zwbd.agentnexus.sdui.workflow;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Executes workflow actions as a DAG using topological ordering.
 * When edges are defined in the workflow, execution follows the graph
 * rather than the legacy sequential action list.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DagExecutor {

    private final ActionExecutor actionExecutor;
    private final ExecutorService parallelPool = Executors.newCachedThreadPool();

    /**
     * Execute a DAG starting from trigger entry points.
     * If no edges are defined, falls back to simple sequential execution.
     */
    public Map<String, Object> executeDag(WorkflowDefinition def, WorkflowInstance instance,
                                           Map<String, Object> triggerPayload, Map<String, String> env,
                                           String triggerId) {
        if (def.edges() == null || def.edges().isEmpty()) {
            // Legacy mode: execute actions sequentially by triggerId
            List<ActionDef> actions = def.actions() != null ? def.actions().get(triggerId) : null;
            if (actions != null) {
                ActionExecutor.ExecutionReport report = actionExecutor.execute(actions, instance, triggerPayload, env);
                return Map.of(
                        "mode", "sequential",
                        "executed", actions.size(),
                        "nodeResults", report.nodeResults(),
                        "failedNode", report.failedNode(),
                        "failureReason", report.failureReason()
                );
            }
            return Map.of("mode", "sequential", "executed", actions != null ? actions.size() : 0);
        }

        // DAG mode
        DagValidator.DagResult dag = DagValidator.buildAndValidate(
                def.actions(), def.edges(), def.triggers());
        if (!dag.isValid()) {
            log.error("DAG validation failed: {}", dag.error());
            return Map.of("mode", "dag", "status", "invalid", "error", dag.error());
        }

        List<List<String>> levels = DagValidator.computeExecutionLevels(
                dag.topologicalOrder(), dag.adjacency());

        Map<String, Map<String, Object>> nodeOutputs = new ConcurrentHashMap<>();
        List<Map<String, Object>> nodeResults = new CopyOnWriteArrayList<>();
        AtomicInteger executed = new AtomicInteger(0);

        // Execute level by level, with nodes in each level running in parallel
        for (List<String> level : levels) {
            if (level.size() == 1) {
                String nodeId = level.get(0);
                Map<String, Object> output = executeNode(nodeId, def, instance,
                        triggerPayload, env, nodeOutputs, nodeResults);
                if (output != null) {
                    nodeOutputs.put(nodeId, output);
                    executed.incrementAndGet();
                }
            } else {
                // Parallel execution for nodes at the same level
                List<Future<Map.Entry<String, Map<String, Object>>>> futures = new ArrayList<>();
                for (String nodeId : level) {
                    futures.add(parallelPool.submit(() -> {
                        Map<String, Object> output = executeNode(nodeId, def, instance,
                                triggerPayload, env, nodeOutputs, nodeResults);
                        return output != null ? Map.entry(nodeId, output) : null;
                    }));
                }
                for (Future<Map.Entry<String, Map<String, Object>>> future : futures) {
                    try {
                        Map.Entry<String, Map<String, Object>> result = future.get(30, TimeUnit.SECONDS);
                        if (result != null) {
                            nodeOutputs.put(result.getKey(), result.getValue());
                            executed.incrementAndGet();
                        }
                    } catch (Exception e) {
                        log.error("Parallel node execution failed: {}", e.getMessage());
                    }
                }
            }
        }

        log.info("DAG execution complete: {} / {} nodes executed", executed.get(), dag.topologicalOrder().size());
        return Map.of("mode", "dag", "executed", executed.get(),
                "total", dag.topologicalOrder().size(),
                "order", dag.topologicalOrder(),
                "nodeResults", nodeResults);
    }

    private Map<String, Object> executeNode(String nodeId, WorkflowDefinition def,
                                             WorkflowInstance instance,
                                             Map<String, Object> triggerPayload,
                                             Map<String, String> env,
                                             Map<String, Map<String, Object>> nodeOutputs,
                                             List<Map<String, Object>> nodeResults) {
        List<ActionDef> actions = def.actions() != null ? def.actions().get(nodeId) : null;
        if (actions == null || actions.isEmpty()) {
            // Could be a trigger-only node (entry point)
            return Map.of();
        }

        // Resolve $<nodeId>.<field> references in trigger payload
        Map<String, Object> enrichedPayload = new LinkedHashMap<>(triggerPayload);
        for (var entry : nodeOutputs.entrySet()) {
            String prefix = "$" + entry.getKey() + ".";
            for (var outEntry : entry.getValue().entrySet()) {
                enrichedPayload.put(prefix + outEntry.getKey(), outEntry.getValue());
            }
        }

        ActionExecutor.ExecutionReport report = actionExecutor.execute(actions, instance, enrichedPayload, env);
        nodeResults.add(Map.of(
                "nodeId", nodeId,
                "status", report.failedNode() == null ? "COMPLETED" : "ERROR",
                "results", report.nodeResults(),
                "failedNode", report.failedNode(),
                "failureReason", report.failureReason()
        ));
        return Map.of("status", report.failedNode() == null ? "completed" : "error");
    }
}
