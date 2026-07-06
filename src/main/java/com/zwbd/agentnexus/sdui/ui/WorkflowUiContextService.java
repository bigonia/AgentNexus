package com.zwbd.agentnexus.sdui.ui;

import com.zwbd.agentnexus.sdui.section.*;
import com.zwbd.agentnexus.sdui.ui.repo.WorkflowUiContextRepository;
import com.zwbd.agentnexus.sdui.workflow.model.NodeWorkflowDefinition;
import com.zwbd.agentnexus.sdui.workflow.entity.NodeWorkflowDeploymentEntity;
import jakarta.transaction.Transactional;
import org.springframework.stereotype.Service;

import java.util.*;

@Service
public class WorkflowUiContextService {

    private final WorkflowUiContextRepository repository;
    private final SduiUiTemplateService templateService;
    private final SectionDataCodec sectionDataCodec;
    private final SectionOrchestrationService sectionOrchestrationService;
    private final DevicePrimaryUiService primaryUiService;

    public WorkflowUiContextService(WorkflowUiContextRepository repository,
                                    SduiUiTemplateService templateService,
                                    SectionDataCodec sectionDataCodec,
                                    SectionOrchestrationService sectionOrchestrationService,
                                    DevicePrimaryUiService primaryUiService) {
        this.repository = repository;
        this.templateService = templateService;
        this.sectionDataCodec = sectionDataCodec;
        this.sectionOrchestrationService = sectionOrchestrationService;
        this.primaryUiService = primaryUiService;
    }

    @Transactional
    public List<Map<String, Object>> initialize(NodeWorkflowDefinition workflow,
                                                NodeWorkflowDeploymentEntity deployment) {
        List<Map<String, Object>> configs = uiTemplateConfigs(workflow.uiTemplates());
        if (configs.isEmpty()) return List.of();

        Map<String, String> bindings = stringMap(deployment.getSlotBindings());
        List<Map<String, Object>> result = new ArrayList<>();
        for (Map<String, Object> config : configs) {
            String slotId = string(config.get("slotId"));
            String templateKey = string(config.get("templateKey"));
            String deviceId = bindings.get(slotId);
            if (deviceId == null || deviceId.isBlank()) {
                throw new IllegalArgumentException("ui template slot is not bound: " + slotId);
            }
            SduiUiTemplateEntity template = templateService.requireByKey(templateKey);
            templateService.validateDeviceSupportsTemplate(deviceId, template.getDefinition());

            Map<String, Object> variables = templateService.variableValues(template.getDefinition(), SduiUiTemplateService.map(config.get("variables")));
            SectionScene scene = templateService.toScene(template, variables);
            boolean sent = sectionOrchestrationService.sendScene(deviceId, scene);

            WorkflowUiContextEntity context = repository
                    .findByDeploymentIdAndSlotIdAndTemplateKey(deployment.getId(), slotId, templateKey)
                    .orElseGet(WorkflowUiContextEntity::new);
            context.setWorkflowId(workflow.id());
            context.setDeploymentId(deployment.getId());
            context.setSlotId(slotId);
            context.setTemplateKey(templateKey);
            context.setDeviceId(deviceId);
            context.setActivePageId(scene.pageId());
            context.setContext(contextMap(template, scene, variables));
            repository.save(context);

            Map<String, Object> entry = toMap(context);
            entry.put("sent", sent);
            result.add(entry);
        }
        return result;
    }

    public List<String> validateTemplateBindings(NodeWorkflowDefinition workflow, Map<String, String> bindings) {
        List<String> errors = new ArrayList<>();
        for (Map<String, Object> config : uiTemplateConfigs(workflow.uiTemplates())) {
            String slotId = string(config.get("slotId"));
            String templateKey = string(config.get("templateKey"));
            if (slotId.isBlank()) errors.add("uiTemplates slotId is required");
            if (templateKey.isBlank()) errors.add("uiTemplates templateKey is required for slot " + slotId);
            String deviceId = bindings.get(slotId);
            if (deviceId == null || deviceId.isBlank()) {
                errors.add("ui template slot is not bound: " + slotId);
                continue;
            }
            try {
                SduiUiTemplateEntity template = templateService.requireByKey(templateKey);
                templateService.validateDeviceSupportsTemplate(deviceId, template.getDefinition());
            } catch (IllegalArgumentException e) {
                errors.add(e.getMessage());
            }
        }
        return errors;
    }

