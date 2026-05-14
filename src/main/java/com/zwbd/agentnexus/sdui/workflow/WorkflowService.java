package com.zwbd.agentnexus.sdui.workflow;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.zwbd.agentnexus.sdui.section.SectionScene;
import com.zwbd.agentnexus.sdui.section.SectionOrchestrationService;
import jakarta.transaction.Transactional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.*;

@Slf4j
@Service
@RequiredArgsConstructor
public class WorkflowService {

    private final WorkflowDefinitionRepository definitionRepo;
    private final WorkflowInstanceRepository instanceRepo;
    private final ActionExecutor actionExecutor;
    private final TriggerScheduler triggerScheduler;
    private final SectionOrchestrationService sectionService;
    private final ObjectMapper objectMapper;

    private final Map<String, WorkflowInstance> runningInstances = new LinkedHashMap<>();
    private final Map<String, WorkflowDefinition> loadedDefinitions = new LinkedHashMap<>();
    private final Map<String, Map<String, String>> envConfigs = new LinkedHashMap<>();

    // ── Definition CRUD ──

    public List<WorkflowDefinitionEntity> listDefinitions() {
        return definitionRepo.findAll();
    }

    public Optional<WorkflowDefinitionEntity> getDefinition(String id) {
        return definitionRepo.findById(id);
    }

    @Transactional
    public WorkflowDefinitionEntity saveDefinition(WorkflowDefinitionEntity entity) {
        return definitionRepo.save(entity);
    }

    @Transactional
    public void deleteDefinition(String id) {
        definitionRepo.deleteById(id);
    }

    // ── Instance management ──

    @Transactional
    public Map<String, Object> loadWorkflow(String deviceId, String definitionId) {
        unloadWorkflow(deviceId);

        WorkflowDefinitionEntity entity = definitionRepo.findById(definitionId)
                .orElseThrow(() -> new IllegalArgumentException("Workflow definition not found: " + definitionId));

        WorkflowDefinition def;
        try {
            def = objectMapper.readValue(entity.getDefinitionJson(), WorkflowDefinition.class);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("Failed to parse workflow definition JSON", e);
        }

        WorkflowInstance instance = new WorkflowInstance(deviceId, definitionId);
        Map<String, String> env = envConfigs.getOrDefault(definitionId, Map.of());

        loadedDefinitions.put(deviceId, def);
        runningInstances.put(deviceId, instance);

        triggerScheduler.registerTriggers(deviceId, def, instance, actionExecutor, env);

        if (def.pages() != null && !def.pages().isEmpty()) {
            PageDef firstPage = def.pages().get(0);
            instance.activePage(firstPage.id());
            sendPageToDevice(deviceId, firstPage, instance, Map.of(), env);
        }

        WorkflowInstanceEntity ie = new WorkflowInstanceEntity();
        ie.setDeviceId(deviceId);
        ie.setDefinitionId(definitionId);
        ie.setActivePage(instance.activePage());
        ie.setStatus("RUNNING");
        instanceRepo.deleteByDeviceId(deviceId);
        instanceRepo.save(ie);

        log.info("Workflow loaded: deviceId={} definitionId={}", deviceId, definitionId);
        return Map.of("status", "loaded", "deviceId", deviceId, "definitionId", definitionId,
                "activePage", instance.activePage());
    }

    @Transactional
    public Map<String, Object> unloadWorkflow(String deviceId) {
        triggerScheduler.unregisterAll(deviceId);
        runningInstances.remove(deviceId);
        loadedDefinitions.remove(deviceId);
        instanceRepo.deleteByDeviceId(deviceId);
        log.info("Workflow unloaded: deviceId={}", deviceId);
        return Map.of("status", "unloaded", "deviceId", deviceId);
    }

    public boolean triggerManually(String deviceId, String triggerId) {
        WorkflowInstance instance = runningInstances.get(deviceId);
        WorkflowDefinition def = loadedDefinitions.get(deviceId);
        if (instance == null || def == null) {
            log.warn("No active workflow for device {}", deviceId);
            return false;
        }

        List<ActionDef> actions = def.actions() != null ? def.actions().get(triggerId) : null;
        if (actions == null || actions.isEmpty()) {
            log.warn("Trigger {} not found or has no actions for device {}", triggerId, deviceId);
            return false;
        }

        Map<String, String> env = envConfigs.getOrDefault(def.id(), Map.of());
        actionExecutor.execute(actions, instance, Map.of(), env);
        return true;
    }

