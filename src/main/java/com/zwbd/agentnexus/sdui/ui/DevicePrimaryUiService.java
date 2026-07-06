package com.zwbd.agentnexus.sdui.ui;

import com.zwbd.agentnexus.sdui.section.*;
import com.zwbd.agentnexus.common.web.GlobalContext;
import com.zwbd.agentnexus.sdui.repo.SduiDeviceRepository;
import com.zwbd.agentnexus.sdui.ui.repo.DevicePrimaryUiRepository;
import com.zwbd.agentnexus.sdui.ui.repo.WorkflowUiContextRepository;
import com.zwbd.agentnexus.sdui.workflow.entity.NodeWorkflowDeploymentEntity;
import com.zwbd.agentnexus.sdui.workflow.repo.NodeWorkflowDeploymentRepository;
import jakarta.annotation.PreDestroy;
import jakarta.transaction.Transactional;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.concurrent.*;

@Slf4j
@Service
public class DevicePrimaryUiService {

    private static final long DEFAULT_TEMPORARY_UI_MS = 5_000L;

    private final DevicePrimaryUiRepository primaryUiRepository;
    private final NodeWorkflowDeploymentRepository deploymentRepository;
    private final WorkflowUiContextRepository contextRepository;
    private final SduiDeviceRepository deviceRepository;
    private final SectionOrchestrationService sectionOrchestrationService;
    private final SectionDataCodec sectionDataCodec;
    private final ScheduledExecutorService restoreExecutor = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread thread = new Thread(r, "sdui-primary-ui-restore");
        thread.setDaemon(true);
        return thread;
    });
    private final Map<String, ScheduledFuture<?>> pendingRestores = new ConcurrentHashMap<>();

    public DevicePrimaryUiService(DevicePrimaryUiRepository primaryUiRepository,
                                  NodeWorkflowDeploymentRepository deploymentRepository,
                                  WorkflowUiContextRepository contextRepository,
                                  SduiDeviceRepository deviceRepository,
                                  SectionOrchestrationService sectionOrchestrationService,
                                  SectionDataCodec sectionDataCodec) {
        this.primaryUiRepository = primaryUiRepository;
        this.deploymentRepository = deploymentRepository;
        this.contextRepository = contextRepository;
        this.deviceRepository = deviceRepository;
        this.sectionOrchestrationService = sectionOrchestrationService;
        this.sectionDataCodec = sectionDataCodec;
    }

    @Transactional
    public Map<String, Object> setPrimary(String deviceId, Map<String, Object> body) {
        String deploymentId = string(body.get("deploymentId"));
        String slotId = string(body.get("slotId"));
        String templateKey = string(body.get("templateKey"));
        if (deviceId == null || deviceId.isBlank()) {
            throw new IllegalArgumentException("deviceId is required");
        }
        if (deploymentId.isBlank()) {
            throw new IllegalArgumentException("deploymentId is required");
        }
        if (slotId.isBlank()) {
            throw new IllegalArgumentException("slotId is required");
        }

        NodeWorkflowDeploymentEntity deployment = deploymentRepository.findById(deploymentId)
                .orElseThrow(() -> new IllegalArgumentException("deployment not found: " + deploymentId));
        if (!"active".equals(deployment.getStatus())) {
            throw new IllegalArgumentException("deployment is not active: " + deploymentId);
        }
        String boundDevice = stringMap(deployment.getSlotBindings()).get(slotId);
        if (!deviceId.equals(boundDevice)) {
            throw new IllegalArgumentException("deployment slot is not bound to device: " + slotId + "/" + deviceId);
        }
        WorkflowUiContextEntity context = resolveContext(deploymentId, slotId, templateKey);

        Map<String, Object> extra = !templateKey.isBlank() && !templateKey.equals(context.getTemplateKey())
                ? Map.of("requestedTemplateKey", templateKey, "templateKeyResolved", true)
                : Map.of();
        return savePrimaryAndSend(deviceId, deployment, context, extra);
    }

    @Transactional
    public Map<String, Object> setPrimaryForDeployment(String deploymentId, String deviceId, Map<String, Object> body) {
        Map<String, Object> normalized = new LinkedHashMap<>(body != null ? body : Map.of());
        normalized.put("deploymentId", deploymentId);
        if (string(normalized.get("slotId")).isBlank() && string(normalized.get("templateKey")).isBlank()) {
            return setPrimaryByDeploymentDevice(deploymentId, deviceId);
        }
        return setPrimary(deviceId, normalized);
    }

    @Transactional
    public Map<String, Object> setPrimaryByDeploymentDevice(String deploymentId, String deviceId) {
        if (deploymentId == null || deploymentId.isBlank()) {
            throw new IllegalArgumentException("deploymentId is required");
        }
        if (deviceId == null || deviceId.isBlank()) {
            throw new IllegalArgumentException("deviceId is required");
        }
        NodeWorkflowDeploymentEntity deployment = deploymentRepository.findById(deploymentId)
                .orElseThrow(() -> new IllegalArgumentException("deployment not found: " + deploymentId));
        if (!"active".equals(deployment.getStatus())) {
            throw new IllegalArgumentException("deployment is not active: " + deploymentId);
        }

        List<String> slotIds = slotIdsForDevice(deployment, deviceId);
        if (slotIds.isEmpty()) {
            throw new IllegalArgumentException("deployment is not bound to device: " + deploymentId + "/" + deviceId);
        }

        List<WorkflowUiContextEntity> contexts = new ArrayList<>();
        for (String slotId : slotIds) {
            contexts.addAll(contextRepository.findByDeploymentIdAndSlotId(deploymentId, slotId).stream()
                    .filter(context -> deviceId.equals(context.getDeviceId()))
                    .toList());
        }
        if (contexts.isEmpty()) {
            throw new IllegalArgumentException("deployment has no UI context for device: " + deploymentId + "/" + deviceId
                    + "; ensure the workflow definition has uiTemplates and redeploy if needed");
        }
        if (contexts.size() > 1) {
            throw new IllegalArgumentException("deployment has multiple UI contexts for device: " + deploymentId + "/" + deviceId
                    + ", available=" + contexts.stream()
                    .map(context -> Map.of("contextId", context.getId(), "templateKey", context.getTemplateKey()))
                    .toList());
        }
        return savePrimaryAndSend(deviceId, deployment, contexts.get(0), Map.of());
    }

    public Map<String, Object> getPrimary(String deviceId) {
        return primaryUiRepository.findByDeviceId(deviceId)
                .map(this::toMap)
                .orElseGet(() -> Map.of("deviceId", deviceId, "configured", false));
    }

    @Transactional
    public Map<String, Object> clearPrimary(String deviceId) {
        Optional<DevicePrimaryUiEntity> existing = primaryUiRepository.findByDeviceId(deviceId);
        existing.ifPresent(primaryUiRepository::delete);
        cancelRestore(deviceId);
        return Map.of("deviceId", deviceId, "cleared", existing.isPresent());
    }

    @Transactional
    public boolean restorePrimary(String deviceId) {
        if (!hasUserContextForDevice(deviceId)) {
            return withDeviceUser(deviceId, () -> restorePrimaryInCurrentContext(deviceId));
        }
        return restorePrimaryInCurrentContext(deviceId);
    }

    private boolean restorePrimaryInCurrentContext(String deviceId) {
        cancelRestore(deviceId);
        Optional<WorkflowUiContextEntity> context = primaryContext(deviceId);
        if (context.isEmpty()) {
            log.debug("No primary UI configured for device {}", deviceId);
            return false;
        }
        boolean sent = sendContextScene(context.get());
        log.info("Primary UI restore: device={}, deployment={}, slot={}, template={}, sent={}",
                deviceId, context.get().getDeploymentId(), context.get().getSlotId(), context.get().getTemplateKey(), sent);
        return sent;
    }

    @Transactional
    public Map<String, Object> presentUpdatedContext(WorkflowUiContextEntity context, SectionPatch patch) {
        boolean primary = isPrimaryContext(context);
        if (primary) {
            boolean sent = sectionOrchestrationService.sendPatch(context.getDeviceId(), patch);
            return presentationResult(context, "primary_patch", sent);
        }

        SectionScene scene = sceneFromContext(context);
        boolean sent = sectionOrchestrationService.sendScene(context.getDeviceId(), scene);
        scheduleRestore(context.getDeviceId(), DEFAULT_TEMPORARY_UI_MS);
        Map<String, Object> result = presentationResult(context, "temporary_scene", sent);
        result.put("durationMs", DEFAULT_TEMPORARY_UI_MS);
        return result;
    }

    public SectionScene sceneFromContext(WorkflowUiContextEntity context) {
        Map<String, Object> ctx = context.getContext() != null ? context.getContext() : Map.of();
        String pageId = string(ctx.getOrDefault("activePageId", context.getActivePageId()));
        Map<String, Object> pages = SduiUiTemplateService.map(ctx.get("pages"));
        Map<String, Object> page = SduiUiTemplateService.map(pages.get(pageId));
        if (page.isEmpty()) {
            throw new IllegalArgumentException("ui context page not found: " + pageId);
        }

        SectionLayout layout = SectionLayout.fromWireName(string(page.getOrDefault("layout", "vertical_scroll")));
        if (layout == null) {
            layout = SectionLayout.VERTICAL_SCROLL;
        }
        List<SectionEntry> sections = new ArrayList<>();
        Map<String, Object> rawSections = SduiUiTemplateService.map(page.get("sections"));
        for (Object item : rawSections.values()) {
            Map<String, Object> section = SduiUiTemplateService.map(item);
            String sectionId = string(section.get("sectionId"));
            String sectionType = string(section.get("sectionType"));
            Map<String, Object> fields = SduiUiTemplateService.map(section.get("fields"));
            SectionData data = sectionDataCodec.buildSectionData(sectionType, fields, sectionId);
            if (data == null) {
                throw new IllegalArgumentException("invalid ui context section: " + sectionId);
            }
            sections.add(new SectionEntry(sectionType, sectionId, data));
        }
        if (sections.isEmpty()) {
            throw new IllegalArgumentException("ui context has no sections: " + context.getId());
        }
        return new SectionScene(pageId, layout, false, 0, sections);
    }

    private Optional<WorkflowUiContextEntity> primaryContext(String deviceId) {
        return primaryUiRepository.findByDeviceId(deviceId)
                .flatMap(primary -> contextRepository.findByDeploymentIdAndSlotIdAndTemplateKey(
                        primary.getDeploymentId(), primary.getSlotId(), primary.getTemplateKey()));
    }

    private boolean isPrimaryContext(WorkflowUiContextEntity context) {
        return primaryUiRepository.findByDeviceIdAndDeploymentIdAndSlotIdAndTemplateKey(
                context.getDeviceId(),
                context.getDeploymentId(),
                context.getSlotId(),
                context.getTemplateKey()
        ).isPresent();
    }

    private boolean sendContextScene(WorkflowUiContextEntity context) {
        return sectionOrchestrationService.sendScene(context.getDeviceId(), sceneFromContext(context));
    }

    private Map<String, Object> savePrimaryAndSend(String deviceId,
                                                   NodeWorkflowDeploymentEntity deployment,
                                                   WorkflowUiContextEntity context,
                                                   Map<String, Object> extra) {
        DevicePrimaryUiEntity primary = primaryUiRepository.findByDeviceId(deviceId)
                .orElseGet(DevicePrimaryUiEntity::new);
        primary.setDeviceId(deviceId);
        primary.setWorkflowId(deployment.getWorkflowId());
        primary.setDeploymentId(deployment.getId());
        primary.setSlotId(context.getSlotId());
        primary.setTemplateKey(context.getTemplateKey());
        primary = primaryUiRepository.save(primary);

        boolean sent = sendContextScene(context);
        Map<String, Object> data = toMap(primary);
        data.put("sent", sent);
        data.putAll(extra);
        return data;
    }

    private WorkflowUiContextEntity resolveContext(String deploymentId, String slotId, String templateKey) {
        if (!templateKey.isBlank()) {
            Optional<WorkflowUiContextEntity> exact = contextRepository
                    .findByDeploymentIdAndSlotIdAndTemplateKey(deploymentId, slotId, templateKey);
            if (exact.isPresent()) {
                return exact.get();
            }
        }

        List<WorkflowUiContextEntity> contexts = contextRepository.findByDeploymentIdAndSlotId(deploymentId, slotId);
        if (contexts.size() == 1) {
            WorkflowUiContextEntity resolved = contexts.get(0);
            if (!templateKey.isBlank()) {
                log.info("Primary UI templateKey resolved by slot context: deployment={}, slot={}, requested={}, resolved={}",
                        deploymentId, slotId, templateKey, resolved.getTemplateKey());
            }
            return resolved;
        }
        if (contexts.isEmpty()) {
            throw new IllegalArgumentException("ui context not found for deployment slot: " + deploymentId + "/" + slotId
                    + "; ensure the workflow definition has uiTemplates and the deployment was initialized after that");
        }
        throw new IllegalArgumentException("templateKey is required when deployment slot has multiple ui contexts: "
                + deploymentId + "/" + slotId + ", available=" + contexts.stream()
                .map(WorkflowUiContextEntity::getTemplateKey)
                .toList());
    }

    private void scheduleRestore(String deviceId, long delayMs) {
        cancelRestore(deviceId);
        ScheduledFuture<?> future = restoreExecutor.schedule(() -> {
            try {
                restorePrimary(deviceId);
            } catch (Exception e) {
                log.warn("Primary UI restore failed after temporary UI: device={}, error={}", deviceId, e.getMessage());
            } finally {
                pendingRestores.remove(deviceId);
            }
        }, delayMs, TimeUnit.MILLISECONDS);
        pendingRestores.put(deviceId, future);
    }

    private void cancelRestore(String deviceId) {
        ScheduledFuture<?> previous = pendingRestores.remove(deviceId);
        if (previous != null) {
            previous.cancel(false);
        }
    }

    private boolean hasUserContextForDevice(String deviceId) {
        String current = GlobalContext.getString(GlobalContext.KEY_USER_ID);
        if (current == null || current.isBlank() || GlobalContext.DEFAULT_USER_ID.equals(current)) {
            return false;
        }
        return true;
    }

    private boolean withDeviceUser(String deviceId, Callable<Boolean> task) {
        String previous = GlobalContext.getString(GlobalContext.KEY_USER_ID);
        String ownerUserId = deviceRepository.findById(deviceId)
                .map(device -> string(device.getOwnerUserId()))
                .filter(value -> !value.isBlank())
                .orElse(GlobalContext.DEFAULT_USER_ID);
        GlobalContext.set(GlobalContext.KEY_USER_ID, ownerUserId);
        try {
            return task.call();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        } finally {
            if (previous == null || previous.isBlank()) {
                GlobalContext.clear();
            } else {
                GlobalContext.set(GlobalContext.KEY_USER_ID, previous);
            }
        }
    }

    private Map<String, Object> presentationResult(WorkflowUiContextEntity context, String mode, boolean sent) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("mode", mode);
        result.put("sent", sent);
        result.put("status", sent ? "sent" : "send_failed");
        result.put("deviceId", context.getDeviceId());
        result.put("workflowId", context.getWorkflowId());
        result.put("deploymentId", context.getDeploymentId());
        result.put("slotId", context.getSlotId());
        result.put("templateKey", context.getTemplateKey());
        return result;
    }

    private Map<String, Object> toMap(DevicePrimaryUiEntity entity) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("configured", true);
        data.put("id", entity.getId());
        data.put("deviceId", entity.getDeviceId());
        data.put("workflowId", entity.getWorkflowId());
        data.put("deploymentId", entity.getDeploymentId());
        data.put("slotId", entity.getSlotId());
        data.put("templateKey", entity.getTemplateKey());
        data.put("createdAt", entity.getCreatedAt() != null ? entity.getCreatedAt().toString() : null);
        data.put("updatedAt", entity.getUpdatedAt() != null ? entity.getUpdatedAt().toString() : null);
        return data;
    }

    @PreDestroy
    void shutdown() {
        restoreExecutor.shutdownNow();
    }

    private static String string(Object value) {
        return value == null ? "" : String.valueOf(value);
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

    private static List<String> slotIdsForDevice(NodeWorkflowDeploymentEntity deployment, String deviceId) {
        return stringMap(deployment.getSlotBindings()).entrySet().stream()
                .filter(entry -> deviceId.equals(entry.getValue()))
                .map(Map.Entry::getKey)
                .toList();
    }
}
