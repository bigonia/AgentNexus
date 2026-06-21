package com.zwbd.agentnexus.sdui.statemachine;

import com.zwbd.agentnexus.sdui.DeviceSessionManager;
import com.zwbd.agentnexus.sdui.capability.CapabilityRegistry;
import com.zwbd.agentnexus.sdui.dto.SduiControlDispatchResult;
import com.zwbd.agentnexus.sdui.event.EventDefinition;
import com.zwbd.agentnexus.sdui.event.EventPayload;
import com.zwbd.agentnexus.sdui.event.EventRegistry;
import com.zwbd.agentnexus.sdui.section.SectionData;
import com.zwbd.agentnexus.sdui.section.SectionDataCodec;
import com.zwbd.agentnexus.sdui.section.SectionTypeCatalog;
import com.zwbd.agentnexus.sdui.service.CommandService;
import com.zwbd.agentnexus.sdui.statemachine.model.StateMachine;
import com.zwbd.agentnexus.sdui.statemachine.model.StateMachineDeployment;
import com.zwbd.agentnexus.sdui.statemachine.repo.StateMachineDeploymentRepository;
import com.zwbd.agentnexus.sdui.statemachine.repo.StateMachineRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.client.RestTemplate;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Unified state machine service — pure static definitions with independent
 * deployment records.
 *
 * The state machine definition is a blueprint (states, transitions, sections).
 * Deployments are lightweight runtime instances that bind the definition to
 * specific devices and carry their own runtime state (currentStateId, contextData).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class StateMachineService {

    private final StateMachineRepository stateMachineRepository;
    private final StateMachineDeploymentRepository deploymentRepository;
    private final StateMachineValidationService validationService;
    private final EventRegistry eventRegistry;
    private final CommandService commandService;
    private final StateMachineProjectionService projectionService;
    private final SectionDataCodec sectionDataCodec;
    private final RestTemplate restTemplate;
    private final DeviceSessionManager deviceSessionManager;
    private final CapabilityRegistry capabilityRegistry;

    // ═══════════════════════════════════════════════════════════
    // CRUD
    // ═══════════════════════════════════════════════════════════

    public Map<String, Object> listDefinitions(int page, int size) {
        int pageIndex = Math.max(0, page);
        int pageSize = Math.max(1, Math.min(size, 100));
        PageRequest pageRequest = PageRequest.of(pageIndex, pageSize);

        var resultPage = stateMachineRepository.findAllByOrderByUpdatedAtDesc(pageRequest);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("items", resultPage.getContent().stream().map(this::toStateMachineMap).toList());
        result.put("page", resultPage.getNumber());
        result.put("size", resultPage.getSize());
        result.put("totalPages", resultPage.getTotalPages());
        result.put("totalItems", resultPage.getTotalElements());
        return result;
    }

    public Map<String, Object> getDefinition(String id) {
        return toStateMachineMap(stateMachine(id));
    }

    @Transactional
    public Map<String, Object> createDefinition(Map<String, Object> request) {
        StateMachine sm = new StateMachine();

        String name = string(request.get("name"));
        if (name.isBlank()) throw new IllegalArgumentException("name is required");
        sm.setName(name);

        sm.setDescription(string(request.get("description")));

        // boardTypes — required, at least one
        @SuppressWarnings("unchecked")
        List<String> boardTypes = request.get("boardTypes") instanceof List<?> list
                ? list.stream().map(Object::toString).toList() : List.of();
        if (boardTypes.isEmpty()) throw new IllegalArgumentException("boardTypes is required (at least one)");
        sm.setBoardTypes(boardTypes);

        // definition (optional for incremental API — defaults to empty)
        Map<String, Object> definition;
        if (request.get("definition") instanceof Map<?, ?> map) {
            definition = normalize(map);
            Map<String, Object> validation = validationService.validate(definition);
            if (!Boolean.TRUE.equals(validation.get("valid"))) {
                throw new IllegalArgumentException("invalid definition: " + validation.get("errors"));
            }
        } else {
            definition = new LinkedHashMap<>();
            definition.put("states", new ArrayList<>());
            definition.put("transitions", new ArrayList<>());
        }
        sm.setDefinition(definition);

        // editorModel (optional)
        if (request.get("editorModel") instanceof Map<?, ?> em) {
            sm.setEditorModel(normalize(em));
        }

        return toStateMachineMap(stateMachineRepository.save(sm));
    }

    @Transactional
    public Map<String, Object> updateDefinition(String id, Map<String, Object> request) {
        StateMachine sm = stateMachine(id);

        if (request.containsKey("name")) {
            String name = string(request.get("name"));
            if (name.isBlank()) throw new IllegalArgumentException("name is required");
            sm.setName(name);
        }
        if (request.containsKey("description")) {
            sm.setDescription(string(request.get("description")));
        }
        // boardTypes is immutable after creation
        if (request.containsKey("boardTypes")) {
            throw new IllegalArgumentException("boardTypes cannot be modified after creation");
        }
        if (request.containsKey("definition")) {
            if (!(request.get("definition") instanceof Map<?, ?> map)) {
                throw new IllegalArgumentException("definition must be an object");
            }
            Map<String, Object> normalized = normalize(map);
            Map<String, Object> validation = validationService.validate(normalized);
            if (!Boolean.TRUE.equals(validation.get("valid"))) {
                throw new IllegalArgumentException("invalid definition: " + validation.get("errors"));
            }
            sm.setDefinition(normalized);
        }
        if (request.containsKey("editorModel")) {
            sm.setEditorModel(request.get("editorModel") instanceof Map<?, ?> em ? normalize(em) : Map.of());
        }

        return toStateMachineMap(stateMachineRepository.save(sm));
    }

    @Transactional
    public Map<String, Object> deleteDefinition(String id) {
        deploymentRepository.deleteByStateMachineId(id);
        stateMachineRepository.delete(stateMachine(id));
        return Map.of("deleted", true, "id", id);
    }

    // ═══════════════════════════════════════════════════════════
    // Validation
    // ═══════════════════════════════════════════════════════════

    public Map<String, Object> validate(Map<String, Object> definition) {
        return validationService.validate(definition);
    }

    // ═══════════════════════════════════════════════════════════
    // State sub-resource management
    // ═══════════════════════════════════════════════════════════

    @Transactional
    public Map<String, Object> addState(String stateMachineId, Map<String, Object> request) {
        StateMachine sm = stateMachine(stateMachineId);
        Map<String, Object> definition = sm.getDefinition();

        // Validate
        Map<String, Object> validation = validationService.validateStateEntry(request);
        if (!Boolean.TRUE.equals(validation.get("valid"))) {
            throw new IllegalArgumentException("invalid state: " + validation.get("errors"));
        }

        String stateId = string(request.get("id"));
        if (stateId.isBlank()) throw new IllegalArgumentException("state id is required");

        // Check duplicate
        if (findState(definition, stateId) != null) {
            throw new IllegalArgumentException("state id '" + stateId + "' already exists");
        }

        // Build state entry
        Map<String, Object> stateEntry = new LinkedHashMap<>();
        stateEntry.put("id", stateId);
        String label = string(request.get("label"));
        stateEntry.put("label", label.isBlank() ? stateId : label);

        // Support new multi-page format
        if (request.get("pages") instanceof List<?> pages) {
            stateEntry.put("pages", deepCloneList(pages));
        } else if (request.get("sections") instanceof List<?> sections) {
            // Legacy single-page
            stateEntry.put("sections", deepCloneList(sections));
        } else {
            throw new IllegalArgumentException("state must have pages or sections");
        }

        // Append to states list
        List<Map<String, Object>> states = stateListMutable(definition);
        boolean isInitial = states.isEmpty();
        states.add(stateEntry);
        definition.put("states", states);
        sm.setDefinition(definition);
        stateMachineRepository.save(sm);

        Map<String, Object> result = new LinkedHashMap<>(stateEntry);
        result.put("isInitial", isInitial);
        return result;
    }

    public Map<String, Object> listStates(String stateMachineId) {
        StateMachine sm = stateMachine(stateMachineId);
        List<Map<String, Object>> states = stateList(sm.getDefinition());
        List<Map<String, Object>> summaries = new ArrayList<>();
        for (Map<String, Object> s : states) {
            Map<String, Object> summary = new LinkedHashMap<>();
            summary.put("id", string(s.get("id")));
            summary.put("label", string(s.get("label")));
            summary.put("pageCount", countPages(s));
            summary.put("derivedFromTransitionId", s.get("derivedFromTransitionId"));
            summaries.add(summary);
        }
        return Map.of("states", summaries);
    }

    public Map<String, Object> getState(String stateMachineId, String stateId) {
        StateMachine sm = stateMachine(stateMachineId);
        Map<String, Object> state = findState(sm.getDefinition(), stateId);
        if (state == null) throw new IllegalArgumentException("state not found: " + stateId);

        Map<String, Object> result = new LinkedHashMap<>(state);
        result.put("isInitial", isInitialState(sm.getDefinition(), stateId));
        return result;
    }

    /**
     * Return the state as it exists at a given transition point.
     * If the transition has a toStateId, returns that target state.
     * If no toStateId (self-loop / command-only), returns the fromState.
     */
    public Map<String, Object> getStateAtTransition(String stateMachineId, String transitionId) {
        StateMachine sm = stateMachine(stateMachineId);
        Map<String, Object> transition = findTransitionById(sm.getDefinition(), transitionId);
        if (transition == null) throw new IllegalArgumentException("transition not found: " + transitionId);

        String toStateId = string(transition.get("toStateId"));
        String stateId = toStateId.isBlank() ? string(transition.get("fromStateId")) : toStateId;
        return getState(stateMachineId, stateId);
    }

    @Transactional
    public Map<String, Object> updateState(String stateMachineId, String stateId, Map<String, Object> request) {
        StateMachine sm = stateMachine(stateMachineId);
        Map<String, Object> definition = sm.getDefinition();
        Map<String, Object> state = findState(definition, stateId);
        if (state == null) throw new IllegalArgumentException("state not found: " + stateId);

        // Validate new definition if pages/sections provided
        Map<String, Object> merged = new LinkedHashMap<>(state);
        if (request.containsKey("label")) merged.put("label", string(request.get("label")));
        if (request.containsKey("pages")) merged.put("pages", request.get("pages"));
        if (request.containsKey("sections")) merged.put("sections", request.get("sections"));

        if (request.containsKey("pages") || request.containsKey("sections")) {
            Map<String, Object> validation = validationService.validateStateEntry(merged);
            if (!Boolean.TRUE.equals(validation.get("valid"))) {
                throw new IllegalArgumentException("invalid state update: " + validation.get("errors"));
            }
        }

        // Apply updates
        if (request.containsKey("label")) state.put("label", string(request.get("label")));
        if (request.containsKey("pages")) state.put("pages", deepCloneList(request.get("pages")));
        if (request.containsKey("sections")) {
            state.remove("pages"); // clear multi-page format when using legacy
            state.put("sections", deepCloneList(request.get("sections")));
        }

        // Manual edit clears derivation back-link
        if (request.containsKey("pages") || request.containsKey("sections")) {
            state.remove("derivedFromTransitionId");
        }

        sm.setDefinition(definition);
        stateMachineRepository.save(sm);

        Map<String, Object> result = new LinkedHashMap<>(state);
        result.put("isInitial", isInitialState(definition, stateId));
        return result;
    }

    @Transactional
    public Map<String, Object> deleteState(String stateMachineId, String stateId) {
        StateMachine sm = stateMachine(stateMachineId);
        Map<String, Object> definition = sm.getDefinition();
        Map<String, Object> state = findState(definition, stateId);
        if (state == null) throw new IllegalArgumentException("state not found: " + stateId);

        // Cascade: remove transitions that reference this state
        List<String> cascadedTransitionIds = new ArrayList<>();
        List<Map<String, Object>> transitions = transitionList(definition);
        transitions.removeIf(t -> {
            String fromId = string(t.get("fromStateId"));
            String toId = string(t.get("toStateId"));
            boolean remove = stateId.equals(fromId) || stateId.equals(toId);
            if (remove) cascadedTransitionIds.add(string(t.get("id")));
            return remove;
        });
        definition.put("transitions", transitions);

        // Remove the state itself
        List<Map<String, Object>> states = stateListMutable(definition);
        states.removeIf(s -> stateId.equals(string(s.get("id"))));
        definition.put("states", states);

        sm.setDefinition(definition);
        stateMachineRepository.save(sm);

        return Map.of("deleted", true, "id", stateId,
                "cascadedTransitionIds", cascadedTransitionIds);
    }

    // ═══════════════════════════════════════════════════════════
    // Transition sub-resource management
    // ═══════════════════════════════════════════════════════════

    @Transactional
    public Map<String, Object> addTransition(String stateMachineId, Map<String, Object> request) {
        StateMachine sm = stateMachine(stateMachineId);
        Map<String, Object> definition = sm.getDefinition();

        // Collect existing state IDs for validation
        Set<String> existingStateIds = new LinkedHashSet<>();
        for (Map<String, Object> s : stateList(definition)) {
            String sid = string(s.get("id"));
            if (!sid.isBlank()) existingStateIds.add(sid);
        }

        // Validate
        Map<String, Object> validation = validationService.validateTransitionEntry(request, existingStateIds);
        if (!Boolean.TRUE.equals(validation.get("valid"))) {
            throw new IllegalArgumentException("invalid transition: " + validation.get("errors"));
        }

        // Resolve effective fromStateId (fromTransitionId > fromStateId > initial state)
        String fromStateId = resolveFromStateId(definition, request, existingStateIds);
        if (!existingStateIds.contains(fromStateId)) {
            throw new IllegalArgumentException(
                    "fromStateId '" + fromStateId + "' does not match any existing state");
        }

        // Build transition entry
        String transitionId = UUID.randomUUID().toString();
        Map<String, Object> transition = new LinkedHashMap<>();
        transition.put("id", transitionId);
        transition.put("fromStateId", fromStateId);
        // Store fromTransitionId if provided (traceability)
        String fromTransitionId = string(request.get("fromTransitionId"));
        if (!fromTransitionId.isBlank()) {
            transition.put("fromTransitionId", fromTransitionId);
        }

        String toStateId = string(request.get("toStateId"));
        if (!toStateId.isBlank()) transition.put("toStateId", toStateId);

        transition.put("event", request.get("event") instanceof Map<?, ?> em
                ? normalize(em) : Map.of());
        transition.put("priority", request.get("priority") instanceof Number n
                ? n.intValue() : 0);

        // Actions
        List<Map<String, Object>> actions = new ArrayList<>();
        if (request.get("actions") instanceof List<?> actionList) {
            for (Object a : actionList) {
                if (a instanceof Map<?, ?> am) actions.add(normalize(am));
            }
        }
        transition.put("actions", actions);

        // Pre-derivation validation (warnings, not errors)
        Map<String, Object> derivedState = null;
        if (!toStateId.isBlank()) {
            Map<String, Object> fromState = findState(definition, fromStateId);
            if (fromState != null) {
                Map<String, Object> derivWarnings = validationService
                        .validateTransitionForDerivation(transition, fromState);
                @SuppressWarnings("unchecked")
                List<Map<String, Object>> warnings = derivWarnings.get("warnings") instanceof List<?> w
                        ? (List<Map<String, Object>>) w.stream()
                                .filter(e -> e instanceof Map<?, ?>)
                                .map(e -> normalize((Map<?, ?>) e))
                                .toList()
                        : List.of();
                if (!warnings.isEmpty()) {
                    transition.put("derivationWarnings", warnings);
                }
            }

            // Run derivation
            derivedState = deriveStateFromTransition(definition, transition);
        }

        // Append to transitions list
        List<Map<String, Object>> transitions = transitionListMutable(definition);
        transitions.add(transition);
        definition.put("transitions", transitions);
        sm.setDefinition(definition);
        stateMachineRepository.save(sm);

        Map<String, Object> result = new LinkedHashMap<>(transition);
        if (derivedState != null) result.put("derivedState", derivedState);
        return result;
    }

    public Map<String, Object> listTransitions(String stateMachineId) {
        StateMachine sm = stateMachine(stateMachineId);
        List<Map<String, Object>> transitions = transitionList(sm.getDefinition());
        List<Map<String, Object>> summaries = new ArrayList<>();
        int index = 0;
        for (Map<String, Object> t : transitions) {
            Map<String, Object> summary = new LinkedHashMap<>();
            summary.put("id", string(t.get("id")));
            summary.put("fromStateId", string(t.get("fromStateId")));
            summary.put("toStateId", string(t.get("toStateId")));
            summary.put("event", t.get("event"));
            summary.put("actionCount", t.get("actions") instanceof List<?> al ? al.size() : 0);
            summary.put("priority", t.get("priority") instanceof Number n ? n.intValue() : 0);
            summary.put("index", index++);
            summaries.add(summary);
        }
        return Map.of("transitions", summaries);
    }

    public Map<String, Object> getTransition(String stateMachineId, String transitionId) {
        StateMachine sm = stateMachine(stateMachineId);
        Map<String, Object> transition = findTransitionById(sm.getDefinition(), transitionId);
        if (transition == null) throw new IllegalArgumentException("transition not found: " + transitionId);

        Map<String, Object> result = new LinkedHashMap<>(transition);
        result.put("index", transitionList(sm.getDefinition()).indexOf(transition));
        return result;
    }

    // ═══════════════════════════════════════════════════════════
    // State-aware available events
    // ═══════════════════════════════════════════════════════════

    /**
     * Get events available as triggers for a given state in a state machine.
     *
     * Returns structured groups for the tree view AND a flat
     * {@code availableTriggers} list ready for the "add transition" dropdown.
     * Events already used in existing transitions are marked with
     * {@code configured: true} so the frontend can grey them out rather than
     * hiding them.
     *
     * <h3>Response structure</h3>
     * <pre>{@code
     * {
     *   stateId, stateLabel,
     *   availableTriggers: [ { eventId, displayName, category, source, configured } ],
     *   usedEventIds: [ "ui:action.click", ... ],
     *   sectionEvents: [ ... ],       // tree view: section interaction events
     *   physicalInputs: [ ... ],      // tree view: device physical inputs (grouped)
     *   mediaCapabilities: [ ... ],   // tree view: platform-mediated capabilities
     *   systemEvents: [ ... ],        // tree view: timer / cron
     *   configuredTransitions: [ ... ] // existing transitions FROM this state
     * }
     * }</pre>
     */
    public Map<String, Object> getAvailableEvents(String stateMachineId, String stateId) {
        StateMachine sm = stateMachine(stateMachineId);
        Map<String, Object> definition = sm.getDefinition();

        // Find the target state
        Map<String, Object> targetState = null;
        for (Map<String, Object> s : stateList(definition)) {
            if (stateId.equals(string(s.get("id")))) {
                targetState = s;
                break;
            }
        }
        if (targetState == null) {
            throw new IllegalArgumentException("state not found: " + stateId);
        }

        // ── 1. Section interaction events from this state's sections ──
        Set<String> stateSectionTypes = new LinkedHashSet<>();
        collectSectionTypes(targetState, stateSectionTypes);

        List<Map<String, Object>> sectionEvents = new ArrayList<>();
        for (String stype : stateSectionTypes) {
            for (SectionTypeCatalog.InteractionEvent ievt : SectionTypeCatalog.getInteractionEvents(stype)) {
                eventRegistry.getSectionEvent(ievt.eventId()).ifPresent(def -> {
                    if (def.isPublicTrigger()) {
                        sectionEvents.add(def.toPublicMap());
                    }
                });
            }
        }

        // ── 2. Physical input events from board types ──
        List<Map<String, Object>> physicalInputs = new ArrayList<>();
        List<Map<String, Object>> mediaCapabilities = new ArrayList<>();
        for (String boardTypeKey : sm.getBoardTypes()) {
            CapabilityRegistry.DeviceTypeInfo typeInfo = capabilityRegistry.getDeviceType(boardTypeKey).orElse(null);
            if (typeInfo == null) continue;
            String exampleDeviceId = typeInfo.exampleDeviceIds().stream().findFirst().orElse(null);
            if (exampleDeviceId != null) {
                physicalInputs.addAll(capabilityRegistry.getDevicePhysicalInputs(exampleDeviceId));
                mediaCapabilities.addAll(capabilityRegistry.getDeviceMediaCapabilities(exampleDeviceId));
                break; // Use first board type with an example device
            }
        }

        // ── 3. System events (timer, cron) ── always available ──
        List<Map<String, Object>> systemEvents = eventRegistry.getEventsByCategory(
                EventDefinition.EventCategory.SYSTEM_EVENT).stream()
                .map(EventDefinition::toPublicMap)
                .toList();

        // ── 4. Already-configured transitions FROM this state ──
        List<Map<String, Object>> configuredTransitions = new ArrayList<>();
        Set<String> usedEventIds = new LinkedHashSet<>();
        for (Map<String, Object> t : transitionList(definition)) {
            String fromStateId = string(t.get("fromStateId"));
            if (stateId.equals(fromStateId)) {
                Map<String, Object> event = t.get("event") instanceof Map<?, ?> em
                        ? normalize(em) : Map.of();
                String eventId = eventRegistry.resolveEventId(string(event.get("eventId")));
                usedEventIds.add(eventId);
                Map<String, Object> config = new LinkedHashMap<>();
                config.put("transitionId", string(t.get("id")));
                config.put("toStateId", string(t.get("toStateId")));
                config.put("eventId", eventId);
                config.put("priority", t.get("priority") instanceof Number n ? n.intValue() : 0);
                configuredTransitions.add(config);
            }
        }

        // ── 5. Build flat availableTriggers (deduplicated, marked configured) ──
        List<Map<String, Object>> availableTriggers = new ArrayList<>();
        Set<String> seenEventIds = new LinkedHashSet<>();

        // Helper to add a trigger entry (dedup by eventId)
        java.util.function.BiConsumer<String, Map<String, String>> addTrigger = (eventId, meta) -> {
            if (eventId == null || eventId.isBlank() || !seenEventIds.add(eventId)) return;
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("eventId", eventId);
            entry.put("displayName", meta.getOrDefault("displayName", eventId));
            entry.put("category", meta.getOrDefault("category", ""));
            entry.put("source", meta.getOrDefault("source", ""));
            entry.put("configured", usedEventIds.contains(eventId));
            availableTriggers.add(entry);
        };

        // Section events
        for (Map<String, Object> sec : sectionEvents) {
            addTrigger.accept(
                    string(sec.get("eventId")),
                    Map.of("displayName", string(sec.get("displayName")),
                           "category", string(sec.get("category")),
                           "source", string(sec.get("sourceCapability")))
            );
        }

        // Physical input events (flatten grouped structure)
        for (Map<String, Object> input : physicalInputs) {
            String inputName = string(input.get("inputName"));
            String inputDisplayName = string(input.get("displayName"));
            if (input.get("events") instanceof List<?> evts) {
                for (Object e : evts) {
                    if (e instanceof Map<?, ?> em) {
                        String eventId = string(em.get("eventId"));
                        String displayName = string(em.get("displayName"));
                        addTrigger.accept(eventId, Map.of(
                                "displayName", inputDisplayName + " · " + displayName,
                                "category", "USER_INTERACTION",
                                "source", inputName
                        ));
                    }
                }
            }
        }

        // Media capability trigger events
        for (Map<String, Object> mc : mediaCapabilities) {
            String capName = string(mc.get("capabilityName"));
            String capDisplayName = string(mc.get("displayName"));
            if (mc.get("triggerEvents") instanceof List<?> triggers) {
                for (Object t : triggers) {
                    if (t instanceof Map<?, ?> tm) {
                        String eventId = string(tm.get("eventId"));
                        String displayName = string(tm.get("displayName"));
                        addTrigger.accept(eventId, Map.of(
                                "displayName", capDisplayName + " · " + displayName,
                                "category", "SYSTEM_EVENT",
                                "source", capName
                        ));
                    }
                }
            }
        }

        // System events
        for (Map<String, Object> se : systemEvents) {
            addTrigger.accept(
                    string(se.get("eventId")),
                    Map.of("displayName", string(se.get("displayName")),
                           "category", "SYSTEM_EVENT",
                           "source", "system")
            );
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("stateId", stateId);
        result.put("stateLabel", string(targetState.get("label")));
        // Flat list — ready for "add transition" dropdown
        result.put("availableTriggers", availableTriggers);
        // Set of already-configured event IDs for frontend filtering
        result.put("usedEventIds", new ArrayList<>(usedEventIds));
        // Structured groups for tree view
        result.put("sectionEvents", sectionEvents);
        result.put("physicalInputs", physicalInputs);
        result.put("mediaCapabilities", mediaCapabilities);
        result.put("systemEvents", systemEvents);
        result.put("configuredTransitions", configuredTransitions);
        return result;
    }

    @Transactional
    public Map<String, Object> updateTransition(String stateMachineId, String transitionId,
                                                 Map<String, Object> request) {
        StateMachine sm = stateMachine(stateMachineId);
        Map<String, Object> definition = sm.getDefinition();
        Map<String, Object> transition = findTransitionById(definition, transitionId);
        if (transition == null) throw new IllegalArgumentException("transition not found: " + transitionId);

        // Merge provided fields
        Map<String, Object> merged = new LinkedHashMap<>(transition);
        // Resolve fromStateId when fromStateId or fromTransitionId changed
        if (request.containsKey("fromStateId") || request.containsKey("fromTransitionId")) {
            Set<String> existingStateIds = new LinkedHashSet<>();
            for (Map<String, Object> s : stateList(definition)) {
                String sid = string(s.get("id"));
                if (!sid.isBlank()) existingStateIds.add(sid);
            }
            String resolved = resolveFromStateId(definition, request, existingStateIds);
            merged.put("fromStateId", resolved);
            if (request.containsKey("fromTransitionId")) {
                String ftid = string(request.get("fromTransitionId"));
                if (!ftid.isBlank()) {
                    merged.put("fromTransitionId", ftid);
                } else {
                    merged.remove("fromTransitionId");
                }
            }
        }
        if (request.containsKey("toStateId")) merged.put("toStateId", string(request.get("toStateId")));
        if (request.containsKey("event")) merged.put("event", request.get("event") instanceof Map<?, ?> em
                ? normalize(em) : transition.get("event"));
        if (request.containsKey("priority")) {
            merged.put("priority", request.get("priority") instanceof Number n
                    ? n.intValue() : transition.get("priority"));
        }
        if (request.containsKey("actions") && request.get("actions") instanceof List<?> al) {
            List<Map<String, Object>> actions = new ArrayList<>();
            for (Object a : al) {
                if (a instanceof Map<?, ?> am) actions.add(normalize(am));
            }
            merged.put("actions", actions);
        }

        // Validate merged transition
        Set<String> existingStateIds = new LinkedHashSet<>();
        for (Map<String, Object> s : stateList(definition)) {
            String sid = string(s.get("id"));
            if (!sid.isBlank()) existingStateIds.add(sid);
        }
        Map<String, Object> validation = validationService.validateTransitionEntry(merged, existingStateIds);
        if (!Boolean.TRUE.equals(validation.get("valid"))) {
            throw new IllegalArgumentException("invalid transition update: " + validation.get("errors"));
        }

        // Apply updates
        transition.putAll(merged);

        // Re-derive if fromStateId, toStateId, actions, or fromTransitionId changed
        Map<String, Object> derivedState = null;
        String toStateId = string(transition.get("toStateId"));
        if (!toStateId.isBlank()
                && (request.containsKey("toStateId") || request.containsKey("actions")
                    || request.containsKey("fromStateId")
                    || request.containsKey("fromTransitionId"))) {
            derivedState = deriveStateFromTransition(definition, transition);
        }

        sm.setDefinition(definition);
        stateMachineRepository.save(sm);

        Map<String, Object> result = new LinkedHashMap<>(transition);
        if (derivedState != null) result.put("derivedState", derivedState);
        return result;
    }

    @Transactional
    public Map<String, Object> deleteTransition(String stateMachineId, String transitionId) {
        StateMachine sm = stateMachine(stateMachineId);
        Map<String, Object> definition = sm.getDefinition();
        Map<String, Object> transition = findTransitionById(definition, transitionId);
        if (transition == null) throw new IllegalArgumentException("transition not found: " + transitionId);

        String cascadedStateId = null;
        String toStateId = string(transition.get("toStateId"));
        if (!toStateId.isBlank()) {
            // Only cascade-delete the target state if it was derived by THIS transition
            Map<String, Object> targetState = findState(definition, toStateId);
            if (targetState != null && transitionId.equals(string(targetState.get("derivedFromTransitionId")))) {
                cascadedStateId = toStateId;
                List<Map<String, Object>> states = stateListMutable(definition);
                states.removeIf(s -> toStateId.equals(string(s.get("id"))));
                definition.put("states", states);
            }
        }

        // Remove the transition
        List<Map<String, Object>> transitions = transitionListMutable(definition);
        transitions.removeIf(t -> transitionId.equals(string(t.get("id"))));
        definition.put("transitions", transitions);

        sm.setDefinition(definition);
        stateMachineRepository.save(sm);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("deleted", true);
        result.put("id", transitionId);
        if (cascadedStateId != null) result.put("cascadedStateId", cascadedStateId);
        return result;
    }

    @Transactional
    public Map<String, Object> reorderTransitions(String stateMachineId, List<String> transitionIds) {
        StateMachine sm = stateMachine(stateMachineId);
        Map<String, Object> definition = sm.getDefinition();
        List<Map<String, Object>> currentTransitions = transitionListMutable(definition);

        // Validate: must be a permutation of all existing transition IDs
        Set<String> existingIds = new LinkedHashSet<>();
        for (Map<String, Object> t : currentTransitions) {
            existingIds.add(string(t.get("id")));
        }
        Set<String> requestedIds = new LinkedHashSet<>(transitionIds);
        if (!existingIds.equals(requestedIds)) {
            throw new IllegalArgumentException("transitionIds must be a complete permutation of existing transitions");
        }

        // Build reordered list
        Map<String, Map<String, Object>> byId = new LinkedHashMap<>();
        for (Map<String, Object> t : currentTransitions) {
            byId.put(string(t.get("id")), t);
        }
        List<Map<String, Object>> reordered = new ArrayList<>();
        for (String tid : transitionIds) {
            Map<String, Object> t = byId.get(tid);
            if (t != null) reordered.add(t);
        }

        definition.put("transitions", reordered);
        sm.setDefinition(definition);
        stateMachineRepository.save(sm);

        return Map.of("reordered", true, "transitionIds", transitionIds);
    }

    // ═══════════════════════════════════════════════════════════
    // Auto-Derivation Engine
    // ═══════════════════════════════════════════════════════════

    /**
     * Derive (or re-derive) the target state from a transition's fromState + actions.
     * <p>
     * Operates at the DEFINITION level — template variables like {@code ${ctx.xxx}}
     * are preserved as-is. Only section.add/update/remove and context.set actions
     * affect the derived state. Non-section actions (command.dispatch, http.request, etc.)
     * are ignored during derivation.
     *
     * @param definition the full definition map (mutated in-place: target state is upserted)
     * @param transition the transition map with fromStateId, toStateId, actions
     * @return the derived (or updated) state map
     */
    Map<String, Object> deriveStateFromTransition(Map<String, Object> definition,
                                                   Map<String, Object> transition) {
        String fromStateId = string(transition.get("fromStateId"));
        String toStateId = string(transition.get("toStateId"));
        if (toStateId.isBlank()) return null;

        Map<String, Object> fromState = findState(definition, fromStateId);
        if (fromState == null) {
            throw new IllegalArgumentException("fromState '" + fromStateId + "' not found");
        }

        // Deep-clone the fromState's page definitions (preserving template variables)
        List<Map<String, Object>> derivedPages = deepCloneDefinitionPages(fromState);

        // Collect context.set values as initialContext
        Map<String, Object> initialContext = new LinkedHashMap<>();

        // Apply section-modifying actions to the page definitions
        Object rawActions = transition.get("actions");
        if (rawActions instanceof List<?> actions) {
            for (Object rawAction : actions) {
                if (!(rawAction instanceof Map<?, ?> actionMap)) continue;
                Map<String, Object> action = normalize(actionMap);
                applyActionToDefinitionPages(action, derivedPages, initialContext);
            }
        }

        // Build the derived state
        Map<String, Object> derivedState = new LinkedHashMap<>();
        derivedState.put("id", toStateId);
        String label = string(transition.getOrDefault("label", ""));
        derivedState.put("label", label.isBlank() ? toStateId : label);
        derivedState.put("pages", derivedPages);
        derivedState.put("derivedFromTransitionId", string(transition.get("id")));
        if (!initialContext.isEmpty()) {
            derivedState.put("initialContext", initialContext);
        }

        // Upsert into definition.states
        List<Map<String, Object>> states = stateListMutable(definition);
        Map<String, Object> existingState = findState(definition, toStateId);
        if (existingState != null) {
            // Preserve user-set label if manually created (not previously auto-derived)
            if (existingState.get("derivedFromTransitionId") == null) {
                derivedState.put("label", string(existingState.get("label")));
            }
            // Replace in list
            for (int i = 0; i < states.size(); i++) {
                if (toStateId.equals(string(states.get(i).get("id")))) {
                    states.set(i, derivedState);
                    break;
                }
            }
        } else {
            states.add(derivedState);
        }
        definition.put("states", states);

        return derivedState;
    }

    /**
     * Deep-clone pages at the definition level — preserves template expressions like
     * {@code ${ctx.xxx}} without resolving them. Does NOT include device bindings.
     */
    @SuppressWarnings("unchecked")
    List<Map<String, Object>> deepCloneDefinitionPages(Map<String, Object> state) {
        List<Map<String, Object>> clonedPages = new ArrayList<>();

        // Multi-page format
        Object rawPages = state.get("pages");
        if (rawPages instanceof List<?> pages && !pages.isEmpty()) {
            for (Object p : pages) {
                if (!(p instanceof Map<?, ?> pm)) continue;
                clonedPages.add(clonePageDef(normalize(pm)));
            }
            return clonedPages;
        }

        // Legacy format: sections at state level → single page "main"
        Map<String, Object> legacyPage = new LinkedHashMap<>();
        legacyPage.put("pageId", "main");
        legacyPage.put("layout", string(state.getOrDefault("layout", "vertical_scroll")));
        legacyPage.put("autoScroll", Boolean.TRUE.equals(state.get("autoScroll")));
        legacyPage.put("autoScrollMs", state.get("autoScrollMs") instanceof Number n ? n.intValue() : 0);
        legacyPage.put("sections", cloneSectionsDef(state));
        clonedPages.add(legacyPage);
        return clonedPages;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> clonePageDef(Map<String, Object> pageDef) {
        Map<String, Object> clone = new LinkedHashMap<>();
        clone.put("pageId", string(pageDef.get("pageId")));
        clone.put("layout", string(pageDef.getOrDefault("layout", "vertical_scroll")));
        clone.put("autoScroll", Boolean.TRUE.equals(pageDef.get("autoScroll")));
        clone.put("autoScrollMs", pageDef.get("autoScrollMs") instanceof Number n ? n.intValue() : 0);
        clone.put("sections", cloneSectionsDef(pageDef));
        return clone;
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> cloneSectionsDef(Map<String, Object> container) {
        List<Map<String, Object>> cloned = new ArrayList<>();
        Object rawSections = container.get("sections");
        if (!(rawSections instanceof List<?> sections)) return cloned;

        for (Object s : sections) {
            if (!(s instanceof Map<?, ?> sm)) continue;
            Map<String, Object> sec = new LinkedHashMap<>(normalize(sm));
            // Deep-clone fields (preserving template strings)
            if (sec.get("fields") instanceof Map<?, ?> f) {
                sec.put("fields", new LinkedHashMap<>(normalize(f)));
            }
            cloned.add(sec);
        }
        return cloned;
    }

    /**
     * Apply a single action to definition-level pages (edit-time, no variable resolution).
     * Non-section actions (command.dispatch, http.request, etc.) are ignored.
     */
    @SuppressWarnings("unchecked")
    void applyActionToDefinitionPages(Map<String, Object> action,
                                       List<Map<String, Object>> pages,
                                       Map<String, Object> initialContext) {
        String type = string(action.get("type"));
        String pageId = string(action.getOrDefault("pageId", ""));

        switch (type) {
            case "section.add" -> {
                String sectionType = string(action.getOrDefault("sectionType", ""));
                String sectionId = string(action.getOrDefault("sectionId", ""));
                if (sectionType.isBlank()) return;
                if (sectionId.isBlank()) sectionId = "sec_" + UUID.randomUUID().toString().substring(0, 8);
                Map<String, Object> fields = action.get("fields") instanceof Map<?, ?> fm
                        ? new LinkedHashMap<>(normalize(fm)) : new LinkedHashMap<>();

                Map<String, Object> newSection = new LinkedHashMap<>();
                newSection.put("sectionId", sectionId);
                newSection.put("sectionType", sectionType);
                newSection.put("fields", fields);

                for (Map<String, Object> page : pages) {
                    if (!pageId.isBlank() && !pageId.equals(string(page.get("pageId")))) continue;
                    List<Map<String, Object>> secs = sectionListMutable(page);
                    secs.add(newSection);
                    page.put("sections", secs);
                }
            }
            case "section.update" -> {
                String sectionId = string(action.getOrDefault("sectionId", ""));
                if (sectionId.isBlank()) return;
                Map<String, Object> fields = action.get("fields") instanceof Map<?, ?> fm
                        ? normalize(fm) : Map.of();

                for (Map<String, Object> page : pages) {
                    if (!pageId.isBlank() && !pageId.equals(string(page.get("pageId")))) continue;
                    for (Map<String, Object> sec : sectionListMutable(page)) {
                        if (sectionId.equals(string(sec.get("sectionId")))) {
                            Map<String, Object> existingFields = sec.get("fields") instanceof Map<?, ?> ef
                                    ? new LinkedHashMap<>(normalize(ef)) : new LinkedHashMap<>();
                            existingFields.putAll(fields);
                            sec.put("fields", existingFields);
                        }
                    }
                }
            }
            case "section.remove" -> {
                String sectionId = string(action.getOrDefault("sectionId", ""));
                if (sectionId.isBlank()) return;
                final String targetId = sectionId;
                for (Map<String, Object> page : pages) {
                    if (!pageId.isBlank() && !pageId.equals(string(page.get("pageId")))) continue;
                    List<Map<String, Object>> secs = sectionListMutable(page);
                    secs.removeIf(sec -> targetId.equals(string(sec.get("sectionId"))));
                    page.put("sections", secs);
                }
            }
            case "context.set" -> {
                String name = string(action.get("name"));
                if (!name.isBlank()) {
                    initialContext.put(name, action.get("value"));
                }
            }
            // command.dispatch, http.request, list.instantiate, timer.delay,
            // state.transition, platform.capability — ignored during derivation
        }
    }

    // ═══════════════════════════════════════════════════════════
    // Deployment management
    // ═══════════════════════════════════════════════════════════

    public List<Map<String, Object>> listDeployments(String stateMachineId) {
        return deploymentRepository.findByStateMachineIdOrderByDeployedAtDesc(stateMachineId)
                .stream().map(this::toDeploymentMap).toList();
    }

    @Transactional
    public Map<String, Object> createDeployment(String stateMachineId, Map<String, Object> request) {
        StateMachine sm = stateMachine(stateMachineId);

        @SuppressWarnings("unchecked")
        List<String> devices = request.get("devices") instanceof List<?> list
                ? list.stream().map(Object::toString).distinct().toList() : List.of();
        if (devices.isEmpty()) throw new IllegalArgumentException("devices is required (at least one)");

        // ── Pre-flight checks ──

        // 1. Online check — warn but don't block
        List<String> offlineDevices = new ArrayList<>();
        for (String deviceId : devices) {
            if (!deviceSessionManager.isDeviceOnline(deviceId)) {
                offlineDevices.add(deviceId);
            }
        }

        // 2. Board type matching check
        List<String> boardTypes = sm.getBoardTypes() != null ? sm.getBoardTypes() : List.of();
        List<Map<String, Object>> boardTypeWarnings = new ArrayList<>();
        if (!boardTypes.isEmpty()) {
            for (String deviceId : devices) {
                CapabilityRegistry.DeviceTypeInfo deviceType = capabilityRegistry.getDeviceTypeForDevice(deviceId).orElse(null);
                if (deviceType == null) {
                    boardTypeWarnings.add(Map.of(
                            "deviceId", deviceId,
                            "message", "device board type unknown (no capability reported yet)",
                            "severity", "warning"
                    ));
                } else if (!boardTypes.contains(deviceType.board()) && !boardTypes.contains(deviceType.key())) {
                    boardTypeWarnings.add(Map.of(
                            "deviceId", deviceId,
                            "message", "device board type '" + deviceType.board() + "' not in state machine's target board types: " + boardTypes,
                            "severity", "warning"
                    ));
                }
            }
        }

        // 3. Capability warnings: check each device supports the definition's events/commands/sections
        List<Map<String, Object>> capabilityWarnings = buildCapabilityWarnings(sm, devices);

        // Initialize runtime state to the first state in the definition
        String initialId = firstStateId(sm.getDefinition());
        if (initialId.isBlank()) throw new IllegalArgumentException("definition has no states");

        StateMachineDeployment deployment = new StateMachineDeployment();
        deployment.setStateMachineId(stateMachineId);
        deployment.setDevices(new ArrayList<>(devices));
        deployment.setCurrentStateId(initialId);
        // Seed context from initial state's initialContext (populated by auto-derivation)
        Map<String, Object> seedContext = new LinkedHashMap<>();
        Map<String, Object> initialState = findState(sm.getDefinition(), initialId);
        if (initialState != null && initialState.get("initialContext") instanceof Map<?, ?> ic) {
            seedContext.putAll(normalize(ic));
        }
        deployment.setContextData(seedContext);
        deployment.setDeployedAt(LocalDateTime.now());

        // Build pages from the initial state and push to devices
        // Resolve variables in section fields using the initial empty context
        Map<String, Object> initRuntime = Map.of("$context", Map.of(), "$event", Map.of("deviceId", devices.get(0)));
        List<Map<String, Object>> initialPages = buildPagesFromState(
                sm.getDefinition(), initialId, devices, initRuntime);
        List<Map<String, Object>> pushResults = projectionService.projectScene(initialPages);

        Map<String, Object> result = toDeploymentMap(deploymentRepository.save(deployment));
        result.put("pushResults", pushResults);
        result.put("allSent", pushResults.stream().allMatch(r -> Boolean.TRUE.equals(r.get("sent"))));

        // Merge all warnings
        List<Map<String, Object>> allWarnings = new ArrayList<>();
        if (!offlineDevices.isEmpty() && offlineDevices.size() < devices.size()) {
            allWarnings.add(Map.of(
                    "deviceId", String.join(",", offlineDevices),
                    "message", "some selected devices are offline: " + offlineDevices,
                    "severity", "warning"
            ));
        }
        allWarnings.addAll(boardTypeWarnings);
        allWarnings.addAll(capabilityWarnings);
        if (!allWarnings.isEmpty()) {
            result.put("warnings", allWarnings);
        }

        return result;
    }

    /**
     * Build capability mismatch warnings for each device against the state machine definition.
     */
    private List<Map<String, Object>> buildCapabilityWarnings(StateMachine sm, List<String> devices) {
        List<Map<String, Object>> warnings = new ArrayList<>();
        Map<String, Object> def = sm.getDefinition();

        // Collect all used events from transitions
        Set<String> usedEvents = new LinkedHashSet<>();
        Object rawTransitions = def.get("transitions");
        if (rawTransitions instanceof List<?> transitions) {
            for (Object t : transitions) {
                if (t instanceof Map<?, ?> tm) {
                    Map<String, Object> event = tm.get("event") instanceof Map<?, ?> em ? normalize(em) : Map.of();
                    String eventId = string(event.get("eventId"));
                    if (!eventId.isBlank()) usedEvents.add(eventRegistry.resolveEventId(eventId));
                }
            }
        }

        // Collect all used commands from actions
        Set<String> usedCommands = new LinkedHashSet<>();
        if (rawTransitions instanceof List<?> transitions) {
            for (Object t : transitions) {
                if (t instanceof Map<?, ?> tm) {
                    Object rawActions = tm.get("actions");
                    if (rawActions instanceof List<?> actions) {
                        for (Object a : actions) {
                            if (a instanceof Map<?, ?> am) {
                                String type = string(am.get("type"));
                                if ("command.dispatch".equals(type) || "platform.capability".equals(type)) {
                                    String cmdId = string(am.get("commandId"));
                                    if (!cmdId.isBlank()) usedCommands.add(cmdId);
                                }
                            }
                        }
                    }
                }
            }
        }

        // Collect all used section types from state sections
        Set<String> usedSectionTypes = new LinkedHashSet<>();
        Object rawStates = def.get("states");
        if (rawStates instanceof List<?> states) {
            for (Object s : states) {
                if (s instanceof Map<?, ?> smap) {
                    collectSectionTypes(smap, usedSectionTypes);
                }
            }
        }

        for (String deviceId : devices) {
            CapabilityRegistry.DeviceCapabilities caps = capabilityRegistry.getDeviceSnapshot(deviceId).orElse(null);
            if (caps == null) continue;

            List<String> missingEvents = new ArrayList<>();
            for (String eventId : usedEvents) {
                if (!caps.supportsEvent(eventId) && !eventId.startsWith("system:") && !eventId.startsWith("command.")) {
                    missingEvents.add(eventId);
                }
            }
            List<String> missingCommands = new ArrayList<>();
            for (String cmd : usedCommands) {
                if (!caps.outputCommands().contains(cmd)) {
                    missingCommands.add(cmd);
                }
            }
            List<String> missingSections = new ArrayList<>();
            for (String st : usedSectionTypes) {
                if (!caps.sectionTypes().contains(st)) {
                    missingSections.add(st);
                }
            }

            if (!missingEvents.isEmpty() || !missingCommands.isEmpty() || !missingSections.isEmpty()) {
                StringBuilder msg = new StringBuilder("device " + deviceId + " missing capabilities: ");
                if (!missingEvents.isEmpty()) msg.append("events: ").append(missingEvents).append("; ");
                if (!missingCommands.isEmpty()) msg.append("commands: ").append(missingCommands).append("; ");
                if (!missingSections.isEmpty()) msg.append("sections: ").append(missingSections).append("; ");
                warnings.add(Map.of(
                        "deviceId", deviceId,
                        "message", msg.toString().trim(),
                        "severity", "warning",
                        "missingEvents", missingEvents,
                        "missingCommands", missingCommands,
                        "missingSections", missingSections
                ));
            }
        }
        return warnings;
    }

    /**
     * Recursively collect section types from a state or page map.
     */
    private void collectSectionTypes(Map<?, ?> container, Set<String> types) {
        // Collect from pages array (new multi-page format)
        Object rawPages = container.get("pages");
        if (rawPages instanceof List<?> pages) {
            for (Object p : pages) {
                if (p instanceof Map<?, ?> pm) {
                    collectSectionsFromList(pm.get("sections"), types);
                }
            }
        }
        // Collect from legacy sections array
        collectSectionsFromList(container.get("sections"), types);
    }

    private void collectSectionsFromList(Object rawSections, Set<String> types) {
        if (rawSections instanceof List<?> sections) {
            for (Object sec : sections) {
                if (sec instanceof Map<?, ?> smap) {
                    String st = string(smap.get("sectionType"));
                    if (!st.isBlank()) types.add(st);
                }
            }
        }
    }

    @Transactional
    public Map<String, Object> deleteDeployment(String stateMachineId, String deployId) {
        StateMachineDeployment deployment = deploymentRepository.findById(deployId)
                .orElseThrow(() -> new IllegalArgumentException("deployment not found: " + deployId));
        if (!deployment.getStateMachineId().equals(stateMachineId)) {
            throw new IllegalArgumentException("deployment does not belong to state machine: " + stateMachineId);
        }
        deploymentRepository.delete(deployment);
        return Map.of("deleted", true, "id", deployId, "stateMachineId", stateMachineId);
    }

    // ═══════════════════════════════════════════════════════════
    // Trigger (manual)
    // ═══════════════════════════════════════════════════════════

    @Transactional
    public Map<String, Object> trigger(String stateMachineId, Map<String, Object> request) {
        @SuppressWarnings("unchecked")
        Map<String, Object> event = request.get("event") instanceof Map<?, ?> map
                ? normalize(map) : normalize(request);
        String deviceId = string(event.get("deviceId"));
        String eventId = eventRegistry.resolveEventId(string(event.get("eventId")));
        if (eventId == null || eventId.isBlank()) throw new IllegalArgumentException("event.eventId is required");

        // Find deployments for this state machine that contain the device
        List<StateMachineDeployment> deployments = deploymentRepository
                .findByStateMachineIdOrderByDeployedAtDesc(stateMachineId);
        StateMachineDeployment targetDeployment = null;
        if (!deviceId.isBlank()) {
            for (StateMachineDeployment dep : deployments) {
                if (dep.getDevices().contains(deviceId)) {
                    targetDeployment = dep;
                    break;
                }
            }
            if (targetDeployment == null) {
                throw new IllegalArgumentException("device " + deviceId + " is not in any deployment of this state machine");
            }
        } else {
            targetDeployment = deployments.isEmpty() ? null : deployments.get(0);
            if (targetDeployment == null) {
                throw new IllegalArgumentException("state machine has no deployments");
            }
            deviceId = targetDeployment.getDevices().isEmpty() ? "" : targetDeployment.getDevices().get(0);
        }

        StateMachine sm = stateMachine(stateMachineId);
        event.put("deviceId", deviceId);
        return triggerDeployment(sm, targetDeployment, event, "MANUAL");
    }

    // ═══════════════════════════════════════════════════════════
    // Device event handling (called by event bridges)
    // ═══════════════════════════════════════════════════════════

    @Transactional
    public List<Map<String, Object>> handleDeviceEvent(EventPayload payload) {
        Map<String, Object> event = payload.toLegacyMap();
        event.put("eventId", payload.eventId());
        event.put("deviceId", payload.deviceId());
        if (!payload.pageId().isBlank()) event.put("pageId", payload.pageId());
        if (!payload.sectionId().isBlank()) event.put("sectionId", payload.sectionId());
        if (!payload.nodeId().isBlank()) event.put("nodeId", payload.nodeId());
        return triggerByDevice(payload.deviceId(), event, "DEVICE_EVENT");
    }

    @Transactional
    public List<Map<String, Object>> handleCommandEvent(CommandLifecycleEvent cmdEvent) {
        return triggerByDevice(cmdEvent.deviceId(), new LinkedHashMap<>(cmdEvent.payload()), "COMMAND_EVENT");
    }

    // ═══════════════════════════════════════════════════════════
    // Internal: trigger flow
    // ═══════════════════════════════════════════════════════════

    private List<Map<String, Object>> triggerByDevice(String deviceId, Map<String, Object> event, String source) {
        if (deviceId == null || deviceId.isBlank()) return List.of();
        List<Map<String, Object>> results = new ArrayList<>();

        for (StateMachineDeployment deployment : deploymentRepository.findByDeviceId(deviceId)) {
            try {
                StateMachine sm = stateMachineRepository.findById(deployment.getStateMachineId()).orElse(null);
                if (sm == null) continue;
                results.add(triggerDeployment(sm, deployment, event, source));
            } catch (Exception e) {
                log.error("Trigger failed for deployment {} device {}: {}", deployment.getId(), deviceId, e.getMessage());
                results.add(Map.of("deploymentId", deployment.getId(), "error", e.getMessage()));
            }
        }
        return results;
    }

    /**
     * Core trigger logic: find matching transition, execute actions, update deployment state.
     *
     * Section actions (add/update/remove) modify pages in-place during the transition chain.
     * After each chain iteration, the projection layer diffs previous vs. current pages
     * and sends patches or full scenes to devices.
     */
    private Map<String, Object> triggerDeployment(StateMachine sm, StateMachineDeployment deployment,
                                                   Map<String, Object> event, String source) {
        String eventId = eventRegistry.resolveEventId(string(event.get("eventId")));
        if (eventId == null || eventId.isBlank()) {
            throw new IllegalArgumentException("event.eventId is required");
        }

        long started = System.currentTimeMillis();
        Map<String, Object> runtime = runtimeContext(deployment, event);
        event.put("source", source);
        event.put("triggerDeviceId", runtime.get("triggerDeviceId"));

        Map<String, Object> output = new LinkedHashMap<>();
        List<Map<String, Object>> allActions = new ArrayList<>();
        List<Map<String, Object>> allProjections = new ArrayList<>();
        List<String> allTransitionIds = new ArrayList<>();

        // Build initial pages from the current state with runtime variable resolution
        List<Map<String, Object>> currentPages = buildPagesFromState(
                sm.getDefinition(), deployment.getCurrentStateId(), deployment.getDevices(), runtime);
        List<Map<String, Object>> previousPages = deepCopyPages(currentPages);

        int chainLimit = 100;
        int chainCount = 0;
        String currentEventId = eventId;

        try {
            while (chainCount < chainLimit) {
                chainCount++;
                Map<String, Object> transition = findTransition(sm.getDefinition(), deployment.getCurrentStateId(), currentEventId)
                        .orElse(null);
                if (transition == null) {
                    if (chainCount == 1) {
                        output.put("matched", false);
                        output.put("reason", "no transition matched from state '" + deployment.getCurrentStateId()
                                + "' for event '" + currentEventId + "'");
                    }
                    break;
                }

                allTransitionIds.add(string(transition.get("id")));
                if (chainCount == 1) output.put("matched", true);

                // Snapshot pages before actions (for diffing after action execution)
                previousPages = deepCopyPages(currentPages);

                // Execute actions — section actions modify currentPages in-place
                List<Map<String, Object>> actions = executeActions(
                        deployment, sm.getDefinition(), transition, runtime, currentPages);
                allActions.addAll(actions);

                // Project section changes that happened during actions (before state transition)
                List<Map<String, Object>> actionProjections = projectionService.projectTransition(
                        previousPages, currentPages);
                allProjections.addAll(actionProjections);

                // State transition: rebuild pages from the target state's definition
                String toStateId = string(transition.get("toStateId"));
                if (!toStateId.isBlank()) {
                    List<Map<String, Object>> preTransitionPages = deepCopyPages(currentPages);
                    deployment.setCurrentStateId(toStateId);
                    currentPages = buildPagesFromState(
                            sm.getDefinition(), toStateId, deployment.getDevices(), runtime);
                    List<Map<String, Object>> stateProjections = projectionService.projectTransition(
                            preTransitionPages, currentPages);
                    allProjections.addAll(stateProjections);
                }

                // Chain to next transition if state.transition action is pending
                Object pendingEventId = runtime.remove("__pendingEventId__");
                if (pendingEventId == null) break;
                @SuppressWarnings("unchecked")
                Map<String, Object> pendingEvent = (Map<String, Object>) runtime.remove("__pendingEvent__");
                if (pendingEvent != null) runtime = runtimeContext(deployment, pendingEvent);
                currentEventId = string(pendingEventId);
            }

            output.put("actions", allActions);
            output.put("commandResults", commandResults(allActions));
            output.put("projectionResults", allProjections);
            output.put("chainCount", chainCount);
            output.put("transitionIds", allTransitionIds);
            output.put("stateMachineId", sm.getId());
            output.put("triggerDeviceId", runtime.get("triggerDeviceId"));
            output.put("currentStateId", deployment.getCurrentStateId());
            output.put("context", deployment.getContextData());
            output.put("deploymentId", deployment.getId());

            deploymentRepository.save(deployment);
            output.put("durationMs", System.currentTimeMillis() - started);
            output.put("status", "SUCCEEDED");
            return output;
        } catch (Exception e) {
            output.put("error", e.getMessage());
            output.put("durationMs", System.currentTimeMillis() - started);
            output.put("status", "FAILED");
            throw e;
        }
    }

    // ═══════════════════════════════════════════════════════════
    // Internal: actions
    // ═══════════════════════════════════════════════════════════

    /**
     * Execute all actions in a transition. Section actions modify currentPages in-place.
     */
    private List<Map<String, Object>> executeActions(StateMachineDeployment deployment,
                                                      Map<String, Object> definition,
                                                      Map<String, Object> transition,
                                                      Map<String, Object> runtime,
                                                      List<Map<String, Object>> currentPages) {
        Object rawActions = transition.get("actions");
        if (!(rawActions instanceof List<?> actions)) return List.of();

        List<Map<String, Object>> results = new ArrayList<>();
        for (Object rawAction : actions) {
            if (!(rawAction instanceof Map<?, ?> map)) continue;
            Map<String, Object> rawActionMap = normalize(map);
            String type = string(rawActionMap.get("type"));

            Map<String, Object> stashedItemTemplate = null;
            if ("list.instantiate".equals(type)) {
                stashedItemTemplate = rawActionMap.get("itemTemplate") instanceof Map<?, ?> tm
                        ? normalize((Map<?, ?>) tm) : null;
                rawActionMap.remove("itemTemplate");
            }

            Map<String, Object> action = resolveValues(rawActionMap, runtime);
            if (stashedItemTemplate != null) action.put("itemTemplate", stashedItemTemplate);

            Map<String, Object> result = new LinkedHashMap<>();
            result.put("type", type);
            switch (type) {
                case "context.set" -> executeContextSet(deployment, action, result);
                case "section.add", "section.update", "section.remove" ->
                        executeSectionAction(deployment, definition, action, result, currentPages, runtime);
                case "command.dispatch", "platform.capability" -> executeCommandAction(deployment, action, runtime, result);
                case "http.request" -> executeHttpRequest(action, runtime, result);
                case "list.instantiate" -> executeListInstantiate(deployment, definition, action, result, currentPages, runtime);
                case "timer.delay" -> executeTimerDelay(action, result);
                case "state.transition" -> executeStateTransition(action, runtime, result);
                default -> {
                    result.put("status", "SKIPPED");
                    result.put("reason", "unsupported action type");
                }
            }
            runtime.put("$prev", result);
            runtime.put("$context", deployment.getContextData());
            results.add(result);
        }
        return results;
    }

    // ═══════════════════════════════════════════════════════════
    // Action executors
    // ═══════════════════════════════════════════════════════════

    private void executeContextSet(StateMachineDeployment deployment, Map<String, Object> action, Map<String, Object> result) {
        String name = string(action.get("name"));
        if (name.isBlank()) { result.put("status", "ERROR"); result.put("reason", "name is required"); return; }
        Map<String, Object> context = new LinkedHashMap<>(deployment.getContextData());
        context.put(name, action.get("value"));
        deployment.setContextData(context);
        result.put("status", "SUCCEEDED");
        result.put("name", name);
    }

    /**
     * Execute section.add / section.update / section.remove by modifying currentPages in-place.
     * The projection layer will diff and send patches after all actions run.
     */
    private void executeSectionAction(StateMachineDeployment deployment, Map<String, Object> definition,
                                       Map<String, Object> action, Map<String, Object> result,
                                       List<Map<String, Object>> currentPages, Map<String, Object> runtime) {
        String type = string(action.get("type"));
        String pageId = string(action.getOrDefault("pageId", ""));
        Map<String, Object> fields = action.get("fields") instanceof Map<?, ?> fm
                ? normalize(fm) : Map.of();

        result.put("status", "SUCCEEDED");

        switch (type) {
            case "section.add" -> executeSectionAdd(action, result, currentPages, pageId, fields, runtime);
            case "section.update" -> executeSectionUpdate(action, result, currentPages, pageId, fields, runtime);
            case "section.remove" -> executeSectionRemove(action, result, currentPages, pageId);
        }
    }

    private void executeSectionAdd(Map<String, Object> action, Map<String, Object> result,
                                    List<Map<String, Object>> currentPages, String pageId,
                                    Map<String, Object> fields, Map<String, Object> runtime) {
        String sectionType = string(action.getOrDefault("sectionType", ""));
        String sectionId = string(action.getOrDefault("sectionId", ""));
        if (sectionType.isBlank()) {
            result.put("status", "ERROR");
            result.put("reason", "sectionType is required for section.add");
            return;
        }
        if (sectionId.isBlank()) {
            sectionId = "sec_" + UUID.randomUUID().toString().substring(0, 8);
        }
        if (!eventRegistry.isKnownSectionType(sectionType)) {
            result.put("status", "ERROR");
            result.put("reason", "unknown section type: " + sectionType);
            return;
        }
        Map<String, Object> resolvedFields = resolveValues(fields, runtime);
        int added = 0;
        for (Map<String, Object> page : currentPages) {
            if (!pageId.isBlank() && !pageId.equals(string(page.get("pageId")))) continue;
            Map<String, Object> newSection = new LinkedHashMap<>();
            newSection.put("sectionId", sectionId);
            newSection.put("sectionType", sectionType);
            newSection.put("fields", resolvedFields);
            sectionListMutable(page).add(newSection);
            added++;
        }
        result.put("sectionId", sectionId);
        result.put("sectionType", sectionType);
        result.put("addedToPages", added);
        log.debug("section.add {} (type={}) to {} pages", sectionId, sectionType, added);
    }

    private void executeSectionUpdate(Map<String, Object> action, Map<String, Object> result,
                                       List<Map<String, Object>> currentPages, String pageId,
                                       Map<String, Object> fields, Map<String, Object> runtime) {
        String sectionId = string(action.getOrDefault("sectionId", ""));
        if (sectionId.isBlank()) {
            result.put("status", "ERROR");
            result.put("reason", "sectionId is required for section.update");
            return;
        }
        Map<String, Object> resolvedFields = resolveValues(fields, runtime);
        int updated = 0;
        for (Map<String, Object> page : currentPages) {
            if (!pageId.isBlank() && !pageId.equals(string(page.get("pageId")))) continue;
            for (Map<String, Object> sec : sectionListMutable(page)) {
                if (sectionId.equals(string(sec.get("sectionId")))) {
                    @SuppressWarnings("unchecked")
                    Map<String, Object> existingFields = sec.get("fields") instanceof Map<?, ?> ef
                            ? new LinkedHashMap<>(normalize(ef)) : new LinkedHashMap<>();
                    existingFields.putAll(resolvedFields);
                    sec.put("fields", existingFields);
                    updated++;
                }
            }
        }
        result.put("sectionId", sectionId);
        result.put("updatedInPages", updated);
        if (updated == 0) {
            result.put("status", "WARNING");
            result.put("reason", "section " + sectionId + " not found in any target page");
        }
        log.debug("section.update {} in {} pages ({} fields)", sectionId, updated, resolvedFields.size());
    }

    private void executeSectionRemove(Map<String, Object> action, Map<String, Object> result,
                                       List<Map<String, Object>> currentPages, String pageId) {
        String sectionId = string(action.getOrDefault("sectionId", ""));
        if (sectionId.isBlank()) {
            result.put("status", "ERROR");
            result.put("reason", "sectionId is required for section.remove");
            return;
        }
        final String targetSectionId = sectionId; // effectively final for lambda
        int removed = 0;
        for (Map<String, Object> page : currentPages) {
            if (!pageId.isBlank() && !pageId.equals(string(page.get("pageId")))) continue;
            List<Map<String, Object>> sections = sectionListMutable(page);
            boolean wasRemoved = sections.removeIf(sec -> targetSectionId.equals(string(sec.get("sectionId"))));
            if (wasRemoved) removed++;
        }
        result.put("sectionId", targetSectionId);
        result.put("removedFromPages", removed);
        if (removed == 0) {
            result.put("status", "WARNING");
            result.put("reason", "section " + targetSectionId + " not found in any target page");
        }
        log.debug("section.remove {} from {} pages", targetSectionId, removed);
    }

    private void executeCommandAction(StateMachineDeployment deployment, Map<String, Object> action,
                                       Map<String, Object> runtime, Map<String, Object> result) {
        String commandId = string(action.get("commandId"));
        Map<String, Object> params = action.get("params") instanceof Map<?, ?> pm ? normalize(pm) : Map.of();
        EventRegistry.ValidationResult validation = eventRegistry.validateCommand(commandId, params);
        result.put("commandId", commandId);
        result.put("validated", validation.valid());
        result.put("errors", validation.errors().stream().map(EventRegistry.ValidationError::toMap).toList());
        if (!validation.valid()) { result.put("status", "ERROR"); return; }

        List<String> devices = new ArrayList<>(deployment.getDevices());
        List<Map<String, Object>> dispatches = new ArrayList<>();
        for (String deviceId : devices) {
            SduiControlDispatchResult dispatch = commandService.dispatchCommand(deviceId, commandId, params);
            dispatches.add(Map.of(
                    "deviceId", deviceId, "cmdId", dispatch.cmdId(),
                    "sent", dispatch.sent(), "status", dispatch.status(),
                    "action", dispatch.action() != null ? dispatch.action() : commandId
            ));
        }
        result.put("status", devices.isEmpty() ? "SKIPPED" : "DISPATCHED");
        result.put("targetDevices", devices);
        result.put("dispatches", dispatches);
    }

    @SuppressWarnings("unchecked")
    private void executeHttpRequest(Map<String, Object> action, Map<String, Object> runtime, Map<String, Object> result) {
        String url = string(action.get("url"));
        if (url.isBlank()) { result.put("status", "ERROR"); result.put("reason", "url is required"); return; }
        String method = string(action.get("method"));
        if (method.isBlank()) method = "GET";
        method = method.toUpperCase();

        Map<String, Object> headers = action.get("headers") instanceof Map<?, ?> h ? normalize((Map<?, ?>) h) : Map.of();
        Object body = action.get("body");

        try {
            HttpHeaders httpHeaders = new HttpHeaders();
            httpHeaders.setContentType(MediaType.APPLICATION_JSON);
            headers.forEach((k, v) -> httpHeaders.set(k, String.valueOf(v)));
            HttpEntity<Object> entity = new HttpEntity<>(body, httpHeaders);
            ResponseEntity<Object> response = switch (method) {
                case "GET" -> restTemplate.exchange(url, HttpMethod.GET, entity, Object.class);
                case "POST" -> restTemplate.exchange(url, HttpMethod.POST, entity, Object.class);
                case "PUT" -> restTemplate.exchange(url, HttpMethod.PUT, entity, Object.class);
                case "DELETE" -> restTemplate.exchange(url, HttpMethod.DELETE, entity, Object.class);
                case "PATCH" -> restTemplate.exchange(url, HttpMethod.PATCH, entity, Object.class);
                default -> { result.put("status", "ERROR"); result.put("reason", "unsupported HTTP method: " + method); yield null; }
            };
            if (response == null) return;
            Map<String, Object> httpResult = new LinkedHashMap<>();
            httpResult.put("status", response.getStatusCode().value());
            httpResult.put("headers", response.getHeaders().toSingleValueMap());
            httpResult.put("body", response.getBody());
            runtime.put("$http", Map.of("response", httpResult));
            result.put("status", "SUCCEEDED");
            result.put("httpStatus", response.getStatusCode().value());
        } catch (Exception e) {
            log.warn("HTTP action failed for url {}: {}", url, e.getMessage());
            runtime.put("$http", Map.of("response", Map.of("status", 0, "headers", Map.of(), "body", e.getMessage())));
            result.put("status", "ERROR");
            result.put("reason", e.getMessage());
        }
    }

    @SuppressWarnings("unchecked")
    private void executeListInstantiate(StateMachineDeployment deployment, Map<String, Object> definition,
                                         Map<String, Object> action, Map<String, Object> result,
                                         List<Map<String, Object>> currentPages, Map<String, Object> runtime) {
        String sectionType = string(action.get("sectionType"));
        String itemsSource = string(action.get("itemsSource"));
        String pageId = string(action.getOrDefault("pageId", ""));
        Map<String, Object> itemTemplate = action.get("itemTemplate") instanceof Map<?, ?> tm
                ? normalize(tm) : Map.of();

        if (sectionType.isBlank()) {
            result.put("status", "ERROR"); result.put("reason", "sectionType is required for list.instantiate"); return;
        }
        if (!eventRegistry.isKnownSectionType(sectionType)) {
            result.put("status", "ERROR"); result.put("reason", "unknown section type: " + sectionType); return;
        }
        if (itemsSource.isBlank()) {
            result.put("status", "ERROR"); result.put("reason", "itemsSource is required for list.instantiate"); return;
        }

        // Resolve the items source from runtime variables (e.g. $http.response.body.items)
        Object itemsObj = resolvePath(itemsSource, runtime);
        if (!(itemsObj instanceof List<?> items)) {
            result.put("status", "ERROR");
            result.put("reason", "itemsSource '" + itemsSource + "' did not resolve to a list; got: " + (itemsObj != null ? itemsObj.getClass().getSimpleName() : "null"));
            return;
        }

        int totalAdded = 0;
        for (Object item : items) {
            // Build a runtime context with $row pointing to the current item
            Map<String, Object> itemRuntime = new LinkedHashMap<>(runtime);
            itemRuntime.put("$row", item instanceof Map<?, ?> im ? normalize(im) : Map.of("value", item));
            // Resolve the item template fields against the per-item runtime
            Map<String, Object> resolvedTemplate = resolveValues(itemTemplate, itemRuntime);

            String sectionId = "list_" + UUID.randomUUID().toString().substring(0, 8);
            for (Map<String, Object> page : currentPages) {
                if (!pageId.isBlank() && !pageId.equals(string(page.get("pageId")))) continue;
                Map<String, Object> newSection = new LinkedHashMap<>();
                newSection.put("sectionId", sectionId);
                newSection.put("sectionType", sectionType);
                newSection.put("fields", new LinkedHashMap<>(resolvedTemplate));
                sectionListMutable(page).add(newSection);
                totalAdded++;
            }
        }

        result.put("status", "SUCCEEDED");
        result.put("itemsProcessed", items.size());
        result.put("sectionsAdded", totalAdded);
        result.put("sectionType", sectionType);
        log.debug("list.instantiate: {} items → {} sections of type {}", items.size(), totalAdded, sectionType);
    }

    private void executeTimerDelay(Map<String, Object> action, Map<String, Object> result) {
        int durationMs = intValue(action.get("durationMs"), -1);
        if (durationMs < 0) { result.put("status", "ERROR"); result.put("reason", "durationMs is required"); return; }
        try { Thread.sleep(durationMs); result.put("status", "SUCCEEDED"); result.put("delayed", durationMs); }
        catch (InterruptedException e) { Thread.currentThread().interrupt(); result.put("status", "ERROR"); result.put("reason", "timer interrupted"); }
    }

    private void executeStateTransition(Map<String, Object> action, Map<String, Object> runtime, Map<String, Object> result) {
        String eventId = string(action.get("eventId"));
        if (eventId.isBlank()) { result.put("status", "ERROR"); result.put("reason", "eventId is required"); return; }
        @SuppressWarnings("unchecked")
        Map<String, Object> pendingEvent = action.get("event") instanceof Map<?, ?> em ? normalize((Map<?, ?>) em) : Map.of();
        if (!pendingEvent.containsKey("deviceId")) pendingEvent.put("deviceId", runtime.get("triggerDeviceId"));
        if (!pendingEvent.containsKey("eventId")) pendingEvent.put("eventId", eventId);
        runtime.put("__pendingEventId__", eventId);
        runtime.put("__pendingEvent__", pendingEvent);
        result.put("status", "SUCCEEDED");
        result.put("pendingEventId", eventId);
    }

    // ═══════════════════════════════════════════════════════════
    // Internal: page building from definition states
    // ═══════════════════════════════════════════════════════════

    /**
     * Build runtime page objects from a state's page definitions.
     *
     * Supports two formats:
     * - New multi-page format: state has a "pages" array, each with pageId, layout, sections.
     * - Legacy format: state has a "sections" array → single page with pageId="main".
     *
     * Section field values are resolved using the current runtime context.
     */
    private List<Map<String, Object>> buildPagesFromState(Map<String, Object> definition, String stateId,
                                                          List<String> devices, Map<String, Object> runtime) {
        Map<String, Object> state = findState(definition, stateId);
        if (state == null) return List.of();

        List<String> deviceList = new ArrayList<>(devices);

        // New multi-page format
        Object rawPages = state.get("pages");
        if (rawPages instanceof List<?> pageList && !pageList.isEmpty()) {
            List<Map<String, Object>> pages = new ArrayList<>();
            for (Object p : pageList) {
                if (!(p instanceof Map<?, ?> pm)) continue;
                pages.add(buildSinglePage(normalize(pm), deviceList, runtime));
            }
            return pages;
        }

        // Legacy format: state-level sections → single page "main"
        Map<String, Object> page = buildLegacyPage(state, deviceList, runtime);
        return new ArrayList<>(List.of(page));
    }

    /**
     * Build a single page from the new multi-page format entry.
     */
    private Map<String, Object> buildSinglePage(Map<String, Object> pageDef, List<String> devices,
                                                 Map<String, Object> runtime) {
        String pageId = string(pageDef.get("pageId"));
        if (pageId.isBlank()) pageId = "page_" + UUID.randomUUID().toString().substring(0, 6);
        Map<String, Object> page = new LinkedHashMap<>();
        page.put("pageId", pageId);
        page.put("layout", string(pageDef.getOrDefault("layout", "vertical_scroll")));
        page.put("autoScroll", Boolean.TRUE.equals(pageDef.get("autoScroll")));
        page.put("autoScrollMs", intValue(pageDef.get("autoScrollMs"), 0));
        page.put("devices", new ArrayList<>(devices));
        page.put("sections", buildSectionsFromDef(pageDef, runtime));
        return page;
    }

    /**
     * Build a single page from legacy state-level sections.
     */
    private Map<String, Object> buildLegacyPage(Map<String, Object> state, List<String> devices,
                                                 Map<String, Object> runtime) {
        Map<String, Object> page = new LinkedHashMap<>();
        page.put("pageId", "main");
        page.put("layout", string(state.getOrDefault("layout", "vertical_scroll")));
        page.put("autoScroll", Boolean.TRUE.equals(state.get("autoScroll")));
        page.put("autoScrollMs", intValue(state.get("autoScrollMs"), 0));
        page.put("devices", new ArrayList<>(devices));
        page.put("sections", buildSectionsFromDef(state, runtime));
        return page;
    }

    /**
     * Build section list from a state or page definition, resolving runtime variables in fields.
     */
    private List<Map<String, Object>> buildSectionsFromDef(Map<String, Object> container, Map<String, Object> runtime) {
        List<Map<String, Object>> sections = new ArrayList<>();
        Object rawSections = container.get("sections");
        if (!(rawSections instanceof List<?> list)) return sections;
        for (Object item : list) {
            if (!(item instanceof Map<?, ?> map)) continue;
            Map<String, Object> section = new LinkedHashMap<>(normalize(map));
            // Resolve runtime variables in section fields
            if (section.get("fields") instanceof Map<?, ?> f) {
                section.put("fields", resolveValues(normalize(f), runtime));
            }
            sections.add(section);
        }
        return sections;
    }

    private Map<String, Object> findState(Map<String, Object> definition, String stateId) {
        List<Map<String, Object>> states = stateList(definition);
        return states.stream().filter(s -> stateId.equals(string(s.get("id")))).findFirst().orElse(null);
    }

    // ═══════════════════════════════════════════════════════════
    // Internal: runtime context
    // ═══════════════════════════════════════════════════════════

    Map<String, Object> runtimeContext(StateMachineDeployment deployment, Map<String, Object> event) {
        String triggerDeviceId = string(event.get("deviceId"));
        Map<String, Object> runtime = new LinkedHashMap<>();
        runtime.put("$event", event);
        runtime.put("$context", deployment.getContextData());
        runtime.put("triggerDeviceId", triggerDeviceId);
        runtime.put("$trigger_device", triggerDeviceId);
        return runtime;
    }

    // ═══════════════════════════════════════════════════════════
    // Internal: variable resolution
    // ═══════════════════════════════════════════════════════════

    Map<String, Object> resolveValues(Map<String, Object> source, Map<String, Object> runtime) {
        Map<String, Object> result = new LinkedHashMap<>();
        source.forEach((key, value) -> result.put(key, resolveValue(value, runtime)));
        return result;
    }

    @SuppressWarnings("unchecked")
    private Object resolveValue(Object value, Map<String, Object> runtime) {
        if (value instanceof String text) {
            if (text.startsWith("$") && !text.contains("${")) return resolvePath(text, runtime);
            if (text.contains("${")) return interpolate(text, runtime);
            return text;
        }
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> result = new LinkedHashMap<>();
            map.forEach((key, item) -> result.put(String.valueOf(key), resolveValue(item, runtime)));
            return result;
        }
        if (value instanceof List<?> list) return list.stream().map(item -> resolveValue(item, runtime)).toList();
        return value;
    }

    private Object interpolate(String text, Map<String, Object> runtime) {
        if (text.startsWith("${") && text.endsWith("}") && text.indexOf("${", 2) < 0) {
            return resolveInterpolationPath(text.substring(2, text.length() - 1), runtime);
        }
        StringBuilder result = new StringBuilder();
        int pos = 0;
        while (pos < text.length()) {
            int start = text.indexOf("${", pos);
            if (start < 0) { result.append(text, pos, text.length()); break; }
            result.append(text, pos, start);
            int end = text.indexOf("}", start + 2);
            if (end < 0) { result.append(text, start, text.length()); break; }
            Object resolved = resolveInterpolationPath(text.substring(start + 2, end), runtime);
            result.append(resolved != null ? resolved : "");
            pos = end + 1;
        }
        return result.toString();
    }

    private Object resolveInterpolationPath(String path, Map<String, Object> runtime) {
        if (path.startsWith("event.")) return resolvePath("$event." + path.substring(6), runtime);
        if (path.startsWith("ctx.")) return resolvePath("$context." + path.substring(4), runtime);
        if (path.startsWith("prev.")) return resolvePath("$prev." + path.substring(5), runtime);
        if ("trigger_device".equals(path)) return runtime.get("$trigger_device");
        return resolvePath("$" + path, runtime);
    }

    private Object resolvePath(String path, Map<String, Object> runtime) {
        if (runtime.containsKey(path)) return runtime.get(path);
        int dot = path.indexOf('.');
        String root = dot > 0 ? path.substring(0, dot) : path;
        Object current = runtime.get(root);
        if (dot < 0) return current != null ? current : path;
        for (String part : path.substring(dot + 1).split("\\.")) {
            if (current instanceof Map<?, ?> map) current = map.get(part); else return null;
        }
        return current;
    }

    // ═══════════════════════════════════════════════════════════
    // Internal: transition matching
    // ═══════════════════════════════════════════════════════════

    @SuppressWarnings("unchecked")
    Optional<Map<String, Object>> findTransition(Map<String, Object> definition, String currentStateId, String eventId) {
        Object raw = definition.get("transitions");
        if (!(raw instanceof List<?> transitions)) return Optional.empty();
        List<Map<String, Object>> matches = new ArrayList<>();
        int idx = 0;
        for (Object t : transitions) {
            if (!(t instanceof Map<?, ?> map)) { idx++; continue; }
            Map<String, Object> transition = normalize(map);
            String fromStateId = string(transition.get("fromStateId"));
            if (!fromStateId.isBlank() && !fromStateId.equals(currentStateId)) { idx++; continue; }
            Map<String, Object> event = transition.get("event") instanceof Map<?, ?> em ? normalize(em) : Map.of();
            if (eventId.equals(eventRegistry.resolveEventId(string(event.get("eventId"))))) {
                transition.put("__originalIndex__", idx);
                matches.add(transition);
            }
            idx++;
        }
        if (matches.isEmpty()) return Optional.empty();
        matches.sort(Comparator
                .comparingInt((Map<String, Object> t) -> intValue(t.get("priority"), 0)).reversed()
                .thenComparingInt(t -> (Integer) t.get("__originalIndex__")));
        return Optional.of(matches.get(0));
    }

    // ═══════════════════════════════════════════════════════════
    // Internal: helpers
    // ═══════════════════════════════════════════════════════════

    private StateMachine stateMachine(String id) {
        return stateMachineRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("state machine not found: " + id));
    }

    private String firstStateId(Map<String, Object> definition) {
        List<Map<String, Object>> states = stateList(definition);
        return states.isEmpty() ? "" : string(states.get(0).get("id"));
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> commandResults(List<Map<String, Object>> actions) {
        return actions.stream()
                .filter(a -> "command.dispatch".equals(a.get("type")) || "platform.capability".equals(a.get("type")))
                .filter(a -> a.get("dispatches") instanceof List<?>)
                .flatMap(a -> ((List<Map<String, Object>>) a.get("dispatches")).stream())
                .toList();
    }

    private List<Map<String, Object>> deepCopyPages(List<Map<String, Object>> pages) {
        List<Map<String, Object>> copy = new ArrayList<>();
        for (Map<String, Object> page : pages) {
            Map<String, Object> pageCopy = new LinkedHashMap<>(page);
            pageCopy.put("devices", new ArrayList<>(devicesFromPage(page)));
            pageCopy.put("sections", deepCopySections(sectionList(page)));
            copy.add(pageCopy);
        }
        return copy;
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> deepCopySections(List<Map<String, Object>> sections) {
        List<Map<String, Object>> copy = new ArrayList<>();
        for (Map<String, Object> section : sections) {
            Map<String, Object> sc = new LinkedHashMap<>(section);
            if (section.get("fields") instanceof Map<?, ?> f) sc.put("fields", new LinkedHashMap<>(normalize(f)));
            copy.add(sc);
        }
        return copy;
    }

    @SuppressWarnings("unchecked")
    List<Map<String, Object>> stateSections(Map<String, Object> state) {
        Object raw = state.get("sections");
        if (!(raw instanceof List<?> list)) return new ArrayList<>();
        List<Map<String, Object>> sections = new ArrayList<>();
        for (Object item : list) {
            if (item instanceof Map<?, ?> map) sections.add(normalize(map));
        }
        return sections;
    }

    @SuppressWarnings("unchecked")
    List<Map<String, Object>> sectionList(Map<String, Object> page) {
        Object raw = page.get("sections");
        if (!(raw instanceof List<?> list)) return new ArrayList<>();
        List<Map<String, Object>> sections = new ArrayList<>();
        for (Object item : list) {
            if (item instanceof Map<?, ?> map) sections.add(normalize(map));
        }
        return sections;
    }

    /**
     * Returns the mutable sections list from a page, for in-place modification by section actions.
     * If the sections key is missing or not a list, creates and sets a new ArrayList.
     */
    @SuppressWarnings("unchecked")
    List<Map<String, Object>> sectionListMutable(Map<String, Object> page) {
        Object raw = page.get("sections");
        if (raw instanceof List<?> list) {
            // Ensure the list contains mutable maps
            List<Map<String, Object>> result = new ArrayList<>();
            for (Object item : list) {
                if (item instanceof Map<?, ?> map) {
                    result.add(new LinkedHashMap<>(normalize(map)));
                }
            }
            page.put("sections", result);
            return result;
        }
        List<Map<String, Object>> newList = new ArrayList<>();
        page.put("sections", newList);
        return newList;
    }

    @SuppressWarnings("unchecked")
    List<Map<String, Object>> stateList(Map<String, Object> definition) {
        Object raw = definition.get("states");
        if (!(raw instanceof List<?> list)) return new ArrayList<>();
        List<Map<String, Object>> states = new ArrayList<>();
        for (Object item : list) {
            if (item instanceof Map<?, ?> map) states.add(normalize((Map<?, ?>) map));
        }
        return states;
    }

    /**
     * Returns the mutable states list from the definition, for in-place modification.
     */
    @SuppressWarnings("unchecked")
    List<Map<String, Object>> stateListMutable(Map<String, Object> definition) {
        Object raw = definition.get("states");
        List<Map<String, Object>> result = new ArrayList<>();
        if (raw instanceof List<?> list) {
            for (Object item : list) {
                if (item instanceof Map<?, ?> map) {
                    result.add(new LinkedHashMap<>(normalize(map)));
                }
            }
        }
        definition.put("states", result);
        return result;
    }

    @SuppressWarnings("unchecked")
    List<Map<String, Object>> transitionList(Map<String, Object> definition) {
        Object raw = definition.get("transitions");
        if (!(raw instanceof List<?> list)) return new ArrayList<>();
        List<Map<String, Object>> transitions = new ArrayList<>();
        for (Object item : list) {
            if (item instanceof Map<?, ?> map) transitions.add(normalize((Map<?, ?>) map));
        }
        return transitions;
    }

    @SuppressWarnings("unchecked")
    List<Map<String, Object>> transitionListMutable(Map<String, Object> definition) {
        Object raw = definition.get("transitions");
        List<Map<String, Object>> result = new ArrayList<>();
        if (raw instanceof List<?> list) {
            for (Object item : list) {
                if (item instanceof Map<?, ?> map) {
                    result.add(new LinkedHashMap<>(normalize(map)));
                }
            }
        }
        definition.put("transitions", result);
        return result;
    }

    Map<String, Object> findTransitionById(Map<String, Object> definition, String transitionId) {
        return transitionList(definition).stream()
                .filter(t -> transitionId.equals(string(t.get("id"))))
                .findFirst().orElse(null);
    }

    /**
     * Resolve the effective {@code fromStateId} for a transition request.
     * <p>
     * Precedence:
     * <ol>
     *   <li>{@code fromTransitionId} — look up the referenced transition,
     *       use its {@code toStateId} (or {@code fromStateId} for self-loops)</li>
     *   <li>{@code fromStateId} — use directly (backward compatible)</li>
     *   <li>neither — default to the initial state (states[0])</li>
     * </ol>
     *
     * @param definition       the state machine definition
     * @param request          the transition request body
     * @param existingStateIds existing state IDs (unused here; consistency with caller)
     * @return the resolved fromStateId (never blank)
     * @throws IllegalArgumentException if the reference is invalid or no default exists
     */
    private String resolveFromStateId(Map<String, Object> definition,
                                      Map<String, Object> request,
                                      Set<String> existingStateIds) {
        String fromTransitionId = string(request.get("fromTransitionId"));
        String fromStateId = string(request.get("fromStateId"));

        // Precedence 1: fromTransitionId
        if (!fromTransitionId.isBlank()) {
            Map<String, Object> target = findTransitionById(definition, fromTransitionId);
            if (target == null) {
                throw new IllegalArgumentException(
                        "fromTransitionId '" + fromTransitionId + "' not found");
            }
            String refToStateId = string(target.get("toStateId"));
            String refFromStateId = string(target.get("fromStateId"));
            // For normal transitions use toStateId; for self-loops use fromStateId
            if (!refToStateId.isBlank() && !refToStateId.equals(refFromStateId)) {
                return refToStateId;
            }
            return refFromStateId;
        }

        // Precedence 2: fromStateId (backward compatible)
        if (!fromStateId.isBlank()) {
            return fromStateId;
        }

        // Precedence 3: default to initial state
        List<Map<String, Object>> states = stateList(definition);
        if (states.isEmpty()) {
            throw new IllegalArgumentException(
                    "fromStateId is required when no states have been defined");
        }
        return string(states.get(0).get("id"));
    }

    boolean isInitialState(Map<String, Object> definition, String stateId) {
        List<Map<String, Object>> states = stateList(definition);
        return !states.isEmpty() && stateId.equals(string(states.get(0).get("id")));
    }

    @SuppressWarnings("unchecked")
    int countPages(Map<String, Object> state) {
        Object rawPages = state.get("pages");
        if (rawPages instanceof List<?> pages) return pages.size();
        // Legacy: sections at state level → counts as 1 page
        Object rawSections = state.get("sections");
        return rawSections instanceof List<?> ? 1 : 0;
    }

    @SuppressWarnings("unchecked")
    List<Map<String, Object>> deepCloneList(Object rawList) {
        List<Map<String, Object>> result = new ArrayList<>();
        if (!(rawList instanceof List<?> list)) return result;
        for (Object item : list) {
            if (item instanceof Map<?, ?> map) {
                Map<String, Object> cloned = new LinkedHashMap<>();
                for (var entry : map.entrySet()) {
                    Object value = entry.getValue();
                    if (value instanceof Map<?, ?> vm) {
                        cloned.put(String.valueOf(entry.getKey()), new LinkedHashMap<>(normalize(vm)));
                    } else if (value instanceof List<?> vl) {
                        cloned.put(String.valueOf(entry.getKey()), deepCloneList(vl));
                    } else {
                        cloned.put(String.valueOf(entry.getKey()), value);
                    }
                }
                result.add(cloned);
            }
        }
        return result;
    }

    List<String> devicesFromPage(Map<String, Object> page) {
        Set<String> devices = new LinkedHashSet<>();
        Object raw = page.get("devices");
        if (raw instanceof List<?> list) {
            for (Object item : list) { String s = string(item); if (!s.isBlank()) devices.add(s); }
        } else if (raw instanceof String s && !s.isBlank()) {
            devices.add(s);
        }
        return new ArrayList<>(devices);
    }

    // ═══════════════════════════════════════════════════════════
    // Serialization
    // ═══════════════════════════════════════════════════════════

    private Map<String, Object> toStateMachineMap(StateMachine sm) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("id", sm.getId());
        result.put("name", sm.getName());
        result.put("description", sm.getDescription());
        result.put("boardTypes", sm.getBoardTypes());
        result.put("definition", sm.getDefinition());
        result.put("editorModel", sm.getEditorModel());
        result.put("createdAt", sm.getCreatedAt());
        result.put("updatedAt", sm.getUpdatedAt());
        return result;
    }

    private Map<String, Object> toDeploymentMap(StateMachineDeployment d) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("id", d.getId());
        result.put("stateMachineId", d.getStateMachineId());
        result.put("devices", d.getDevices());
        result.put("deployedAt", d.getDeployedAt());
        return result;
    }

    // ═══════════════════════════════════════════════════════════
    // Low-level helpers
    // ═══════════════════════════════════════════════════════════

    Map<String, Object> normalize(Map<?, ?> raw) {
        Map<String, Object> result = new LinkedHashMap<>();
        raw.forEach((key, value) -> result.put(String.valueOf(key), value));
        return result;
    }

    String string(Object value) {
        return value == null ? "" : String.valueOf(value);
    }

    private int intValue(Object value, int fallback) {
        if (value instanceof Number n) return n.intValue();
        try { return value != null ? Integer.parseInt(String.valueOf(value)) : fallback; }
        catch (NumberFormatException e) { return fallback; }
    }
}
