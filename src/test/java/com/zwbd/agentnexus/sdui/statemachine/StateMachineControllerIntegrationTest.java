package com.zwbd.agentnexus.sdui.statemachine;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.zwbd.agentnexus.sdui.dto.SduiControlDispatchResult;
import com.zwbd.agentnexus.sdui.service.CommandService;
import com.zwbd.agentnexus.sdui.statemachine.model.StateMachine;
import com.zwbd.agentnexus.sdui.statemachine.repo.StateMachineDeploymentRepository;
import com.zwbd.agentnexus.sdui.statemachine.repo.StateMachineRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.http.MediaType;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.test.web.servlet.MockMvc;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = "spring.ai.chat.memory.repository.jdbc.initialize-schema=never")
@AutoConfigureMockMvc
@Import(StateMachineControllerIntegrationTest.TestOverrides.class)
class StateMachineControllerIntegrationTest {

    private static final String DEVICE_ID = "1051DB398BD0";
    private static final String BOARD_TYPE = "ESP32-S3";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private StateMachineRepository stateMachineRepository;

    @Autowired
    private StateMachineDeploymentRepository deploymentRepository;

    @BeforeEach
    void setUp() {
        deploymentRepository.deleteAll();
        stateMachineRepository.deleteAll();
    }

    @Test
    void catalogValidateCreateAndDeployClosedLoop() throws Exception {
        // 1. Catalog is available
        Map<String, Object> catalog = apiData(mockMvc.perform(get("/api/v1/sdui/events/catalog"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString());
        assertTrue(catalog.containsKey("commands"));
        assertTrue(catalog.containsKey("sections"));

        // 2. Validate
        Map<String, Object> definitionBody = definitionBody(false);
        Map<String, Object> validateResponse = apiData(mockMvc.perform(post("/api/v1/sdui/state-machines/validate")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(definitionBody.get("definition"))))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString());
        assertEquals(true, validateResponse.get("valid"));

        // 3. Create (no status, boardTypes required)
        Map<String, Object> createResponse = apiData(mockMvc.perform(post("/api/v1/sdui/state-machines")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(definitionBody)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString());
        String smId = String.valueOf(createResponse.get("id"));
        assertNotNull(createResponse.get("boardTypes"));
        assertNotNull(createResponse.get("definition"));
        assertNull(createResponse.get("status")); // no status field
        assertFalse(stateMachineRepository.findAll().isEmpty());

        // 4. Deploy to device
        Map<String, Object> deployBody = Map.of("devices", List.of(DEVICE_ID));
        Map<String, Object> deployResponse = apiData(mockMvc.perform(post("/api/v1/sdui/state-machines/{id}/deployments", smId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(deployBody)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString());
        assertEquals(smId, deployResponse.get("stateMachineId"));
        assertEquals(List.of(DEVICE_ID), deployResponse.get("devices"));
        assertNotNull(deployResponse.get("deployedAt"));

        // 5. Verify deployment exists
        assertFalse(deploymentRepository.findAll().isEmpty());
    }

    @Test
    void triggerAdvancesDeploymentState() throws Exception {
        String smId = createAndDeployStateMachine(false);

        Map<String, Object> triggerResponse = apiData(mockMvc.perform(post("/api/v1/sdui/state-machines/{id}/trigger", smId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(eventBody("ui:action.click", DEVICE_ID))))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString());

        assertEquals("SUCCEEDED", triggerResponse.get("status"));
        assertEquals(true, triggerResponse.get("matched"));
        assertEquals("done", triggerResponse.get("currentStateId"));
        assertEquals(DEVICE_ID, triggerResponse.get("triggerDeviceId"));
        assertTrue(triggerResponse.containsKey("projectionResults"));
        assertTrue(triggerResponse.containsKey("transitionIds"));

        // Verify deployment state advanced
        var deployment = deploymentRepository.findAll().get(0);
        assertEquals("done", deployment.getCurrentStateId());
    }

    @Test
    void triggerWithoutMatchingTransitionReturnsUnmatched() throws Exception {
        String smId = createAndDeployStateMachine(false);

        Map<String, Object> triggerResponse = apiData(mockMvc.perform(post("/api/v1/sdui/state-machines/{id}/trigger", smId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(eventBody("ui:list.select", DEVICE_ID))))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString());

        assertEquals("SUCCEEDED", triggerResponse.get("status"));
        assertEquals(false, triggerResponse.get("matched"));
        assertEquals("no transition matched from state 'idle' for event 'ui:list.select'",
                triggerResponse.get("reason"));
    }

    @Test
    void deviceEventOnlyTriggersDeployedStateMachine() throws Exception {
        String boundSmId = createAndDeployStateMachine(false);
        String otherSmId = createAndDeployStateMachineWithDevice("other-device");

        List<Map<String, Object>> response = apiDataList(mockMvc.perform(post("/api/v1/sdui/state-machines/events/device")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(eventBody("ui:action.click", DEVICE_ID))))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString());

        assertEquals(1, response.size());
        assertEquals(boundSmId, response.get(0).get("stateMachineId"));

        // Only the bound state machine's deployment advanced
        var boundDeployment = deploymentRepository.findByStateMachineIdOrderByDeployedAtDesc(boundSmId).get(0);
        var otherDeployment = deploymentRepository.findByStateMachineIdOrderByDeployedAtDesc(otherSmId).get(0);
        assertEquals("done", boundDeployment.getCurrentStateId());
        assertEquals("idle", otherDeployment.getCurrentStateId());
    }

    @Test
    void commandDispatchWritesCommandResultsIntoOutput() throws Exception {
        String smId = createAndDeployStateMachine(true);

        Map<String, Object> triggerResponse = apiData(mockMvc.perform(post("/api/v1/sdui/state-machines/{id}/trigger", smId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(eventBody("ui:action.click", DEVICE_ID))))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString());

        List<Map<String, Object>> commandResults = list(triggerResponse.get("commandResults"));
        assertEquals(1, commandResults.size());
        assertEquals(DEVICE_ID, commandResults.get(0).get("deviceId"));
        assertEquals("cmd-1", commandResults.get(0).get("cmdId"));
        assertEquals("SENT", commandResults.get(0).get("status"));
    }

    @Test
    void listAndDeleteDeployments() throws Exception {
        String smId = createAndDeployStateMachine(false);

        // List deployments
        List<Map<String, Object>> deployments = apiDataList(mockMvc.perform(get("/api/v1/sdui/state-machines/{id}/deployments", smId))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString());
        assertEquals(1, deployments.size());
        String deployId = (String) deployments.get(0).get("id");

        // Delete deployment
        Map<String, Object> deleteResp = apiData(mockMvc.perform(delete("/api/v1/sdui/state-machines/{id}/deployments/{deployId}", smId, deployId))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString());
        assertEquals(true, deleteResp.get("deleted"));

        // Verify no deployments remain
        List<Map<String, Object>> afterDelete = apiDataList(mockMvc.perform(get("/api/v1/sdui/state-machines/{id}/deployments", smId))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString());
        assertTrue(afterDelete.isEmpty());
    }

    @Test
    void boardTypesRequiredAtCreation() throws Exception {
        Map<String, Object> body = Map.of(
                "name", "No Board Types",
                "definition", Map.of("states", List.of(), "transitions", List.of())
        );
        mockMvc.perform(post("/api/v1/sdui/state-machines")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(body)))
                .andExpect(status().isOk())
                .andExpect(result -> {
                    Map<String, Object> response = objectMapper.readValue(
                            result.getResponse().getContentAsString(), new TypeReference<>() {});
                    assertEquals(40000, response.get("code"));
                });
    }

    // ── Helpers ──

    private String createAndDeployStateMachine(boolean withCommandAction) throws Exception {
        return createAndDeployStateMachineWithDevice(DEVICE_ID, withCommandAction);
    }

    private String createAndDeployStateMachineWithDevice(String deviceId) throws Exception {
        return createAndDeployStateMachineWithDevice(deviceId, false);
    }

    private String createAndDeployStateMachineWithDevice(String deviceId, boolean withCommandAction) throws Exception {
        // Create state machine with boardTypes
        Map<String, Object> body = new LinkedHashMap<>(definitionBody(withCommandAction));
        Map<String, Object> createResponse = apiData(mockMvc.perform(post("/api/v1/sdui/state-machines")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(body)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString());
        String smId = String.valueOf(createResponse.get("id"));

        // Create deployment binding to device
        mockMvc.perform(post("/api/v1/sdui/state-machines/{id}/deployments", smId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("devices", List.of(deviceId)))))
                .andExpect(status().isOk());

        return smId;
    }

    private Map<String, Object> definitionBody(boolean withCommandAction) {
        List<Map<String, Object>> actions = new java.util.ArrayList<>();
        actions.add(Map.of("type", "context.set", "name", "clicked", "value", true));
        if (withCommandAction) {
            actions.add(Map.of(
                    "type", "command.dispatch",
                    "commandId", "rgb.effect.set",
                    "params", Map.of("mode", "solid", "r", 1, "g", 2, "b", 3)
            ));
        }

        return Map.of(
                "name", "API Closed Loop SM",
                "description", "integration test",
                "boardTypes", List.of(BOARD_TYPE),
                "definition", Map.of(
                        "states", List.of(
                                Map.of("id", "idle", "sections", List.of(
                                        Map.of("sectionId", "actions", "sectionType", "action_section",
                                                "fields", Map.of("actions", List.of(
                                                        Map.of("id", "ok", "label", "OK", "tone", "primary", "enabled", true)
                                                ))
                                        )
                                )),
                                Map.of("id", "done", "sections", List.of(
                                        Map.of("sectionId", "progress", "sectionType", "progress_section",
                                                "fields", Map.of("title", "Done", "progress", 100, "progressText", "100%")
                                        )
                                ))
                        ),
                        "transitions", List.of(Map.of(
                                "id", "click_to_done",
                                "fromStateId", "idle",
                                "toStateId", "done",
                                "event", Map.of("eventId", "ui:action.click"),
                                "actions", actions
                        ))
                ),
                "editorModel", Map.of()
        );
    }

    private Map<String, Object> eventBody(String eventId, String deviceId) {
        return Map.of(
                "event", Map.of(
                        "eventId", eventId,
                        "deviceId", deviceId,
                        "pageId", "main",
                        "sectionId", "actions",
                        "nodeId", "ok"
                )
        );
    }

    private String json(Object body) throws Exception {
        return objectMapper.writeValueAsString(body);
    }

    private Map<String, Object> apiData(String body) throws Exception {
        Map<String, Object> response = objectMapper.readValue(body, new TypeReference<>() {});
        assertEquals(20000, response.get("code"));
        return map(response.get("data"));
    }

    private List<Map<String, Object>> apiDataList(String body) throws Exception {
        Map<String, Object> response = objectMapper.readValue(body, new TypeReference<>() {});
        assertEquals(20000, response.get("code"));
        return list(response.get("data"));
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> map(Object value) {
        return (Map<String, Object>) value;
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> list(Object value) {
        return (List<Map<String, Object>>) value;
    }

    @TestConfiguration
    static class TestOverrides {

        @Bean
        @Primary
        CommandService commandService() {
            return new CommandService(null, null, null, null, new ObjectMapper()) {
                @Override
                public SduiControlDispatchResult dispatchCommand(String deviceId, String action, Object value) {
                    return new SduiControlDispatchResult("cmd-1", "rgb_set", null, true, "SENT");
                }
            };
        }

        @Bean
        @Primary
        StateMachineProjectionService projectionService() {
            return new StateMachineProjectionService(null, null) {
                @Override
                public List<Map<String, Object>> projectScene(List<Map<String, Object>> pages) {
                    List<Map<String, Object>> results = new java.util.ArrayList<>();
                    for (Map<String, Object> page : pages) {
                        for (String deviceId : devicesFromPage(page)) {
                            Map<String, Object> entry = new java.util.LinkedHashMap<>();
                            entry.put("type", "scene");
                            entry.put("deviceId", deviceId);
                            entry.put("sent", true);
                            entry.put("sections", 1);
                            results.add(entry);
                        }
                    }
                    return results;
                }

                @Override
                public List<Map<String, Object>> projectTransition(List<Map<String, Object>> previousPages,
                                                                   List<Map<String, Object>> currentPages) {
                    List<Map<String, Object>> results = new java.util.ArrayList<>();
                    for (Map<String, Object> page : currentPages) {
                        for (String deviceId : devicesFromPage(page)) {
                            Map<String, Object> entry = new java.util.LinkedHashMap<>();
                            entry.put("type", "patch");
                            entry.put("deviceId", deviceId);
                            entry.put("sent", true);
                            entry.put("patches", 1);
                            results.add(entry);
                        }
                    }
                    return results;
                }
            };
        }
    }
}
