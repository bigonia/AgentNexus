package com.zwbd.agentnexus.sdui.ui;

import com.zwbd.agentnexus.common.web.GlobalContext;
import com.zwbd.agentnexus.sdui.model.SduiDevice;
import com.zwbd.agentnexus.sdui.repo.SduiDeviceRepository;
import com.zwbd.agentnexus.sdui.section.*;
import com.zwbd.agentnexus.sdui.ui.repo.DevicePrimaryUiRepository;
import com.zwbd.agentnexus.sdui.ui.repo.WorkflowUiContextRepository;
import com.zwbd.agentnexus.sdui.workflow.entity.NodeWorkflowDeploymentEntity;
import com.zwbd.agentnexus.sdui.workflow.repo.NodeWorkflowDeploymentRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.Map;
import java.util.Optional;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

class DevicePrimaryUiServiceTest {

    private DevicePrimaryUiRepository primaryRepository;
    private NodeWorkflowDeploymentRepository deploymentRepository;
    private WorkflowUiContextRepository contextRepository;
    private SduiDeviceRepository deviceRepository;
    private SectionOrchestrationService sectionService;
    private DevicePrimaryUiService service;

    @BeforeEach
    void setUp() {
        GlobalContext.set(GlobalContext.KEY_USER_ID, "alice");
        primaryRepository = mock(DevicePrimaryUiRepository.class);
        deploymentRepository = mock(NodeWorkflowDeploymentRepository.class);
        contextRepository = mock(WorkflowUiContextRepository.class);
        deviceRepository = mock(SduiDeviceRepository.class);
        sectionService = mock(SectionOrchestrationService.class);
        SectionTypeCatalog catalog = mock(SectionTypeCatalog.class);
        when(catalog.get(any())).thenReturn(Optional.empty());
        service = new DevicePrimaryUiService(
                primaryRepository,
                deploymentRepository,
                contextRepository,
                deviceRepository,
                sectionService,
                new SectionDataCodec(catalog)
        );
    }

    @AfterEach
    void tearDown() {
        service.shutdown();
        GlobalContext.clear();
    }

    @Test
    void setPrimaryValidatesBindingAndSendsContextScene() {
        NodeWorkflowDeploymentEntity deployment = deployment();
        WorkflowUiContextEntity context = context("wf-1", "dep-1", "main-slot", "dev-a", "main_view");
        when(deploymentRepository.findById("dep-1")).thenReturn(Optional.of(deployment));
        when(contextRepository.findByDeploymentIdAndSlotIdAndTemplateKey("dep-1", "main-slot", "main_view"))
                .thenReturn(Optional.of(context));
        when(primaryRepository.findByDeviceId("dev-a")).thenReturn(Optional.empty());
        when(primaryRepository.save(any())).thenAnswer(inv -> {
            DevicePrimaryUiEntity entity = inv.getArgument(0);
            entity.setId("primary-1");
            return entity;
        });
        when(sectionService.sendScene(eq("dev-a"), any())).thenReturn(true);

        Map<String, Object> result = service.setPrimary("dev-a", Map.of(
                "deploymentId", "dep-1",
                "slotId", "main-slot",
                "templateKey", "main_view"
        ));

        assertEquals("dep-1", result.get("deploymentId"));
        assertEquals(true, result.get("sent"));
        verify(sectionService).sendScene(eq("dev-a"), any());
    }

