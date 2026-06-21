package com.zwbd.agentnexus.sdui.statemachine;

import com.zwbd.agentnexus.common.web.ApiResponse;
import com.zwbd.agentnexus.sdui.event.EventPayload;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/sdui/state-machines")
@RequiredArgsConstructor
public class StateMachineController {

    private final StateMachineValidationService validationService;
    private final StateMachineService stateMachineService;

    // ── CRUD ──

    @GetMapping
    public ApiResponse<Object> list(@RequestParam(defaultValue = "0") int page,
                                     @RequestParam(defaultValue = "20") int size) {
        return ApiResponse.ok(stateMachineService.listDefinitions(page, size));
    }

    @PostMapping
    public ApiResponse<Map<String, Object>> create(@RequestBody Map<String, Object> body) {
        try {
            return ApiResponse.ok(stateMachineService.createDefinition(body));
        } catch (IllegalArgumentException e) {
            return ApiResponse.error(40000, e.getMessage());
        }
    }

    @GetMapping("/{id}")
    public ApiResponse<Map<String, Object>> get(@PathVariable String id) {
        try {
            return ApiResponse.ok(stateMachineService.getDefinition(id));
        } catch (IllegalArgumentException e) {
            return ApiResponse.error(40400, e.getMessage());
        }
    }

    @PutMapping("/{id}")
    public ApiResponse<Map<String, Object>> update(@PathVariable String id, @RequestBody Map<String, Object> body) {
        try {
            return ApiResponse.ok(stateMachineService.updateDefinition(id, body));
        } catch (IllegalArgumentException e) {
            return ApiResponse.error(40000, e.getMessage());
        }
    }

    @DeleteMapping("/{id}")
    public ApiResponse<Map<String, Object>> delete(@PathVariable String id) {
        try {
            return ApiResponse.ok(stateMachineService.deleteDefinition(id));
        } catch (IllegalArgumentException e) {
            return ApiResponse.error(40400, e.getMessage());
        }
    }

    // ── Validation ──

    @PostMapping("/validate")
    public ApiResponse<Map<String, Object>> validate(@RequestBody Map<String, Object> definition) {
        return ApiResponse.ok(stateMachineService.validate(definition));
    }

    // ── Deployment management ──

    @GetMapping("/{id}/deployments")
    public ApiResponse<Object> listDeployments(@PathVariable String id) {
        try {
            return ApiResponse.ok(stateMachineService.listDeployments(id));
        } catch (IllegalArgumentException e) {
            return ApiResponse.error(40400, e.getMessage());
        }
    }

    @PostMapping("/{id}/deployments")
    public ApiResponse<Map<String, Object>> createDeployment(@PathVariable String id,
                                                              @RequestBody Map<String, Object> body) {
        try {
            return ApiResponse.ok(stateMachineService.createDeployment(id, body));
        } catch (IllegalArgumentException e) {
            return ApiResponse.error(40000, e.getMessage());
        }
    }

    @DeleteMapping("/{id}/deployments/{deployId}")
    public ApiResponse<Map<String, Object>> deleteDeployment(@PathVariable String id,
                                                              @PathVariable String deployId) {
        try {
            return ApiResponse.ok(stateMachineService.deleteDeployment(id, deployId));
        } catch (IllegalArgumentException e) {
            return ApiResponse.error(40400, e.getMessage());
        }
    }

    // ── State-aware available events ──

    /**
     * Get events available as triggers FROM a given state.
     * Returns section interaction events (from this state's sections),
     * physical input events (from board types), system events, and
     * already-configured transitions.
     *
     * @param stateId the state to query (defaults to first state if omitted)
     */
    @GetMapping("/{id}/available-events")
    public ApiResponse<Map<String, Object>> getAvailableEvents(
            @PathVariable String id,
            @RequestParam(required = false) String stateId) {
        try {
            String targetStateId = stateId;
            if (targetStateId == null || targetStateId.isBlank()) {
                // Default to first state
                Map<String, Object> states = stateMachineService.listStates(id);
                @SuppressWarnings("unchecked")
                List<Map<String, Object>> statesList = states.get("states") instanceof List<?> list
                        ? list.stream().filter(m -> m instanceof Map<?, ?>).map(m -> (Map<String, Object>) m).toList()
                        : List.of();
                if (statesList.isEmpty()) {
                    return ApiResponse.error(40000, "state machine has no states");
                }
                targetStateId = string(statesList.get(0).get("id"));
            }
            return ApiResponse.ok(stateMachineService.getAvailableEvents(id, targetStateId));
        } catch (IllegalArgumentException e) {
            return ApiResponse.error(40400, e.getMessage());
        }
    }

    // ── Trigger  ──

    @PostMapping("/{id}/trigger")
    public ApiResponse<Map<String, Object>> trigger(@PathVariable String id, @RequestBody Map<String, Object> body) {
        try {
            return ApiResponse.ok(stateMachineService.trigger(id, body));
        } catch (IllegalArgumentException | IllegalStateException e) {
            return ApiResponse.error(40000, e.getMessage());
        }
    }

