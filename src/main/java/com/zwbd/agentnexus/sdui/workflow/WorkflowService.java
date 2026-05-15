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
        WorkflowInstance instance = runningInstances.remove(deviceId);
        if (instance != null) {
            instance.watcher().unregisterAll();
        }
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

    /**
     * Fire a device event with optional sectionId/nodeId for targeted trigger matching.
     */
    public int fireEvent(String deviceId, String eventType, Map<String, Object> payload) {
        WorkflowInstance instance = runningInstances.get(deviceId);
        WorkflowDefinition def = loadedDefinitions.get(deviceId);
        if (instance == null || def == null) return 0;

        String sectionId = payload.get("sectionId") instanceof String s && !s.isEmpty() ? s : null;
        String nodeId = payload.get("nodeId") instanceof String s && !s.isEmpty() ? s : null;

        Map<String, String> env = envConfigs.getOrDefault(def.id(), Map.of());
        int fired = 0;

        for (TriggerDef trigger : def.triggers()) {
            if (!(trigger instanceof TriggerDef.DeviceEventTrigger d)) continue;
            if (!d.event().equals(eventType)) continue;
            if (!matchesFilter(d.sectionId(), sectionId)) continue;
            if (!matchesFilter(d.nodeId(), nodeId)) continue;

            List<ActionDef> actions = def.actions().get(trigger.id());
            if (actions != null) {
                actionExecutor.execute(actions, instance, payload, env);
                fired++;
            }
        }

        // fallback: if no sectionId-aware trigger matched, try legacy event-only triggers
        if (fired == 0 && (sectionId != null || nodeId != null)) {
            for (TriggerDef trigger : def.triggers()) {
                if (!(trigger instanceof TriggerDef.DeviceEventTrigger d)) continue;
                if (!d.event().equals(eventType)) continue;
                // legacy triggers with no sectionId/nodeId filter
                if (isBlank(d.sectionId()) && isBlank(d.nodeId())) {
                    List<ActionDef> actions = def.actions().get(trigger.id());
                    if (actions != null) {
                        actionExecutor.execute(actions, instance, payload, env);
                        fired++;
                    }
                }
            }
        }

        return fired;
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
                                List.of(
                                        param("interval", "间隔秒数", "number", ""),
                                        param("cron", "Cron 表达式", "string", "例如: 0 */5 * * * *")
                                )),
                        Map.of("type", "webhook", "label", "Webhook 触发", "params",
                                List.of(param("path", "回调路径", "string", "例如: /alerts/github"))),
                        Map.of("type", "device_event", "label", "设备事件触发", "params",
                                List.of(
                                        param("event", "事件类型", "string", "click / shake / long_press"),
                                        param("sectionId", "限定 Section", "string", "可选，留空则不限"),
                                        param("nodeId", "限定控件", "string", "可选，留空则不限")
                                ))
                ),
                "actions", List.of(
                        Map.of("type", "fetch", "label", "HTTP 请求", "params",
                                List.of(
                                        param("url", "请求地址", "string", "支持 $data.xxx / $trigger.xxx"),
                                        param("method", "请求方法", "string", "GET / POST"),
                                        param("save", "存储变量名", "string", "结果保存到 $data.<name>")
                                )),
                        Map.of("type", "set_variable", "label", "设置变量", "params",
                                List.of(
                                        param("variable", "变量名", "string", "不含 $data. 前缀"),
                                        param("value", "变量值", "string",
                                                "支持 $data.xxx / $trigger.xxx / $env.XXX / 字面量 / 表达式")
                                ),
                                "syntax", "支持表达式: $data.x + 10 / '文本' / min(a,b) / $data.x == 'playing' ? '暂停' : '播放'"),
                        Map.of("type", "condition", "label", "条件分支", "params",
                                List.of(
                                        param("variable", "判断变量", "string", "$data.xxx"),
                                        param("operator", "运算符", "string", "eq / neq / gt / gte / lt / lte / contains / isEmpty"),
                                        param("value", "比较值", "string", "支持字面量和 $data.xxx"),
                                        param("thenActions", "条件成立时执行", "actions[]", "动作数组"),
                                        param("elseActions", "条件不成立时执行", "actions[]", "可选，动作数组")
                                ),
                                "syntax", "thenActions 和 elseActions 均为动作数组，可嵌套 condition/sequence"),
                        Map.of("type", "sequence", "label", "顺序执行", "params",
                                List.of(param("steps", "步骤列表", "actions[]", "按顺序执行的动作数组")),
                                "syntax", "可包含任意类型动作，常用于 condition 分支内部"),
                        Map.of("type", "play_audio", "label", "播放音频", "params",
                                List.of(
                                        param("preset", "预设音", "string",
                                                "notification / success / error / warning / click / beep")
                                )),
                        Map.of("type", "tts", "label", "TTS 朗读", "params",
                                List.of(param("text", "朗读文本", "string", "支持 $data.xxx / $trigger.xxx"))),
                        Map.of("type", "patch_section", "label", "增量更新 Section", "params",
                                List.of(
                                        param("page", "页面 ID", "string", ""),
                                        param("sectionId", "Section ID", "string", ""),
                                        param("bind", "绑定数据源", "string", "支持 $data.xxx")
                                )),
                        Map.of("type", "update_page", "label", "下发页面", "params",
                                List.of(param("page", "页面 ID", "string", ""))),
                        Map.of("type", "switch_page", "label", "切换页面", "params",
                                List.of(param("page", "页面 ID", "string", ""))),
                        Map.of("type", "control", "label", "执行器命令", "params",
                                List.of(
                                        param("command", "命令名", "string", "例如: rgb.effect.set"),
                                        param("value", "命令值", "string", "支持 $data.xxx / $trigger.xxx")
                                ))
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

    // ── Internal helpers ──

    public void sendPageToDevice(String deviceId, PageDef page, WorkflowInstance instance,
                                  Map<String, Object> triggerPayload, Map<String, String> env) {
        SectionScene scene = actionExecutor.buildPageSceneWithBindings(
                page, instance.variablesAsMap(), triggerPayload, env, instance.watcher());
        sectionService.sendScene(deviceId, scene);
    }

    private boolean matchesFilter(String filterValue, String actualValue) {
        if (filterValue == null || filterValue.isEmpty()) return true;
        return filterValue.equals(actualValue);
    }

    private boolean isBlank(String s) {
        return s == null || s.isBlank();
    }

    private Map<String, Object> param(String name, String label, String type, String syntax) {
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("name", name);
        p.put("label", label);
        p.put("type", type);
        if (syntax != null && !syntax.isEmpty()) p.put("syntax", syntax);
        return p;
    }
}
