package com.zwbd.agentnexus.sdui.statemachine;

import com.zwbd.agentnexus.sdui.dto.SduiControlDispatchResult;
import com.zwbd.agentnexus.sdui.event.EventCatalogLoader;
import com.zwbd.agentnexus.sdui.event.EventRegistry;
import com.zwbd.agentnexus.sdui.event.EventPayload;
import com.zwbd.agentnexus.sdui.section.SectionDataCodec;
import com.zwbd.agentnexus.sdui.service.CommandService;
import com.zwbd.agentnexus.sdui.statemachine.model.StateMachine;
import com.zwbd.agentnexus.sdui.statemachine.model.StateMachineDeployment;
import com.zwbd.agentnexus.sdui.statemachine.repo.StateMachineDeploymentRepository;
import com.zwbd.agentnexus.sdui.statemachine.repo.StateMachineRepository;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StateMachineServiceRuntimeTest {

    @Test
    void resolvesRuntimeVariables() {
        StateMachineDeployment deployment = deployment("device-a");
        StateMachineService service = service(stateMachine(), deployment,
                new CapturingCommandService(), new CapturingProjectionService());

        Map<String, Object> runtime = service.runtimeContext(deployment, Map.of(
                "eventId", "ui:action.click",
                "deviceId", "device-a",
                "nodeId", "ok"
        ));
        Map<String, Object> resolved = service.resolveValues(Map.of(
                "params", Map.of("text", "$event.nodeId", "missing", "$context.missing")
        ), runtime);

        assertEquals("device-a", runtime.get("triggerDeviceId"));
        assertEquals("ok", ((Map<?, ?>) resolved.get("params")).get("text"));
        assertEquals(null, ((Map<?, ?>) resolved.get("params")).get("missing"));
    }

    @Test
    void dispatchesCommandActionToDeploymentDevices() {
        StateMachine sm = stateMachine();
        StateMachineDeployment deployment = deployment("device-a");
        CapturingCommandService commandService = new CapturingCommandService();
        CapturingProjectionService projectionService = new CapturingProjectionService();

        StateMachineService service = service(sm, deployment, commandService, projectionService);
        Map<String, Object> run = service.trigger("sm-1", Map.of(
                "event", Map.of("eventId", "ui:action.click", "deviceId", "device-a", "nodeId", "ok")
        ));

        assertEquals("SUCCEEDED", run.get("status"));
        assertEquals("device-a", commandService.deviceId);
        assertEquals("rgb.effect.set", commandService.commandId);
        assertTrue(projectionService.calls > 0);
    }

    @Test
    void deviceEventOnlyTriggersStateMachinesWithMatchingDeployment() {
        StateMachine sm = stateMachine();
        StateMachineDeployment deployment = deployment("device-a");
        StateMachineService service = service(sm, deployment,
                new CapturingCommandService(), new CapturingProjectionService());

        // Other device — no matching deployment
        List<Map<String, Object>> unboundRuns = service.handleDeviceEvent(
                EventPayload.minimal("ui:action.click", "other-device"));
        // Bound device — should match
        List<Map<String, Object>> boundRuns = service.handleDeviceEvent(
                EventPayload.minimal("ui:action.click", "device-a"));

        assertTrue(unboundRuns.isEmpty());
        assertEquals(1, boundRuns.size());
    }

    private StateMachineService service(StateMachine sm,
                                        StateMachineDeployment deployment,
                                        CommandService commandService,
                                        StateMachineProjectionService projectionService) {
        StateMachineRepository smRepo = proxy(StateMachineRepository.class, (method, args) -> {
            if ("findById".equals(method) && args.length > 0 && "sm-1".equals(args[0]))
                return Optional.of(sm);
            if ("save".equals(method)) return args[0];
            return defaultValue(method);
        });

        StateMachineDeploymentRepository depRepo = proxy(StateMachineDeploymentRepository.class, (method, args) -> {
            if ("findByDeviceId".equals(method) && args.length > 0) {
                if ("device-a".equals(args[0])) return List.of(deployment);
                return List.of();
            }
            if ("findByStateMachineIdOrderByDeployedAtDesc".equals(method)) {
                return List.of(deployment);
            }
            if ("save".equals(method)) return args[0];
            if ("findAll".equals(method)) return List.of(deployment);
            return defaultValue(method);
        });

        EventCatalogLoader loader = new EventCatalogLoader(new SectionDataCodec());
        loader.load();
        EventRegistry registry = new EventRegistry(loader);

        StateMachineValidationService validationService = new StateMachineValidationService(registry);
        SectionDataCodec codec = new SectionDataCodec();

        return new StateMachineService(
                smRepo, depRepo, validationService, registry,
                commandService, projectionService, codec, null, null, null);
    }

    @SuppressWarnings("unchecked")
    private <T> T proxy(Class<T> type, RepositoryInvocation invocation) {
        return (T) Proxy.newProxyInstance(
                type.getClassLoader(), new Class<?>[]{type},
                (proxy, method, args) -> invocation.invoke(method.getName(), args != null ? args : new Object[0]));
    }

    private Object defaultValue(Object method) {
        String name = method.toString();
        if (name.contains("toString")) return "test-proxy";
        if (name.contains("hashCode")) return 1;
        if (name.contains("equals")) return false;
        return null;
    }

    private StateMachine stateMachine() {
        StateMachine sm = new StateMachine();
        sm.setId("sm-1");
        sm.setName("runtime test");
        sm.setBoardTypes(List.of("ESP32-S3"));
        sm.setDefinition(Map.of(
                "states", List.of(
                        Map.of("id", "idle", "sections", List.of()),
                        Map.of("id", "done", "sections", List.of())
                ),
                "transitions", List.of(Map.of(
                        "id", "click-to-done",
                        "fromStateId", "idle",
                        "toStateId", "done",
                        "event", Map.of("eventId", "ui:action.click"),
                        "actions", List.of(Map.of(
                                "type", "command.dispatch",
                                "commandId", "rgb.effect.set",
                                "params", Map.of("r", 1, "g", 2, "b", 3)
                        ))
                ))
        ));
        return sm;
    }

    private StateMachineDeployment deployment(String deviceId) {
        StateMachineDeployment d = new StateMachineDeployment();
        d.setId("dep-1");
        d.setStateMachineId("sm-1");
        d.setDevices(new ArrayList<>(List.of(deviceId)));
        d.setCurrentStateId("idle");
        d.setContextData(new LinkedHashMap<>());
        return d;
    }

    private interface RepositoryInvocation {
        Object invoke(String method, Object[] args);
    }

    private static class CapturingCommandService extends CommandService {
        private String deviceId;
        private String commandId;

        CapturingCommandService() { super(null, null, null, null, null); }

        @Override
        public SduiControlDispatchResult dispatchCommand(String deviceId, String action, Object value) {
            this.deviceId = deviceId;
            this.commandId = action;
            return new SduiControlDispatchResult("cmd-1", "rgb_set", null, true, "SENT");
        }
    }

    private static class CapturingProjectionService extends StateMachineProjectionService {
        private int calls;

        CapturingProjectionService() { super(null, null); }

        @Override
        public List<Map<String, Object>> projectTransition(List<Map<String, Object>> previousPages,
                                                           List<Map<String, Object>> currentPages) {
            calls++;
            return List.of(Map.of("sent", true));
        }

        @Override
        public List<Map<String, Object>> projectScene(List<Map<String, Object>> pages) {
            calls++;
            return List.of(Map.of("sent", true));
        }
    }
}