    @Test
    void setPrimaryResolvesSingleSlotContextWhenTemplateKeyDoesNotMatch() {
        NodeWorkflowDeploymentEntity deployment = deployment();
        WorkflowUiContextEntity context = context("wf-1", "dep-1", "main-slot", "dev-a", "actual_view");
        when(deploymentRepository.findById("dep-1")).thenReturn(Optional.of(deployment));
        when(contextRepository.findByDeploymentIdAndSlotIdAndTemplateKey("dep-1", "main-slot", "main_view"))
                .thenReturn(Optional.empty());
        when(contextRepository.findByDeploymentIdAndSlotId("dep-1", "main-slot"))
                .thenReturn(List.of(context));
        when(primaryRepository.findByDeviceId("dev-a")).thenReturn(Optional.empty());
        when(primaryRepository.save(any())).thenAnswer(inv -> {
            DevicePrimaryUiEntity entity = inv.getArgument(0);
            entity.setId("primary-1");
            return entity;
        });
        when(sectionService.sendScene(eq("dev-a"), any())).thenReturn(true);

        Map<String, Object> result = service.setPrimary("dev-a", Map.of(
                "deploymentId", "dep-1",
                "slotId", "main-slot",
                "templateKey", "main_view"
        ));

        assertEquals("actual_view", result.get("templateKey"));
        assertEquals("main_view", result.get("requestedTemplateKey"));
        assertEquals(true, result.get("templateKeyResolved"));
        verify(sectionService).sendScene(eq("dev-a"), any());
    }

    @Test
    void setPrimaryAllowsOmittingTemplateKeyWhenSlotHasSingleContext() {
        NodeWorkflowDeploymentEntity deployment = deployment();
        WorkflowUiContextEntity context = context("wf-1", "dep-1", "main-slot", "dev-a", "actual_view");
        when(deploymentRepository.findById("dep-1")).thenReturn(Optional.of(deployment));
        when(contextRepository.findByDeploymentIdAndSlotId("dep-1", "main-slot"))
                .thenReturn(List.of(context));
        when(primaryRepository.findByDeviceId("dev-a")).thenReturn(Optional.empty());
        when(primaryRepository.save(any())).thenAnswer(inv -> {
            DevicePrimaryUiEntity entity = inv.getArgument(0);
            entity.setId("primary-1");
            return entity;
        });
        when(sectionService.sendScene(eq("dev-a"), any())).thenReturn(true);

        Map<String, Object> result = service.setPrimary("dev-a", Map.of(
                "deploymentId", "dep-1",
                "slotId", "main-slot"
        ));

        assertEquals("actual_view", result.get("templateKey"));
        assertEquals(true, result.get("sent"));
    }

    @Test
    void setPrimaryForDeploymentResolvesContextFromDeviceOnly() {
        NodeWorkflowDeploymentEntity deployment = deployment();
        WorkflowUiContextEntity context = context("wf-1", "dep-1", "main-slot", "dev-a", "actual_view");
        when(deploymentRepository.findById("dep-1")).thenReturn(Optional.of(deployment));
        when(contextRepository.findByDeploymentIdAndSlotId("dep-1", "main-slot"))
                .thenReturn(List.of(context));
        when(primaryRepository.findByDeviceId("dev-a")).thenReturn(Optional.empty());
        when(primaryRepository.save(any())).thenAnswer(inv -> {
            DevicePrimaryUiEntity entity = inv.getArgument(0);
            entity.setId("primary-1");
            return entity;
        });
        when(sectionService.sendScene(eq("dev-a"), any())).thenReturn(true);

        Map<String, Object> result = service.setPrimaryForDeployment("dep-1", "dev-a", Map.of());

        assertEquals("dep-1", result.get("deploymentId"));
        assertEquals("actual_view", result.get("templateKey"));
        assertEquals(true, result.get("sent"));
    }

