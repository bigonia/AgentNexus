package com.zwbd.agentnexus.sdui.debug.workflow;

import com.zwbd.agentnexus.sdui.DeviceSessionManager;
import com.zwbd.agentnexus.sdui.capability.node.CapabilityNodeCatalog;
import com.zwbd.agentnexus.sdui.capability.node.CapabilityNodeCatalogService;
import com.zwbd.agentnexus.sdui.capability.node.CapabilityNodeDefinition;
import com.zwbd.agentnexus.sdui.capability.node.CapabilityNodeRuntimeMode;
import com.zwbd.agentnexus.sdui.debug.node.CapabilityNodeTestService;
import com.zwbd.agentnexus.sdui.event.EventPayload;
import com.zwbd.agentnexus.sdui.handler.EventInputHandler;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class NodeWorkflowDebugServiceTest {

    private EventInputHandler eventInputHandler;
    private DeviceSessionManager sessionManager;
    private CapabilityNodeCatalogService nodeCatalogService;
    private CapabilityNodeTestService nodeTestService;
    private EventInputHandler.PayloadEventListener listener;
    private NodeWorkflowDebugService service;

    @BeforeEach
    void setUp() {
        eventInputHandler = mock(EventInputHandler.class);
        sessionManager = mock(DeviceSessionManager.class);
        nodeCatalogService = mock(CapabilityNodeCatalogService.class);
        nodeTestService = mock(CapabilityNodeTestService.class);
        service = new NodeWorkflowDebugService(eventInputHandler, sessionManager, nodeCatalogService, nodeTestService);

        ArgumentCaptor<EventInputHandler.PayloadEventListener> captor =
                ArgumentCaptor.forClass(EventInputHandler.PayloadEventListener.class);
        verify(eventInputHandler).addPayloadListener(captor.capture());
        listener = captor.getValue();
    }

    @Test
    void rejectsInvalidDefinition() {
        NodeWorkflowDefinition invalid = new NodeWorkflowDefinition(
                null,
                "bad",
                List.of(new NodeWorkflowSlot("source", "type-a", "Source", List.of())),
                List.of(new NodeWorkflowNode("target_rgb", "missing", "rgb.effect", Map.of())),
                List.of(new NodeWorkflowEdge("source_button", "target_rgb"))
        );

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> service.createWorkflow(invalid));
        assertTrue(error.getMessage().contains("unknown slot"));
    }

    @Test
    void deployRejectsMissingNodeCapability() {
        String workflowId = createWorkflow();
        when(sessionManager.isDeviceOnline(anyString())).thenReturn(true);
        when(nodeCatalogService.buildForDevice("dev-a")).thenReturn(catalog("dev-a", "button.trigger"));
        when(nodeCatalogService.buildForDevice("dev-b")).thenReturn(catalog("dev-b"));

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> service.deploy(workflowId, Map.of("slotBindings", Map.of(
                        "source", "dev-a",
                        "target", "dev-b"
                ))));

        assertTrue(error.getMessage().contains("does not support nodeType rgb.effect"));
    }

    @Test
    void eventExecutesOutputNodeOnTargetSlot() {
        String workflowId = createWorkflow();
        String deploymentId = deploy(workflowId, "dev-a", "dev-b");
        when(nodeTestService.executeOutputTest(eq("dev-b"), anyMap())).thenReturn(Map.of(
                "status", "sent",
                "sent", true,
                "nodeType", "rgb.effect",
                "deviceId", "dev-b"
        ));

        listener.onEvent(new EventPayload(
                "input:buttons.pwr.short_press",
                "dev-a",
                "",
                "",
                "pwr",
                0,
                null,
                System.currentTimeMillis(),
                Map.of()
        ));

        verify(nodeTestService).executeOutputTest(eq("dev-b"), argThat(body ->
                "rgb.effect".equals(body.get("nodeType"))
                        && Map.of("mode", "solid", "r", 0, "g", 120, "b", 255, "brightness", 80)
                        .equals(body.get("params"))));
        List<Map<String, Object>> runs = service.listRuns(workflowId, deploymentId);
        assertEquals(1, runs.size());
        assertEquals("passed", runs.get(0).get("status"));
    }

    @Test
    void deploymentsAreIsolatedByBoundSourceDevice() {
        String workflowId = createWorkflow();
        String depOne = deploy(workflowId, "dev-a", "dev-b");
        String depTwo = deploy(workflowId, "dev-c", "dev-d");
        when(nodeTestService.executeOutputTest(anyString(), anyMap())).thenReturn(Map.of(
                "status", "sent",
                "sent", true
        ));

        listener.onEvent(new EventPayload(
                "input:buttons.pwr.short_press",
                "dev-a",
                "",
                "",
                "pwr",
                0,
                null,
                System.currentTimeMillis(),
                Map.of()
        ));

        verify(nodeTestService).executeOutputTest(eq("dev-b"), anyMap());
        verify(nodeTestService, never()).executeOutputTest(eq("dev-d"), anyMap());
        assertEquals(1, service.listRuns(workflowId, depOne).size());
        assertEquals(0, service.listRuns(workflowId, depTwo).size());
    }

    @Test
    void deleteDeploymentStopsEventExecution() {
        String workflowId = createWorkflow();
        String deploymentId = deploy(workflowId, "dev-a", "dev-b");
        service.deleteDeployment(workflowId, deploymentId);

        listener.onEvent(new EventPayload(
                "input:buttons.pwr.short_press",
                "dev-a",
                "",
                "",
                "pwr",
                0,
                null,
                System.currentTimeMillis(),
                Map.of()
        ));

        verify(nodeTestService, never()).executeOutputTest(anyString(), anyMap());
    }

    @Test
    void deployReplacesOlderDebugDeploymentWithSameTriggerBinding() {
        String firstWorkflowId = createWorkflow();
        String firstDeploymentId = deploy(firstWorkflowId, "dev-a", "dev-b");
        String secondWorkflowId = createWorkflow(Map.of("mode", "off", "off", true));
        Map<String, Object> secondDeployment = deployResult(secondWorkflowId, "dev-a", "dev-b");
        assertEquals(List.of(firstDeploymentId), secondDeployment.get("replacedDeployments"));

        when(nodeTestService.executeOutputTest(eq("dev-b"), anyMap())).thenReturn(Map.of(
                "status", "sent",
                "sent", true
        ));

        listener.onEvent(new EventPayload(
                "input:buttons.pwr.short_press",
                "dev-a",
                "",
                "",
                "pwr",
                0,
                null,
                System.currentTimeMillis(),
                Map.of()
        ));

        ArgumentCaptor<Map<String, Object>> bodyCaptor = ArgumentCaptor.forClass(Map.class);
        verify(nodeTestService).executeOutputTest(eq("dev-b"), bodyCaptor.capture());
        @SuppressWarnings("unchecked")
        Map<String, Object> params = (Map<String, Object>) bodyCaptor.getValue().get("params");
        assertEquals(true, params.get("off"));
        assertThrows(IllegalArgumentException.class, () -> service.listRuns(firstWorkflowId, firstDeploymentId));
    }

    @Test
    void testTriggerExecutesWithoutRealInputEvent() {
        String workflowId = createWorkflow();
        String deploymentId = deploy(workflowId, "dev-a", "dev-b");
        when(nodeTestService.executeOutputTest(eq("dev-b"), anyMap())).thenReturn(Map.of(
                "status", "sent",
                "sent", true
        ));

        Map<String, Object> run = service.testTrigger(workflowId, deploymentId, Map.of());

        assertEquals("passed", run.get("status"));
        verify(nodeTestService).executeOutputTest(eq("dev-b"), anyMap());
    }

    private String createWorkflow() {
        return createWorkflow(Map.of("mode", "solid", "r", 0, "g", 120, "b", 255, "brightness", 80));
    }

    private String createWorkflow(Map<String, Object> targetParams) {
        Map<String, Object> created = service.createWorkflow(new NodeWorkflowDefinition(
                null,
                "button-to-rgb",
                List.of(
                        new NodeWorkflowSlot("source", "type-a", "Source", List.of()),
                        new NodeWorkflowSlot("target", "type-a", "Target", List.of())
                ),
                List.of(
                        new NodeWorkflowNode("source_button", "source", "button.trigger",
                                Map.of("eventId", "input:buttons.pwr.short_press", "nodeId", "pwr")),
                        new NodeWorkflowNode("target_rgb", "target", "rgb.effect",
                                targetParams)
                ),
                List.of(new NodeWorkflowEdge("source_button", "target_rgb"))
        ));
        return (String) created.get("workflowId");
    }

    private String deploy(String workflowId, String sourceDevice, String targetDevice) {
        when(sessionManager.isDeviceOnline(anyString())).thenReturn(true);
        when(nodeCatalogService.buildForDevice(sourceDevice)).thenReturn(catalog(sourceDevice, "button.trigger"));
        when(nodeCatalogService.buildForDevice(targetDevice)).thenReturn(catalog(targetDevice, "rgb.effect"));
        Map<String, Object> deployed = deployResult(workflowId, sourceDevice, targetDevice);
        return (String) deployed.get("deploymentId");
    }

    private Map<String, Object> deployResult(String workflowId, String sourceDevice, String targetDevice) {
        when(sessionManager.isDeviceOnline(anyString())).thenReturn(true);
        when(nodeCatalogService.buildForDevice(sourceDevice)).thenReturn(catalog(sourceDevice, "button.trigger"));
        when(nodeCatalogService.buildForDevice(targetDevice)).thenReturn(catalog(targetDevice, "rgb.effect"));
        return service.deploy(workflowId, Map.of("slotBindings", Map.of(
                "source", sourceDevice,
                "target", targetDevice
        )));
    }

    private CapabilityNodeCatalog catalog(String deviceId, String... nodeTypes) {
        return new CapabilityNodeCatalog(
                deviceId,
                true,
                "ok",
                List.of(nodeTypes).stream().map(this::nodeDefinition).toList(),
                List.of()
        );
    }

    private CapabilityNodeDefinition nodeDefinition(String nodeType) {
        return new CapabilityNodeDefinition(
                nodeType,
                nodeType,
                nodeType,
                nodeType,
                "",
                "button.trigger".equals(nodeType)
                        ? CapabilityNodeRuntimeMode.TRIGGER
                        : CapabilityNodeRuntimeMode.ACTION,
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                Map.of(),
                Map.of()
        );
    }
}
