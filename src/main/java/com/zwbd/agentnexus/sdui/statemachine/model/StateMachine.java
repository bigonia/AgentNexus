package com.zwbd.agentnexus.sdui.statemachine.model;

import com.zwbd.agentnexus.utils.StringListConverter;
import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Data;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.TenantId;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Pure static state machine definition — an event-driven flow blueprint.
 *
 * A state machine is a static configuration describing states, transitions,
 * and the page/section layout for each state. It has no lifecycle status,
 * no runtime state, and no device bindings — those are handled by the
 * separate {@link StateMachineDeployment} entity.
 *
 * Deployment creates an independent lightweight record (definition copy +
 * device binding) that activates event listening for those devices.
 */
@Data
@Entity
@Table(name = "sdui_state_machine")
public class StateMachine {

    @TenantId
    @Column(name = "space_id", nullable = false, updatable = false)
    private String spaceId;

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(length = 64)
    private String id;

    @Column(nullable = false, length = 128)
    private String name;

    @Column(length = 512)
    private String description;

    /**
     * Target board types (multi-select, required at creation, immutable after creation).
     * Acts as a capability filter — the editor only shows events/commands/sections
     * supported by these board types.
     */
    @Convert(converter = StringListConverter.class)
    @Column(nullable = false, columnDefinition = "text")
    private List<String> boardTypes = new ArrayList<>();

    /**
     * State machine logic: states and transitions.
     * <pre>{@code
     * {
     *   "states": [
     *     { "id": "idle", "label": "空闲待机",
     *       "sections": [
     *         { "sectionId": "welcome", "sectionType": "hero_section", "fields": {...} }
     *       ]
     *     }
     *   ],
     *   "transitions": [
     *     { "id": "t1", "fromStateId": "idle", "toStateId": "active",
     *       "event": { "eventId": "ui:action.click" },
     *       "actions": [
     *         { "type": "context.set", "name": "clicked", "value": true },
     *         { "type": "command.dispatch", "commandId": "rgb.effect.set", "params": {...} }
     *       ],
     *       "priority": 0
     *     }
     *   ]
     * }
     * }</pre>
     */
    @Convert(converter = StateMachineJsonConverter.class)
    @Column(nullable = false, columnDefinition = "text")
    private Map<String, Object> definition = new LinkedHashMap<>();

    /** Frontend editor state (node positions, viewport, etc.). */
    @Convert(converter = StateMachineJsonConverter.class)
    @Column(nullable = false, columnDefinition = "text")
    private Map<String, Object> editorModel = new LinkedHashMap<>();

    @CreationTimestamp
    private LocalDateTime createdAt;

    @UpdateTimestamp
    private LocalDateTime updatedAt;
}