    public List<String> validateUiNodes(NodeWorkflowDefinition workflow) {
        List<String> errors = new ArrayList<>();
        Set<String> keys = new LinkedHashSet<>();
        Map<String, Set<String>> variablesBySlotTemplate = new LinkedHashMap<>();
        for (Map<String, Object> config : uiTemplateConfigs(workflow.uiTemplates())) {
            String slotId = string(config.get("slotId"));
            String templateKey = string(config.get("templateKey"));
            if (!slotId.isBlank() && !templateKey.isBlank()) {
                keys.add(slotId + "::" + templateKey);
                try {
                    SduiUiTemplateEntity template = templateService.requireByKey(templateKey);
                    Set<String> variables = new LinkedHashSet<>();
                    for (Map<String, Object> variable : SduiUiTemplateService.listOfMaps(template.getDefinition().get("variables"))) {
                        variables.add(string(variable.get("variableKey")));
                    }
                    variablesBySlotTemplate.put(slotId + "::" + templateKey, variables);
                } catch (IllegalArgumentException ignored) {
                    // Deployment validation reports missing templates with device context.
                }
            }
        }
        workflow.nodes().stream()
                .filter(node -> "ui.update".equals(node.nodeType()))
                .filter(node -> node.params().containsKey("variableKey"))
                .forEach(node -> {
                    String slotId = string(node.params().getOrDefault("slotId", node.slotId()));
                    String templateKey = string(node.params().get("templateKey"));
                    String variableKey = string(node.params().get("variableKey"));
                    if (templateKey.isBlank()) {
                        errors.add("ui.update node requires templateKey: " + node.nodeId());
                        return;
                    }
                    String key = slotId + "::" + templateKey;
                    if (!keys.contains(key)) {
                        errors.add("ui.update node references unbound ui template: " + node.nodeId());
                        return;
                    }
                    Set<String> variables = variablesBySlotTemplate.getOrDefault(key, Set.of());
                    if (!variables.isEmpty() && !variables.contains(variableKey)) {
                        errors.add("ui.update node references unknown variableKey " + variableKey + ": " + node.nodeId());
                    }
                });
        return errors;
    }

    @Transactional
    public Map<String, Object> updateVariable(String workflowId,
                                              String deploymentId,
                                              String deviceId,
                                              Map<String, Object> params) {
        String slotId = string(params.get("slotId"));
        String templateKey = string(params.get("templateKey"));
        String variableKey = string(params.get("variableKey"));
        Object value = params.get("value");
        if (slotId.isBlank()) throw new IllegalArgumentException("ui.update slotId is required");
        if (variableKey.isBlank()) throw new IllegalArgumentException("ui.update variableKey is required");

        WorkflowUiContextEntity context = resolveContext(deploymentId, slotId, templateKey);
        if (!workflowId.equals(context.getWorkflowId())) {
            throw new IllegalArgumentException("ui context does not belong to workflow: " + workflowId);
        }
        if (!deviceId.equals(context.getDeviceId())) {
            throw new IllegalArgumentException("ui context device mismatch: " + deviceId);
        }

        Map<String, Object> ctx = new LinkedHashMap<>(context.getContext());
        Map<String, Object> variables = new LinkedHashMap<>(SduiUiTemplateService.map(ctx.get("variables")));
        Map<String, Object> variableDef = SduiUiTemplateService.listOfMaps(ctx.get("variableDefs")).stream()
                .filter(item -> variableKey.equals(string(item.get("variableKey"))))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("ui variable not found: " + variableKey));
        variables.put(variableKey, value);
        ctx.put("variables", variables);

        String pageId = string(ctx.getOrDefault("activePageId", context.getActivePageId()));
        String sectionId = string(variableDef.get("sectionId"));
        String field = string(variableDef.get("field"));
        Map<String, Object> pages = new LinkedHashMap<>(SduiUiTemplateService.map(ctx.get("pages")));
        Map<String, Object> page = new LinkedHashMap<>(SduiUiTemplateService.map(pages.get(pageId)));
        Map<String, Object> sections = new LinkedHashMap<>(SduiUiTemplateService.map(page.get("sections")));
        Map<String, Object> section = new LinkedHashMap<>(SduiUiTemplateService.map(sections.get(sectionId)));
        String sectionType = string(section.get("sectionType"));
        Map<String, Object> fields = new LinkedHashMap<>(SduiUiTemplateService.map(section.get("fields")));
        SduiUiTemplateService.deepSet(fields, field, value);
        section.put("fields", fields);
        sections.put(sectionId, section);
        page.put("sections", sections);
        pages.put(pageId, page);
        ctx.put("pages", pages);

