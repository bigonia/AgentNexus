package com.zwbd.agentnexus.sdui.statemachine;

import com.zwbd.agentnexus.sdui.event.EventCatalogLoader;
import com.zwbd.agentnexus.sdui.event.EventRegistry;
import com.zwbd.agentnexus.sdui.section.SectionDataCodec;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StateMachineValidationServiceTest {

    @Test
    void validatesTransitionEventsAndActionsAgainstEventRegistry() {
        StateMachineValidationService service = new StateMachineValidationService(registry());

        Map<String, Object> result = service.validate(Map.of(
                "states", List.of(Map.of(
                        "id", "idle",
                        "sections", List.of(Map.of(
                                "sectionId", "actions",
                                "sectionType", "action_section",
                                "fields", Map.of("actions", List.of())
                        ))
                )),
                "transitions", List.of(Map.of(
                        "id", "click-to-rgb",
                        "event", Map.of("eventId", "ui:action.click"),
                        "actions", List.of(Map.of(
                                "type", "command.dispatch",
                                "commandId", "rgb.effect.set",
                                "params", Map.of("r", 1, "g", 2, "b", 3)
                        ))
                ))
        ));

        assertTrue((Boolean) result.get("valid"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void rejectsUnknownEventsAndOutOfRangeCommandParams() {
        StateMachineValidationService service = new StateMachineValidationService(registry());

        Map<String, Object> result = service.validate(Map.of(
                "states", List.of(Map.of("id", "idle", "sections", List.of())),
                "transitions", List.of(Map.of(
                        "id", "bad",
                        "event", Map.of("eventId", "ui:missing"),
                        "actions", List.of(Map.of(
                                "type", "command.dispatch",
                                "commandId", "rgb.effect.set",
                                "params", Map.of("r", 999, "g", 0, "b", 0)
                        ))
                ))
        ));

        List<Map<String, Object>> errors = (List<Map<String, Object>>) result.get("errors");
        assertFalse((Boolean) result.get("valid"));
        assertTrue(errors.stream().anyMatch(e -> "UNKNOWN_INBOUND_EVENT".equals(e.get("code"))));
        assertTrue(errors.stream().anyMatch(e -> "VALUE_OUT_OF_RANGE".equals(e.get("code"))));
        assertTrue(errors.stream().anyMatch(e -> ((String) e.get("path")).contains("transitions[bad]")));
        assertTrue(errors.stream().anyMatch(e -> ((String) e.get("path")).contains(".params.r")));
    }

    @Test
    @SuppressWarnings("unchecked")
    void rejectsTransitionsPointingToNonexistentStates() {
        StateMachineValidationService service = new StateMachineValidationService(registry());

        Map<String, Object> result = service.validate(Map.of(
                "states", List.of(Map.of("id", "idle", "sections", List.of())),
                "transitions", List.of(Map.of(
                        "id", "bad_ref",
                        "fromStateId", "nonexistent",
                        "toStateId", "also_fake",
                        "event", Map.of("eventId", "ui:action.click"),
                        "actions", List.of()
                ))
        ));

        List<Map<String, Object>> errors = (List<Map<String, Object>>) result.get("errors");
        assertFalse((Boolean) result.get("valid"));
        assertTrue(errors.stream().anyMatch(e -> "INVALID_REFERENCE".equals(e.get("code")) &&
                ((String) e.get("path")).contains("fromStateId")));
        assertTrue(errors.stream().anyMatch(e -> "INVALID_REFERENCE".equals(e.get("code")) &&
                ((String) e.get("path")).contains("toStateId")));
    }

    @Test
    @SuppressWarnings("unchecked")
    void rejectsDuplicateStateAndTransitionIds() {
        StateMachineValidationService service = new StateMachineValidationService(registry());

        Map<String, Object> result = service.validate(Map.of(
                "states", List.of(
                        Map.of("id", "idle", "sections", List.of()),
                        Map.of("id", "idle", "sections", List.of())
                ),
                "transitions", List.of(
                        Map.of("id", "dup", "event", Map.of("eventId", "ui:action.click"), "actions", List.of()),
                        Map.of("id", "dup", "event", Map.of("eventId", "ui:action.click"), "actions", List.of())
                )
        ));

        List<Map<String, Object>> errors = (List<Map<String, Object>>) result.get("errors");
        assertFalse((Boolean) result.get("valid"));
        long duplicateErrors = errors.stream()
                .filter(e -> "DUPLICATE_ID".equals(e.get("code")))
                .count();
        assertTrue(duplicateErrors >= 2, "expected at least 2 duplicate ID errors, got " + duplicateErrors);
    }

    @Test
    @SuppressWarnings("unchecked")
    void validatesSectionRemoveAndContextSetActions() {
        StateMachineValidationService service = new StateMachineValidationService(registry());

        Map<String, Object> result = service.validate(Map.of(
                "states", List.of(Map.of("id", "idle", "sections", List.of())),
                "transitions", List.of(Map.of(
                        "id", "missing_fields",
                        "event", Map.of("eventId", "command.dispatch"),
                        "actions", List.of(
                                Map.of("type", "section.remove"),
                                Map.of("type", "context.set")
                        )
                ))
        ));

        List<Map<String, Object>> errors = (List<Map<String, Object>>) result.get("errors");
        assertFalse((Boolean) result.get("valid"));
        assertTrue(errors.stream().anyMatch(e -> "MISSING_REQUIRED_FIELD".equals(e.get("code")) &&
                ((String) e.get("path")).contains("sectionId")));
        assertTrue(errors.stream().anyMatch(e -> "MISSING_REQUIRED_FIELD".equals(e.get("code")) &&
                ((String) e.get("path")).contains(".name")));
    }

    private EventRegistry registry() {
        EventCatalogLoader loader = new EventCatalogLoader(new SectionDataCodec());
        loader.load();
        return new EventRegistry(loader);
    }
}
