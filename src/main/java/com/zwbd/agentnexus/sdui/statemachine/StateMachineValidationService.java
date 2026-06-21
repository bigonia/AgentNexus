package com.zwbd.agentnexus.sdui.statemachine;

import com.zwbd.agentnexus.sdui.event.EventRegistry;
import com.zwbd.agentnexus.sdui.event.EventRegistry.ValidationError;
import com.zwbd.agentnexus.sdui.event.EventRegistry.ValidationResult;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Service
@RequiredArgsConstructor
public class StateMachineValidationService {

    private final EventRegistry eventRegistry;

    // ═══════════════════════════════════════════════════════════
    // Holistic validation (existing — zero behavioral change)
    // ═══════════════════════════════════════════════════════════

    public Map<String, Object> validate(Map<String, Object> definition) {
        List<ValidationError> errors = new ArrayList<>();
        Set<String> stateIds = collectStateIds(definition, errors);
        validateStates(definition, stateIds, errors);
        validateTransitions(definition, stateIds, errors);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("valid", errors.isEmpty());
        result.put("errors", errors.stream().map(ValidationError::toMap).toList());
        return result;
    }

    // ═══════════════════════════════════════════════════════════
    // Incremental validation (new public methods)
    // ═══════════════════════════════════════════════════════════

