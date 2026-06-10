package com.zwbd.agentnexus.sdui.workflow;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.zwbd.agentnexus.sdui.section.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@Slf4j
@Service
@RequiredArgsConstructor
public class WorkflowPageRuntimeService {

    private final WorkflowDefinitionRepository definitionRepo;
    private final SectionOrchestrationService sectionService;
    private final DebugSectionWorkspaceService workspaceService;
    private final SectionDataCodec sectionDataCodec;
    private final ObjectMapper objectMapper;

    public boolean renderPage(String deviceId, WorkflowInstance instance, String pageId,
                              Map<String, Object> triggerPayload, Map<String, String> env) {
        WorkflowDefinition def = loadDefinition(instance.workflowId());
        if (def == null || def.pages() == null) {
            log.warn("Cannot render page for device {} workflow {}: definition not loaded",
                    deviceId, instance.workflowId());
            return false;
        }
        for (PageDef page : def.pages()) {
            if (page.id().equals(pageId)) {
                instance.activePage(pageId);
                sendPageToDevice(deviceId, page, instance, triggerPayload, env);
                return true;
            }
        }
        log.warn("Page {} not found for device {} workflow {}", pageId, deviceId, instance.workflowId());
        return false;
    }

    public void sendPageToDevice(String deviceId, PageDef page, WorkflowInstance instance,
                                 Map<String, Object> triggerPayload, Map<String, String> env) {
        SectionScene scene = buildPageSceneWithBindings(
                page, instance.variablesAsMap(), triggerPayload, env, instance.watcher());
        workspaceService.syncWorkflowScene(deviceId, scene);
        sectionService.sendScene(deviceId, scene);
    }

    public SectionScene buildPageSceneWithBindings(PageDef page, Map<String, Object> data,
                                                   Map<String, Object> triggerPayload, Map<String, String> env,
                                                   VariableWatcher watcher) {
        if (watcher != null) {
            watcher.registerPage(page);
        }
        return buildPageScene(page, data, triggerPayload, env);
    }

    public SectionScene buildPageScene(PageDef page, Map<String, Object> data,
                                       Map<String, Object> triggerPayload, Map<String, String> env) {
        List<SectionEntry> entries = new ArrayList<>();
        for (SectionBindDef section : page.sections()) {
            Map<String, Object> resolved = VariableResolver.resolve(section.bind(), data, triggerPayload, env);
            SectionData sectionData = buildSectionData(section.type(), resolved, section.id());
            if (sectionData == null) {
                log.warn("Cannot build section data for type={} id={}", section.type(), section.id());
                continue;
            }
            SectionType type = SectionType.fromWireName(section.type());
            if (type == null) {
                log.warn("Unknown section type: {}", section.type());
                continue;
            }
            entries.add(new SectionEntry(type, section.id(), sectionData));
        }
        SectionLayout layout = SectionLayout.fromWireName(page.layout());
        return new SectionScene(page.id(), layout, page.autoScroll(), page.autoScrollMs(), entries);
    }

    public boolean patchBoundSection(String deviceId, WorkflowInstance instance, VariableWatcher.SectionBinding binding,
                                     Map<String, Object> triggerPayload, Map<String, String> env) {
        if (binding == null) {
            return false;
        }
        Map<String, Object> resolved = VariableResolver.resolve(binding.bindings(), instance.variablesAsMap(), triggerPayload, env);
        SectionData sectionData = buildSectionData(binding.sectionType(), resolved, binding.sectionId());
        if (sectionData == null) {
            log.warn("Cannot patch section {} for device {}: unknown type {}",
                    binding.sectionId(), deviceId, binding.sectionType());
            return false;
        }
        SectionPatch patch = new SectionPatch(binding.pageId(), List.of(
                new SectionPatch.PatchEntry(binding.sectionId(), "update", null, sectionData)));
        workspaceService.syncWorkflowPatch(deviceId, patch);
        sectionService.sendPatch(deviceId, patch);
        return true;
    }

    private WorkflowDefinition loadDefinition(String definitionId) {
        WorkflowDefinitionEntity entity = definitionRepo.findById(definitionId).orElse(null);
        if (entity == null) {
            return null;
        }
        try {
            return objectMapper.readValue(entity.getDefinitionJson(), WorkflowDefinition.class);
        } catch (JsonProcessingException e) {
            log.warn("Failed to parse workflow definition {}: {}", definitionId, e.getMessage());
            return null;
        }
    }

    @SuppressWarnings("unchecked")
    public SectionData buildSectionData(String type, Map<String, Object> vals) {
        return buildSectionData(type, vals, type);
    }

    public SectionData buildSectionData(String type, Map<String, Object> vals, String sectionId) {
        return sectionDataCodec.buildSectionData(type, vals, sectionId);
    }
}