    @PostMapping("/events/device")
    public ApiResponse<Object> deviceEvent(@RequestBody Map<String, Object> body) {
        Map<String, Object> event = body.get("event") instanceof Map<?, ?> map
                ? normalize(map) : body;
        String deviceId = string(event.get("deviceId"));
        String eventId = string(event.get("eventId"));
        if (deviceId.isBlank() || eventId.isBlank()) {
            return ApiResponse.error(40000, "event.deviceId and event.eventId are required");
        }
        return ApiResponse.ok(stateMachineService.handleDeviceEvent(
                EventPayload.fromLegacyMap(deviceId, eventId, event)));
    }

    // ── State sub-resources ──

    @PostMapping("/{id}/states")
    public ApiResponse<Map<String, Object>> addState(@PathVariable String id,
                                                      @RequestBody Map<String, Object> body) {
        try {
            return ApiResponse.ok(stateMachineService.addState(id, body));
        } catch (IllegalArgumentException e) {
            return ApiResponse.error(40000, e.getMessage());
        }
    }

    @GetMapping("/{id}/states")
    public ApiResponse<Map<String, Object>> listStates(@PathVariable String id,
                                                        @RequestParam(required = false) String at) {
        try {
            if (at != null && !at.isBlank()) {
                return ApiResponse.ok(stateMachineService.getStateAtTransition(id, at));
            }
            return ApiResponse.ok(stateMachineService.listStates(id));
        } catch (IllegalArgumentException e) {
            return ApiResponse.error(40400, e.getMessage());
        }
    }

    @GetMapping("/{id}/states/{stateId}")
    public ApiResponse<Map<String, Object>> getState(@PathVariable String id,
                                                      @PathVariable String stateId) {
        try {
            return ApiResponse.ok(stateMachineService.getState(id, stateId));
        } catch (IllegalArgumentException e) {
            return ApiResponse.error(40400, e.getMessage());
        }
    }

    @PutMapping("/{id}/states/{stateId}")
    public ApiResponse<Map<String, Object>> updateState(@PathVariable String id,
                                                         @PathVariable String stateId,
                                                         @RequestBody Map<String, Object> body) {
        try {
            return ApiResponse.ok(stateMachineService.updateState(id, stateId, body));
        } catch (IllegalArgumentException e) {
            return ApiResponse.error(40000, e.getMessage());
        }
    }

    @DeleteMapping("/{id}/states/{stateId}")
    public ApiResponse<Map<String, Object>> deleteState(@PathVariable String id,
                                                         @PathVariable String stateId) {
        try {
            return ApiResponse.ok(stateMachineService.deleteState(id, stateId));
        } catch (IllegalArgumentException e) {
            return ApiResponse.error(40400, e.getMessage());
        }
    }

    // ── Transition sub-resources ──

    @PostMapping("/{id}/transitions")
    public ApiResponse<Map<String, Object>> addTransition(@PathVariable String id,
                                                           @RequestBody Map<String, Object> body) {
        try {
            return ApiResponse.ok(stateMachineService.addTransition(id, body));
        } catch (IllegalArgumentException e) {
            return ApiResponse.error(40000, e.getMessage());
        }
    }

    @GetMapping("/{id}/transitions")
    public ApiResponse<Map<String, Object>> listTransitions(@PathVariable String id) {
        try {
            return ApiResponse.ok(stateMachineService.listTransitions(id));
        } catch (IllegalArgumentException e) {
            return ApiResponse.error(40400, e.getMessage());
        }
    }

    @GetMapping("/{id}/transitions/{transId}")
    public ApiResponse<Map<String, Object>> getTransition(@PathVariable String id,
                                                           @PathVariable String transId) {
        try {
            return ApiResponse.ok(stateMachineService.getTransition(id, transId));
        } catch (IllegalArgumentException e) {
            return ApiResponse.error(40400, e.getMessage());
        }
    }

    @PutMapping("/{id}/transitions/{transId}")
    public ApiResponse<Map<String, Object>> updateTransition(@PathVariable String id,
                                                              @PathVariable String transId,
                                                              @RequestBody Map<String, Object> body) {
        try {
            return ApiResponse.ok(stateMachineService.updateTransition(id, transId, body));
        } catch (IllegalArgumentException e) {
            return ApiResponse.error(40000, e.getMessage());
        }
    }

    @DeleteMapping("/{id}/transitions/{transId}")
    public ApiResponse<Map<String, Object>> deleteTransition(@PathVariable String id,
                                                              @PathVariable String transId) {
        try {
            return ApiResponse.ok(stateMachineService.deleteTransition(id, transId));
        } catch (IllegalArgumentException e) {
            return ApiResponse.error(40400, e.getMessage());
        }
    }

    @PutMapping("/{id}/transitions/reorder")
    public ApiResponse<Map<String, Object>> reorderTransitions(@PathVariable String id,
                                                                @RequestBody Map<String, Object> body) {
        try {
            @SuppressWarnings("unchecked")
            List<String> transitionIds = body.get("transitionIds") instanceof List<?> list
                    ? list.stream().map(Object::toString).toList() : List.of();
            return ApiResponse.ok(stateMachineService.reorderTransitions(id, transitionIds));
        } catch (IllegalArgumentException e) {
            return ApiResponse.error(40000, e.getMessage());
        }
    }

    // ── Helpers ──

    private Map<String, Object> normalize(Map<?, ?> raw) {
        Map<String, Object> result = new java.util.LinkedHashMap<>();
        raw.forEach((key, value) -> result.put(String.valueOf(key), value));
        return result;
    }

    private String string(Object value) {
        return value == null ? "" : String.valueOf(value);
    }
}