    @Test
    void setPrimaryForDeploymentRejectsDeviceWithoutUiContext() {
        NodeWorkflowDeploymentEntity deployment = deployment();
        when(deploymentRepository.findById("dep-1")).thenReturn(Optional.of(deployment));
        when(contextRepository.findByDeploymentIdAndSlotId("dep-1", "main-slot"))
                .thenReturn(List.of());

        org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class,
                () -> service.setPrimaryForDeployment("dep-1", "dev-a", Map.of()));
    }

    @Test
    void primaryContextUpdateUsesPatch() {
        WorkflowUiContextEntity context = context("wf-1", "dep-1", "main-slot", "dev-a", "main_view");
        when(primaryRepository.findByDeviceIdAndDeploymentIdAndSlotIdAndTemplateKey(
                "dev-a", "dep-1", "main-slot", "main_view")).thenReturn(Optional.of(new DevicePrimaryUiEntity()));
        when(sectionService.sendPatch(eq("dev-a"), any())).thenReturn(true);

        Map<String, Object> result = service.presentUpdatedContext(context, patch());

        assertEquals("primary_patch", result.get("mode"));
        assertEquals(true, result.get("sent"));
        verify(sectionService).sendPatch(eq("dev-a"), any());
        verify(sectionService, never()).sendScene(eq("dev-a"), any());
    }

    @Test
    void nonPrimaryContextUpdateUsesTemporaryScene() {
        WorkflowUiContextEntity context = context("wf-2", "dep-2", "notice-slot", "dev-a", "notice_view");
        when(primaryRepository.findByDeviceIdAndDeploymentIdAndSlotIdAndTemplateKey(
                "dev-a", "dep-2", "notice-slot", "notice_view")).thenReturn(Optional.empty());
        when(sectionService.sendScene(eq("dev-a"), any())).thenReturn(true);

        Map<String, Object> result = service.presentUpdatedContext(context, patch());

        assertEquals("temporary_scene", result.get("mode"));
        assertEquals(5000L, result.get("durationMs"));
        ArgumentCaptor<SectionScene> sceneCaptor = ArgumentCaptor.forClass(SectionScene.class);
        verify(sectionService).sendScene(eq("dev-a"), sceneCaptor.capture());
        assertEquals("main", sceneCaptor.getValue().pageId());
        verify(sectionService, never()).sendPatch(eq("dev-a"), any());
    }

    @Test
    void restorePrimaryUsesDeviceOwnerWhenNoRequestContext() {
        GlobalContext.clear();
        SduiDevice device = new SduiDevice();
        device.setDeviceId("dev-a");
        device.setOwnerUserId("alice");
        DevicePrimaryUiEntity primary = new DevicePrimaryUiEntity();
        primary.setDeviceId("dev-a");
        primary.setDeploymentId("dep-1");
        primary.setSlotId("main-slot");
        primary.setTemplateKey("main_view");
        WorkflowUiContextEntity context = context("wf-1", "dep-1", "main-slot", "dev-a", "main_view");

        when(deviceRepository.findById("dev-a")).thenReturn(Optional.of(device));
        when(primaryRepository.findByDeviceId("dev-a")).thenReturn(Optional.of(primary));
        when(contextRepository.findByDeploymentIdAndSlotIdAndTemplateKey("dep-1", "main-slot", "main_view"))
                .thenReturn(Optional.of(context));
        when(sectionService.sendScene(eq("dev-a"), any())).thenReturn(true);

        boolean sent = service.restorePrimary("dev-a");

        assertEquals(true, sent);
        verify(sectionService).sendScene(eq("dev-a"), any());
    }

    private NodeWorkflowDeploymentEntity deployment() {
        NodeWorkflowDeploymentEntity deployment = new NodeWorkflowDeploymentEntity();
        deployment.setId("dep-1");
        deployment.setWorkflowId("wf-1");
        deployment.setStatus("active");
        deployment.setSlotBindings(Map.of("main-slot", "dev-a"));
        return deployment;
    }

    private WorkflowUiContextEntity context(String workflowId, String deploymentId, String slotId,
                                            String deviceId, String templateKey) {
        WorkflowUiContextEntity context = new WorkflowUiContextEntity();
        context.setId("ctx-" + deploymentId);
        context.setWorkflowId(workflowId);
        context.setDeploymentId(deploymentId);
        context.setSlotId(slotId);
        context.setDeviceId(deviceId);
        context.setTemplateKey(templateKey);
        context.setActivePageId("main");
        context.setContext(Map.of(
                "activePageId", "main",
                "pages", Map.of(
                        "main", Map.of(
                                "pageId", "main",
                                "layout", "vertical_scroll",
                                "sections", Map.of(
                                        "text1", Map.of(
                                                "sectionId", "text1",
                                                "sectionType", "text_section",
                                                "fields", Map.of("title", "T", "body", "B")
                                        )
                                )
                        )
                )
        ));
        return context;
    }

    private SectionPatch patch() {
        return new SectionPatch("main", java.util.List.of(
                new SectionPatch.PatchEntry("text1", "update", "text_section",
                        new SectionData.TextData("T", "B"))
        ));
    }
}
