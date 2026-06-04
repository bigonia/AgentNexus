package com.zwbd.agentnexus.sdui.workflow;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Persistent store for workflow execution state during suspend/resume cycles.
 * Saves full variable snapshots so execution can resume after server restart.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ExecutionStateStore {

    private final ExecutionStateRepository repo;
    private final WorkflowInstanceRepository instanceRepo;
    private final ObjectMapper objectMapper;

    @Transactional
    public ExecutionStateEntity saveSuspendPoint(String deviceId, String definitionId, String triggerId,
                                                  String currentNode, String nextNode,
                                                  Map<String, Object> variables,
                                                  Map<String, Object> triggerPayload,
                                                  String resumeEvent, long timeoutMs) {
        ExecutionStateEntity entity = new ExecutionStateEntity();
        entity.setDeviceId(deviceId);
        entity.setDefinitionId(definitionId);
        entity.setTriggerId(triggerId);
        entity.setCurrentNode(currentNode);
        entity.setNextNode(nextNode);
        entity.setResumeEvent(resumeEvent);
        entity.setTimeoutAt(LocalDateTime.now().plusSeconds(timeoutMs / 1000));
        entity.setStatus("SUSPENDED");
        try {
            entity.setVariablesJson(objectMapper.writeValueAsString(variables));
            if (triggerPayload != null) {
                entity.setTriggerPayloadJson(objectMapper.writeValueAsString(triggerPayload));
            }
        } catch (JsonProcessingException e) {
            log.error("Failed to serialize variables for suspend point", e);
            throw new RuntimeException("Failed to save execution state", e);
        }
        entity = repo.save(entity);
        log.info("Execution suspended: deviceId={} workflow={} trigger={} node={} resumeEvent={}",
                deviceId, definitionId, triggerId, currentNode, resumeEvent);
        return entity;
    }

    @Transactional
    public Optional<ExecutionStateEntity> findSuspendPoint(String deviceId, String definitionId,
                                                            String triggerId) {
        return repo.findByDeviceIdAndDefinitionIdAndTriggerIdAndStatus(
                deviceId, definitionId, triggerId, "SUSPENDED");
    }

    @Transactional
    public List<ExecutionStateEntity> findSuspendedForDevice(String deviceId) {
        return repo.findByDeviceIdAndStatus(deviceId, "SUSPENDED");
    }

    @Transactional
    public List<ExecutionStateEntity> findAllSuspended() {
        return repo.findByStatus("SUSPENDED");
    }

    @Transactional
    public List<ExecutionStateEntity> findTimedOut() {
        List<ExecutionStateEntity> all = repo.findByStatus("SUSPENDED");
        LocalDateTime now = LocalDateTime.now();
        return all.stream()
                .filter(e -> e.getTimeoutAt() != null && e.getTimeoutAt().isBefore(now))
                .toList();
    }

    @Transactional
    public void markCompleted(String id) {
        repo.findById(id).ifPresent(e -> {
            e.setStatus("COMPLETED");
            repo.save(e);
        });
    }

    @Transactional
    public void markTimedOut(String id) {
        repo.findById(id).ifPresent(e -> {
            e.setStatus("TIMED_OUT");
            repo.save(e);
        });
    }

    @Transactional
    public void markError(String id, String error) {
        repo.findById(id).ifPresent(e -> {
            e.setStatus("ERROR");
            repo.save(e);
            log.error("Execution state {} marked ERROR", id);
        });
    }

    @Transactional
    public void saveVariablesSnapshot(String deviceId, String definitionId, String variablesJson) {
        instanceRepo.findByDeviceIdAndDefinitionId(deviceId, definitionId).ifPresent(ie -> {
            ie.setVariablesJson(variablesJson);
            instanceRepo.save(ie);
        });
    }

    @Transactional
    public void clearForWorkflow(String deviceId, String definitionId) {
        repo.deleteByDeviceIdAndDefinitionId(deviceId, definitionId);
    }

    public Map<String, Object> deserializeVariables(String json) {
        if (json == null) return new java.util.LinkedHashMap<>();
        try {
            return objectMapper.readValue(json, new TypeReference<Map<String, Object>>() {});
        } catch (JsonProcessingException e) {
            log.error("Failed to deserialize variables", e);
            return new java.util.LinkedHashMap<>();
        }
    }

    public Map<String, Object> deserializeTriggerPayload(String json) {
        if (json == null) return Map.of();
        try {
            return objectMapper.readValue(json, new TypeReference<Map<String, Object>>() {});
        } catch (JsonProcessingException e) {
            log.error("Failed to deserialize trigger payload", e);
            return Map.of();
        }
    }
}