    public void fireEvent(String deviceId, String eventType, Map<String, Object> payload) {
        WorkflowInstance instance = runningInstances.get(deviceId);
        WorkflowDefinition def = loadedDefinitions.get(deviceId);
        if (instance == null || def == null) return;

        for (TriggerDef trigger : def.triggers()) {
            if (trigger instanceof TriggerDef.DeviceEventTrigger d && d.event().equals(eventType)) {
                List<ActionDef> actions = def.actions().get(trigger.id());
                if (actions != null) {
                    Map<String, String> env = envConfigs.getOrDefault(def.id(), Map.of());
                    actionExecutor.execute(actions, instance, payload, env);
                }
                break;
            }
        }
    }

    public Map<String, Object> getDeviceStatus(String deviceId) {
        WorkflowInstance instance = runningInstances.get(deviceId);
        WorkflowDefinition def = loadedDefinitions.get(deviceId);
        if (instance == null) {
            return Map.of("deviceId", deviceId, "status", "no_workflow");
        }
        return Map.of(
                "deviceId", deviceId,
                "status", instance.status().name(),
                "workflowId", instance.workflowId(),
                "workflowName", def != null ? def.name() : "",
                "activePage", instance.activePage()
        );
    }

    public Map<String, Object> getNodeTypes() {
        return Map.of(
                "triggers", List.of(
                        Map.of("type", "manual", "label", "手动触发", "params", List.of()),
                        Map.of("type", "cron", "label", "定时触发", "params",
                                List.of("interval(秒)", "cron(表达式)")),
                        Map.of("type", "webhook", "label", "Webhook 触发", "params",
                                List.of("path(回调路径)")),
                        Map.of("type", "device_event", "label", "设备事件触发", "params",
                                List.of("event(事件类型)"))
                ),
                "actions", List.of(
                        Map.of("type", "fetch", "label", "HTTP 请求", "params",
                                List.of("url", "method", "save(变量名)")),
                        Map.of("type", "update_page", "label", "下发页面", "params",
                                List.of("page(页面ID)")),
                        Map.of("type", "patch_section", "label", "增量更新 Section", "params",
                                List.of("page", "sectionId", "bind")),
                        Map.of("type", "play_audio", "label", "播放音频", "params",
                                List.of("preset")),
                        Map.of("type", "tts", "label", "TTS 朗读", "params", List.of("text")),
                        Map.of("type", "switch_page", "label", "切换页面", "params",
                                List.of("page")),
                        Map.of("type", "control", "label", "执行器命令", "params",
                                List.of("command", "value"))
                ),
                "sectionTypes", List.of(
                        Map.of("type", "hero_section", "label", "Hero 数据", "fields",
                                List.of("value", "label", "subtitle", "tone", "iconSrc", "progress")),
                        Map.of("type", "metric_section", "label", "指标网格", "fields",
                                List.of("metrics[]")),
                        Map.of("type", "chart_section", "label", "图表", "fields",
                                List.of("title", "points[]", "progress")),
                        Map.of("type", "list_section", "label", "列表", "fields",
                                List.of("items[]")),
                        Map.of("type", "progress_section", "label", "进度条", "fields",
                                List.of("title", "progress", "progressText")),
                        Map.of("type", "text_section", "label", "文本", "fields",
                                List.of("title", "body")),
                        Map.of("type", "action_section", "label", "操作按钮", "fields",
                                List.of("actions[]")),
                        Map.of("type", "timer_section", "label", "计时器", "fields",
                                List.of("title", "progress", "elapsedMs", "running")),
                        Map.of("type", "image_section", "label", "图片", "fields",
                                List.of("iconSrc", "title", "subtitle")),
                        Map.of("type", "overlay_section", "label", "覆盖层", "fields",
                                List.of("title", "body", "tone", "unreadCount")),
                        Map.of("type", "toggle_section", "label", "开关组", "fields",
                                List.of("options[]")),
                        Map.of("type", "nav_section", "label", "导航标签", "fields",
                                List.of("tabs[]", "activeTab"))
                )
        );
    }

    public void sendPageToDevice(String deviceId, PageDef page, WorkflowInstance instance,
                                  Map<String, Object> triggerPayload, Map<String, String> env) {
        SectionScene scene = actionExecutor.buildPageScene(page, instance.variablesAsMap(), triggerPayload, env);
        sectionService.sendScene(deviceId, scene);
    }
}