    /**
     * Validate a single state entry for incremental add/update operations.
     */
    public Map<String, Object> validateStateEntry(Map<String, Object> stateDef) {
        List<ValidationError> errors = new ArrayList<>();
        String stateId = string(stateDef.get("id"));
        String statePath = "state[" + stateId + "]";

        if (stateId.isBlank()) {
            errors.add(err("state.id", "MISSING_REQUIRED_FIELD", "state id is required"));
        }

        // Validate pages/sections
        Object rawPages = stateDef.get("pages");
        if (rawPages instanceof List<?> pages && !pages.isEmpty()) {
            validatePages(pages, statePath, errors);
        } else {
            // Legacy single-page format: sections at state level
            Object rawSections = stateDef.get("sections");
            if (rawSections instanceof List<?> sections) {
                validateSectionList(sections, statePath + ".sections", errors);
            } else {
                errors.add(err(statePath, "MISSING_REQUIRED_FIELD",
                        "state must have pages or sections"));
            }
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("valid", errors.isEmpty());
        result.put("errors", errors.stream().map(ValidationError::toMap).toList());
        return result;
    }

    /**
     * Validate a single transition entry for incremental add/update operations.
     * <p>
     * Unlike holistic validation, this does NOT require {@code toStateId} to reference
     * an existing state — the target state can be auto-created by the derivation engine.
     *
     * @param transitionDef    the transition definition map
     * @param existingStateIds the set of currently existing state IDs
     */
    public Map<String, Object> validateTransitionEntry(Map<String, Object> transitionDef,
                                                        Set<String> existingStateIds) {
        List<ValidationError> errors = new ArrayList<>();
        String transId = string(transitionDef.get("id"));
        String transPath = "transition[" + transId + "]";

        validateSingleTransition(transitionDef, transPath, existingStateIds, true, errors);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("valid", errors.isEmpty());
        result.put("errors", errors.stream().map(ValidationError::toMap).toList());
        return result;
    }

    /**
     * Pre-derivation validation: checks that section.update and section.remove actions
     * target sections that actually exist in the fromState. Returns non-fatal warnings.
     *
     * @param transitionDef the transition containing actions
     * @param fromStateDef  the source state definition (with pages/sections)
     */
    public Map<String, Object> validateTransitionForDerivation(Map<String, Object> transitionDef,
                                                                Map<String, Object> fromStateDef) {
        List<ValidationError> warnings = new ArrayList<>();
        Set<String> fromSectionIds = collectSectionIds(fromStateDef);

        Object rawActions = transitionDef.get("actions");
        if (!(rawActions instanceof List<?> actions)) {
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("valid", true);
            result.put("warnings", List.of());
            return result;
        }

        int actionIndex = 0;
        for (Object rawAction : actions) {
            if (!(rawAction instanceof Map<?, ?> actionMap)) {
                actionIndex++;
                continue;
            }
            Map<String, Object> action = normalize(actionMap);
            String type = string(action.get("type"));
            String sectionId = string(action.get("sectionId"));

            if (!sectionId.isBlank()
                    && ("section.update".equals(type) || "section.remove".equals(type))
                    && !fromSectionIds.contains(sectionId)) {
                warnings.add(err("actions[" + actionIndex + "].sectionId", "SECTION_NOT_FOUND",
                        "section '" + sectionId + "' not found in fromState '"
                                + string(fromStateDef.get("id"))
                                + "'; " + type + " will have no effect during derivation"));
            }
            actionIndex++;
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("valid", warnings.isEmpty());  // valid=true means no warnings
        result.put("warnings", warnings.stream().map(ValidationError::toMap).toList());
        return result;
    }

    // ═══════════════════════════════════════════════════════════
    // First pass: collect state IDs
    // ═══════════════════════════════════════════════════════════

    @SuppressWarnings("unchecked")
    private Set<String> collectStateIds(Map<String, Object> definition, List<ValidationError> errors) {
        Set<String> ids = new LinkedHashSet<>();
        Object rawStates = definition.get("states");
        if (!(rawStates instanceof List<?> states)) {
            return ids;
        }
        for (Object rawState : states) {
            if (!(rawState instanceof Map<?, ?> stateMap)) {
                continue;
            }
            String stateId = string(stateMap.get("id"));
            if (stateId.isBlank()) {
                continue;
            }
            if (!ids.add(stateId)) {
                errors.add(err("definition.states[" + stateId + "]", "DUPLICATE_ID",
                        "duplicate state id: " + stateId));
            }
        }
        return ids;
    }

    // ═══════════════════════════════════════════════════════════
    // State validation (holistic → delegates to per-state)
    // ═══════════════════════════════════════════════════════════

    @SuppressWarnings("unchecked")
    private void validateStates(Map<String, Object> definition, Set<String> stateIds,
                                List<ValidationError> errors) {
        Object rawStates = definition.get("states");
        if (!(rawStates instanceof List<?> states) || states.isEmpty()) {
            errors.add(err("definition.states", "MISSING_REQUIRED_FIELD", "states is required"));
            return;
        }
        int stateIndex = 0;
        for (Object rawState : states) {
            if (!(rawState instanceof Map<?, ?> stateMap)) {
                errors.add(err("definition.states[" + stateIndex + "]", "TYPE_MISMATCH",
                        "state must be an object"));
                stateIndex++;
                continue;
            }
            Map<String, Object> state = normalize(stateMap);
            String stateId = string(state.get("id"));
            String statePath = "definition.states[" + stateId + "]";
            stateIndex++;

            if (stateId.isBlank()) {
                errors.add(err(statePath, "MISSING_REQUIRED_FIELD",
                        "state[" + (stateIndex - 1) + "] id is required"));
            }

            validateSingleStateSections(state, statePath, errors);
        }
    }

    /**
     * Validate the sections within a single state (extracted from the validateStates loop).
     * Supports both new multi-page format (pages[].sections) and legacy format (sections).
     */
    @SuppressWarnings("unchecked")
    private void validateSingleStateSections(Map<String, Object> state, String statePath,
                                              List<ValidationError> errors) {
        // New multi-page format
        Object rawPages = state.get("pages");
        if (rawPages instanceof List<?> pages && !pages.isEmpty()) {
            validatePages(pages, statePath, errors);
            return;
        }

        // Legacy format: sections at state level
        Object rawSections = state.get("sections");
        if (!(rawSections instanceof List<?> sections)) {
            return;
        }
        validateSectionList(sections, statePath + ".sections", errors);
    }

    /**
     * Validate a pages array from a state definition.
     */
    @SuppressWarnings("unchecked")
    private void validatePages(List<?> pages, String statePath, List<ValidationError> errors) {
        int pageIndex = 0;
        for (Object rawPage : pages) {
            if (!(rawPage instanceof Map<?, ?> pageMap)) {
                errors.add(err(statePath + ".pages[" + pageIndex + "]", "TYPE_MISMATCH",
                        "page must be an object"));
                pageIndex++;
                continue;
            }
            Map<String, Object> page = normalize(pageMap);
            String pagePath = statePath + ".pages[" + pageIndex + "]";
            pageIndex++;

            Object rawSections = page.get("sections");
            if (rawSections instanceof List<?> sections) {
                validateSectionList(sections, pagePath + ".sections", errors);
            }
        }
    }

    /**
     * Validate a list of sections — shared by both pages-based and legacy formats.
     */
    @SuppressWarnings("unchecked")
    private void validateSectionList(List<?> sections, String listPath, List<ValidationError> errors) {
        int sectionIndex = 0;
        for (Object rawSection : sections) {
            if (!(rawSection instanceof Map<?, ?> sectionMap)) {
                errors.add(err(listPath + "[" + sectionIndex + "]", "TYPE_MISMATCH",
                        "section must be an object"));
                sectionIndex++;
                continue;
            }
            Map<String, Object> section = normalize(sectionMap);
            String sectionType = string(section.get("sectionType"));
            String sectionPath = listPath + "[" + sectionIndex + "]";
            sectionIndex++;

            if (!eventRegistry.isKnownSectionType(sectionType)) {
                errors.add(err(sectionPath + ".sectionType", "UNKNOWN_SECTION_TYPE",
                        "unknown section type: " + sectionType));
                continue;
            }
            Map<String, Object> fields = section.get("fields") instanceof Map<?, ?> fieldsMap
                    ? normalize(fieldsMap) : Map.of();
            EventRegistry.ValidationResult validation = eventRegistry.validateSectionFields(sectionType, fields);
            for (ValidationError ve : validation.errors()) {
                errors.add(err(sectionPath + "." + ve.path(), ve.code(), ve.message()));
            }
        }
    }

    // ═══════════════════════════════════════════════════════════
    // Transition validation (holistic → delegates to per-transition)
    // ═══════════════════════════════════════════════════════════

    @SuppressWarnings("unchecked")
    private void validateTransitions(Map<String, Object> definition, Set<String> stateIds,
                                     List<ValidationError> errors) {
        Object rawTransitions = definition.get("transitions");
        if (!(rawTransitions instanceof List<?> transitions)) {
            errors.add(err("definition.transitions", "MISSING_REQUIRED_FIELD", "transitions is required"));
            return;
        }
        Set<String> seenTransitionIds = new LinkedHashSet<>();
        int transitionIndex = 0;
        for (Object rawTransition : transitions) {
            if (!(rawTransition instanceof Map<?, ?> transitionMap)) {
                errors.add(err("definition.transitions[" + transitionIndex + "]", "TYPE_MISMATCH",
                        "transition must be an object"));
                transitionIndex++;
                continue;
            }
            Map<String, Object> transition = normalize(transitionMap);
            String transitionId = string(transition.get("id"));
            String transPath = "definition.transitions[" + transitionId + "]";
            transitionIndex++;

            // Duplicate transition ID
            if (!transitionId.isBlank() && !seenTransitionIds.add(transitionId)) {
                errors.add(err(transPath, "DUPLICATE_ID", "duplicate transition id: " + transitionId));
            }

            // Holistic mode: toStateId must reference an existing state
            validateSingleTransition(transition, transPath, stateIds, false, errors);
        }
    }

    /**
     * Validate a single transition — shared by holistic and incremental validation.
     *
     * @param transition            the transition map
     * @param transPath             error path prefix
     * @param existingStateIds      known state IDs for referential checks
     * @param allowMissingToStateId if true, toStateId may reference a not-yet-created state (auto-derivation)
     * @param errors                list to append errors to
     */
    @SuppressWarnings("unchecked")
    private void validateSingleTransition(Map<String, Object> transition, String transPath,
                                           Set<String> existingStateIds,
                                           boolean allowMissingToStateId,
                                           List<ValidationError> errors) {
        // fromStateId referential integrity
        String fromStateId = string(transition.get("fromStateId"));
        if (!fromStateId.isBlank() && !existingStateIds.contains(fromStateId)) {
            errors.add(err(transPath + ".fromStateId", "INVALID_REFERENCE",
                    "fromStateId '" + fromStateId + "' does not match any defined state"));
        }

        // toStateId referential integrity — conditional for incremental API
        String toStateId = string(transition.get("toStateId"));
        if (!allowMissingToStateId && !toStateId.isBlank() && !existingStateIds.contains(toStateId)) {
            errors.add(err(transPath + ".toStateId", "INVALID_REFERENCE",
                    "toStateId '" + toStateId + "' does not match any defined state"));
        }

        // priority type check
        Object rawPriority = transition.get("priority");
        if (rawPriority != null) {
            try {
                Integer.parseInt(String.valueOf(rawPriority));
            } catch (NumberFormatException e) {
                errors.add(err(transPath + ".priority", "TYPE_MISMATCH",
                        "priority must be an integer"));
            }
        }

        // Validate event
        validateTransitionEvent(transPath, transition.get("event"), errors);

        // Validate actions
        Object rawActions = transition.get("actions");
        if (!(rawActions instanceof List<?> actions)) {
            return;
        }
        String transitionId = string(transition.get("id"));
        int actionIndex = 0;
        for (Object rawAction : actions) {
            if (!(rawAction instanceof Map<?, ?> actionMap)) {
                errors.add(err(transPath + ".actions[" + actionIndex + "]", "TYPE_MISMATCH",
                        "transition " + transitionId + " action must be an object"));
                actionIndex++;
                continue;
            }
            validateAction(transPath, transitionId, actionIndex, normalize(actionMap), errors);
            actionIndex++;
        }
    }

    // ═══════════════════════════════════════════════════════════
    // Event validation
    // ═══════════════════════════════════════════════════════════

    @SuppressWarnings("unchecked")
    private void validateTransitionEvent(String transPath, Object rawEvent, List<ValidationError> errors) {
        String eventPath = transPath + ".event";
        if (!(rawEvent instanceof Map<?, ?> eventMap)) {
            errors.add(err(eventPath, "MISSING_REQUIRED_FIELD", "event is required"));
            return;
        }
        String eventId = string(eventMap.get("eventId"));
        if (eventRegistry.getSectionEvent(eventId).isEmpty()
                && eventRegistry.getCommandEvent(eventId).filter(def -> def.direction().name().equals("INBOUND")).isEmpty()) {
            errors.add(err(eventPath + ".eventId", "UNKNOWN_INBOUND_EVENT",
                    "unknown inbound event: " + eventId));
        }
    }

    // ═══════════════════════════════════════════════════════════
    // Action validation (unchanged)
    // ═══════════════════════════════════════════════════════════

    @SuppressWarnings("unchecked")
    private void validateAction(String transPath, String transitionId, int actionIndex,
                                Map<String, Object> action, List<ValidationError> errors) {
        String actionPath = transPath + ".actions[" + actionIndex + "]";
        String type = string(action.get("type"));
        switch (type) {
            case "command.dispatch", "platform.capability" -> {
                String commandId = string(action.get("commandId"));
                if (commandId.isBlank()) {
                    errors.add(err(actionPath + ".commandId", "MISSING_REQUIRED_FIELD",
                            "transition " + transitionId + ": commandId is required for " + type));
                } else {
                    Map<String, Object> params = action.get("params") instanceof Map<?, ?> paramsMap
                            ? normalize(paramsMap) : Map.of();
                    EventRegistry.ValidationResult validation = eventRegistry.validateCommand(commandId, params);
                    for (ValidationError ve : validation.errors()) {
                        errors.add(err(actionPath + "." + ve.path(), ve.code(),
                                "transition " + transitionId + ": " + ve.message()));
                    }
                }
            }
            case "section.update" -> {
                String sectionId = string(action.get("sectionId"));
                if (sectionId.isBlank()) {
                    errors.add(err(actionPath + ".sectionId", "MISSING_REQUIRED_FIELD",
                            "transition " + transitionId + ": sectionId is required for section.update"));
                } else {
                    // Validate fields if sectionType is provided
                    String sectionType = string(action.get("sectionType"));
                    if (!sectionType.isBlank()) {
                        Map<String, Object> fields = action.get("fields") instanceof Map<?, ?> fieldsMap
                                ? normalize(fieldsMap) : Map.of();
                        EventRegistry.ValidationResult validation = eventRegistry.validateSectionFields(sectionType, fields);
                        for (ValidationError ve : validation.errors()) {
                            errors.add(err(actionPath + "." + ve.path(), ve.code(),
                                    "transition " + transitionId + ": " + ve.message()));
                        }
                    }
                }
            }
            case "section.add" -> {
                String sectionType = string(action.get("sectionType"));
                if (sectionType.isBlank()) {
                    errors.add(err(actionPath + ".sectionType", "MISSING_REQUIRED_FIELD",
                            "transition " + transitionId + ": sectionType is required for section.add"));
                } else {
                    Map<String, Object> fields = action.get("fields") instanceof Map<?, ?> fieldsMap
                            ? normalize(fieldsMap) : Map.of();
                    EventRegistry.ValidationResult validation = eventRegistry.validateSectionFields(sectionType, fields);
                    for (ValidationError ve : validation.errors()) {
                        errors.add(err(actionPath + "." + ve.path(), ve.code(),
                                "transition " + transitionId + ": " + ve.message()));
                    }
                }
            }
            case "section.remove" -> {
                String sectionId = string(action.get("sectionId"));
                if (sectionId.isBlank()) {
                    errors.add(err(actionPath + ".sectionId", "MISSING_REQUIRED_FIELD",
                            "transition " + transitionId + ": sectionId is required for section.remove"));
                }
            }
            case "context.set" -> {
                String name = string(action.get("name"));
                if (name.isBlank()) {
                    errors.add(err(actionPath + ".name", "MISSING_REQUIRED_FIELD",
                            "transition " + transitionId + ": name is required for context.set"));
                }
            }
            case "http.request" -> {
                String url = string(action.get("url"));
                if (url.isBlank()) {
                    errors.add(err(actionPath + ".url", "MISSING_REQUIRED_FIELD",
                            "transition " + transitionId + ": url is required for http.request"));
                }
            }
            case "list.instantiate" -> {
                String sectionType = string(action.get("sectionType"));
                String itemsSource = string(action.get("itemsSource"));
                if (sectionType.isBlank()) {
                    errors.add(err(actionPath + ".sectionType", "MISSING_REQUIRED_FIELD",
                            "transition " + transitionId + ": sectionType is required for list.instantiate"));
                } else if (!eventRegistry.isKnownSectionType(sectionType)) {
                    errors.add(err(actionPath + ".sectionType", "UNKNOWN_SECTION_TYPE",
                            "transition " + transitionId + ": unknown section type: " + sectionType));
                }
                if (itemsSource.isBlank()) {
                    errors.add(err(actionPath + ".itemsSource", "MISSING_REQUIRED_FIELD",
                            "transition " + transitionId + ": itemsSource is required for list.instantiate"));
                } else if (!itemsSource.startsWith("$")) {
                    errors.add(err(actionPath + ".itemsSource", "INVALID_REFERENCE",
                            "transition " + transitionId + ": itemsSource must be a $ reference"));
                }
                Map<String, Object> itemTemplate = action.get("itemTemplate") instanceof Map<?, ?> tm
                        ? normalize((Map<?, ?>) tm) : Map.of();
                if (itemTemplate.isEmpty()) {
                    errors.add(err(actionPath + ".itemTemplate", "MISSING_REQUIRED_FIELD",
                            "transition " + transitionId + ": itemTemplate is required for list.instantiate"));
                }
            }
            case "timer.delay" -> {
                Object rawDuration = action.get("durationMs");
                if (rawDuration == null) {
                    errors.add(err(actionPath + ".durationMs", "MISSING_REQUIRED_FIELD",
                            "transition " + transitionId + ": durationMs is required for timer.delay"));
                } else {
                    try {
                        int durationMs = Integer.parseInt(String.valueOf(rawDuration));
                        if (durationMs < 0) {
                            errors.add(err(actionPath + ".durationMs", "VALUE_OUT_OF_RANGE",
                                    "transition " + transitionId + ": durationMs must be >= 0"));
                        }
                    } catch (NumberFormatException e) {
                        errors.add(err(actionPath + ".durationMs", "TYPE_MISMATCH",
                                "transition " + transitionId + ": durationMs must be an integer"));
                    }
                }
            }
            case "state.transition" -> {
                String eventId = string(action.get("eventId"));
                if (eventId.isBlank()) {
                    errors.add(err(actionPath + ".eventId", "MISSING_REQUIRED_FIELD",
                            "transition " + transitionId + ": eventId is required for state.transition"));
                }
            }
            default -> errors.add(err(actionPath + ".type", "UNSUPPORTED_ACTION",
                    "transition " + transitionId + " unsupported action type: " + type));
        }
    }

    // ═══════════════════════════════════════════════════════════
    // Helpers
    // ═══════════════════════════════════════════════════════════

    /**
     * Collect all section IDs from a state definition (across all pages).
     */
    @SuppressWarnings("unchecked")
    private Set<String> collectSectionIds(Map<String, Object> stateDef) {
        Set<String> ids = new LinkedHashSet<>();

        // Multi-page format
        Object rawPages = stateDef.get("pages");
        if (rawPages instanceof List<?> pages) {
            for (Object p : pages) {
                if (p instanceof Map<?, ?> pm) {
                    collectSectionIdsFromContainer(normalize(pm), ids);
                }
            }
        }

        // Legacy format
        collectSectionIdsFromContainer(stateDef, ids);

        return ids;
    }

    @SuppressWarnings("unchecked")
    private void collectSectionIdsFromContainer(Map<String, Object> container, Set<String> ids) {
        Object rawSections = container.get("sections");
        if (rawSections instanceof List<?> sections) {
            for (Object s : sections) {
                if (s instanceof Map<?, ?> sm) {
                    String sid = string(sm.get("sectionId"));
                    if (!sid.isBlank()) ids.add(sid);
                }
            }
        }
    }

    private ValidationError err(String path, String code, String message) {
        return new ValidationError(path, code, message);
    }

    private Map<String, Object> normalize(Map<?, ?> raw) {
        Map<String, Object> result = new LinkedHashMap<>();
        raw.forEach((key, value) -> result.put(String.valueOf(key), value));
        return result;
    }

    private String string(Object value) {
        return value == null ? "" : String.valueOf(value);
    }
}
