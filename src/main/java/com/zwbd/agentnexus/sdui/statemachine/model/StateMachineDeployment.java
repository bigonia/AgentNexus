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

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Lightweight deployment record — an independent runtime instance of a state
 * machine definition bound to specific devices. Each deployment maintains its
 * own runtime state (current state ID, context data) separately from the
 * static definition and from other deployments.
 *
 * Deploy = create this record and start listening for device events.
 * Undeploy = delete this record and stop listening.
 */
@Data
@Entity
@Table(name = "sdui_state_machine_deployment")
public class StateMachineDeployment {

    @TenantId
    @Column(name = "space_id", nullable = false, updatable = false)
    private String spaceId;

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(length = 64)
    private String id;

    @Column(nullable = false, length = 64)
    private String stateMachineId;

    /** Bound device IDs (must match the state machine's boardTypes). */
    @Convert(converter = StringListConverter.class)
    @Column(nullable = false, columnDefinition = "text")
    private List<String> devices = new ArrayList<>();

    /** Current state ID (runtime, initialized to the definition's first state on deploy). */
    @Column(nullable = false, length = 64)
    private String currentStateId = "";

    /** Runtime context data (key-value store for {@code context.set} actions). */
    @Convert(converter = StateMachineJsonConverter.class)
    @Column(nullable = false, columnDefinition = "text")
    private Map<String, Object> contextData = new LinkedHashMap<>();

    @CreationTimestamp
    private LocalDateTime deployedAt;
}