        SectionData data = sectionDataCodec.buildSectionData(sectionType, fields, sectionId);
        SectionPatch patch = new SectionPatch(pageId, List.of(
                new SectionPatch.PatchEntry(sectionId, "update", sectionType, data)
        ));
        context.setContext(ctx);
        context = repository.save(context);
        Map<String, Object> presentation = primaryUiService.presentUpdatedContext(context, patch);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("deviceId", context.getDeviceId());
        result.put("nodeType", "ui.update");
        result.put("operation", "variable_update");
        result.put("slotId", slotId);
        result.put("templateKey", context.getTemplateKey());
        result.put("variableKey", variableKey);
        result.put("value", value);
        result.put("pageId", pageId);
        result.put("sectionId", sectionId);
        result.put("field", field);
        result.put("patch", patchToMap(patch));
        result.put("contextId", context.getId());
        result.put("presentation", presentation);
        result.put("sent", Boolean.TRUE.equals(presentation.get("sent")));
        result.put("status", presentation.getOrDefault("status", "send_failed"));
        return result;
    }

    public List<Map<String, Object>> list(String workflowId, String deploymentId) {
        return repository.findByWorkflowIdAndDeploymentIdOrderByUpdatedAtDesc(workflowId, deploymentId).stream()
                .map(this::toMap)
                .toList();
    }

    public Map<String, Object> toMap(WorkflowUiContextEntity entity) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("contextId", entity.getId());
        data.put("workflowId", entity.getWorkflowId());
        data.put("deploymentId", entity.getDeploymentId());
        data.put("slotId", entity.getSlotId());
        data.put("templateKey", entity.getTemplateKey());
        data.put("deviceId", entity.getDeviceId());
        data.put("activePageId", entity.getActivePageId());
        data.put("context", entity.getContext());
        data.put("createdAt", entity.getCreatedAt() != null ? entity.getCreatedAt().toString() : null);
        data.put("updatedAt", entity.getUpdatedAt() != null ? entity.getUpdatedAt().toString() : null);
        return data;
    }

    private WorkflowUiContextEntity resolveContext(String deploymentId, String slotId, String templateKey) {
        if (!templateKey.isBlank()) {
            return repository.findByDeploymentIdAndSlotIdAndTemplateKey(deploymentId, slotId, templateKey)
                    .orElseThrow(() -> new IllegalArgumentException("ui context not found for slot/template: " + slotId + "/" + templateKey));
        }
        List<WorkflowUiContextEntity> contexts = repository.findByDeploymentIdAndSlotId(deploymentId, slotId);
        if (contexts.isEmpty()) throw new IllegalArgumentException("ui context not found for slot: " + slotId);
        if (contexts.size() > 1) throw new IllegalArgumentException("templateKey is required when slot has multiple ui contexts: " + slotId);
        return contexts.get(0);
    }

    private Map<String, Object> contextMap(SduiUiTemplateEntity template, SectionScene scene, Map<String, Object> variables) {
        Map<String, Object> pages = new LinkedHashMap<>();
        Map<String, Object> page = new LinkedHashMap<>();
        page.put("pageId", scene.pageId());
        page.put("layout", scene.layout().wireName());
        Map<String, Object> sections = new LinkedHashMap<>();
        for (SectionEntry entry : scene.sections()) {
            Map<String, Object> section = new LinkedHashMap<>();
            section.put("sectionId", entry.sectionId());
            section.put("sectionType", entry.type());
            section.put("fields", sectionDataCodec.toFieldMap(entry.data()));
            sections.put(entry.sectionId(), section);
        }
        page.put("sections", sections);
        pages.put(scene.pageId(), page);

        Map<String, Object> context = new LinkedHashMap<>();
        context.put("templateId", template.getId());
        context.put("templateKey", template.getTemplateKey());
        context.put("activePageId", scene.pageId());
        context.put("variables", new LinkedHashMap<>(variables));
        context.put("variableDefs", template.getDefinition().getOrDefault("variables", List.of()));
        context.put("pages", pages);
        return context;
    }

    private Map<String, Object> patchToMap(SectionPatch patch) {
        List<Map<String, Object>> patches = new ArrayList<>();
        for (SectionPatch.PatchEntry entry : patch.patches()) {
            patches.add(new LinkedHashMap<>(Map.of(
                    "sectionId", entry.sectionId(),
                    "op", entry.op(),
                    "sectionType", entry.type(),
                    "fields", sectionDataCodec.toFieldMap(entry.data())
            )));
        }
        return Map.of("pageId", patch.pageId(), "patches", patches);
    }

    private static List<Map<String, Object>> uiTemplateConfigs(List<Map<String, Object>> raw) {
        if (raw == null) return List.of();
        List<Map<String, Object>> result = new ArrayList<>();
        raw.forEach(item -> result.add(new LinkedHashMap<>(item)));
        return result;
    }

    private static Map<String, String> stringMap(Object raw) {
        Map<String, String> result = new LinkedHashMap<>();
        if (raw instanceof Map<?, ?> map) {
            for (var entry : map.entrySet()) {
                result.put(String.valueOf(entry.getKey()), string(entry.getValue()));
            }
        }
        return result;
    }

    private static String string(Object value) {
        return value == null ? "" : String.valueOf(value);
    }
}
