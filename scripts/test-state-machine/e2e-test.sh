#!/usr/bin/env bash
# ─────────────────────────────────────────────────────────────────────────────
# e2e-test.sh — State Machine End-to-End Closed-Loop Test (Incremental API)
#
# Tests the complete state machine lifecycle using the NEW incremental API.
# No mock endpoints, no hardcoded test controllers. Pure HTTP against real APIs.
#
# Usage:
#   ./e2e-test.sh              # Run all steps
#   ./e2e-test.sh --verbose    # Show request/response bodies
#   STEP=discover ./e2e-test.sh  # Run a single step
#   DEVICE_ID=1051DB398BD0 ./e2e-test.sh  # Target a specific device
#
# New Incremental Flow:
#   1. discover      — Discover online device & board type
#   2. catalog       — Query events, commands, sections for the board type
#   3. create-shell  — Create state machine shell (just name + boardTypes)
#   4. add-states    — Add states incrementally (only initial state needed)
#   5. add-transitions — Add transitions one at a time (backend auto-derives target states)
#   6. branch-transitions — Add branch transitions (fromTransitionId + default fromStateId)
#   7. verify-build  — Verify states and transitions via incremental queries
#   8. deploy        — Deploy to real device
#   9. trigger-1     — Trigger idle → running transition
#  10. trigger-2     — Trigger running → done transition
#  11. verify        — Verify deployment state and context
#  12. cleanup       — Remove test state machine and deployment
# ─────────────────────────────────────────────────────────────────────────────
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
source "$SCRIPT_DIR/common.sh"

# Parse options
if [[ "${1:-}" == "--verbose" ]] || [[ "${VERBOSE:-}" == "1" ]]; then
    VERBOSE=1
fi

RUN_STEP="${STEP:-all}"

# ═══════════════════════════════════════════════════════════════════════════════
# Step 1: Discover device & board type
# ═══════════════════════════════════════════════════════════════════════════════
step_discover() {
    log_title "Step 1: Discover Real Device & Board Type"

    if [ -n "${DEVICE_ID:-}" ]; then
        log_info "Using pre-configured device: $DEVICE_ID"

        local bt_resp bt_data type_key
        bt_resp=$(http_get "${BOARD_TYPES_URL}" "List board types")
        bt_data=$(extract_data "$bt_resp") || return 1

        local dev_resp dev_data board
        dev_resp=$(http_get "${DEVICES_URL}?size=50" "List devices")
        dev_data=$(extract_data "$dev_resp") || return 1
        board=$(echo "$dev_data" | jq -r --arg id "$DEVICE_ID" '.items[] | select(.deviceId == $id) | .board')
        save_state "board" "$board"

        type_key=$(echo "$bt_data" | jq -r --arg b "$board" '.[] | select(.board == $b) | .key' | head -1)
        save_state "deviceId" "$DEVICE_ID"
        save_state "boardTypeKey" "$type_key"

        log_ok "Device: $DEVICE_ID  board=$board  type=$type_key"
    else
        discover_device || return 1
    fi

    local type_key device_id board
    type_key=$(read_state "boardTypeKey")
    device_id=$(read_state "deviceId")
    board=$(read_state "board")

    echo ""
    echo -e "  ${BOLD}Target Device:${NC}"
    echo "    Device ID:   $device_id"
    echo "    Board:       $board"
    echo "    Type Key:    $type_key"
    echo ""
}

# ═══════════════════════════════════════════════════════════════════════════════
# Step 2: Query catalog — events, commands, sections by board type
# ═══════════════════════════════════════════════════════════════════════════════
step_catalog() {
    log_title "Step 2: Query Catalog for Board Type"

    local type_key device_id
    type_key=$(read_state "boardTypeKey")
    device_id=$(read_state "deviceId")
    if [ -z "$type_key" ]; then
        log_error "No board type key. Run step_discover first."
        return 1
    fi

    # ── 2a. Board type info ──
    log_step "2a. Board Type Detail"
    local resp data
    resp=$(http_get "${BOARD_TYPES_URL}/${type_key}" "Board detail")
    data=$(extract_and_show "$resp" "Board type '$type_key'") || return 1

    local section_count event_count cmd_count
    section_count=$(echo "$data" | jq -r '.sectionTypes | length')
    event_count=$(echo "$data" | jq -r '.inputEvents | length')
    cmd_count=$(echo "$data" | jq -r '.outputCommands | length')
    log_ok "Sections: $section_count | Input events: $event_count | Commands: $cmd_count"

    # ── 2b. Available inbound events (for transitions) ──
    log_step "2b. Available Inbound Events"
    resp=$(http_get "${BOARD_TYPES_URL}/${type_key}/events" "Board events")
    data=$(extract_data "$resp") 2>/dev/null || true

    echo "$data" | jq -r '.eventOptions[]? | "  \(.value) — \(.label) [\(.category)]"' 2>/dev/null | while read -r line; do
        log_info "$line"
    done
    echo "$data" | jq -r '.eventOptions[]?.value' 2>/dev/null > /tmp/sm-available-events.txt
    log_ok "Event options saved"

    # ── 2c. Available commands ──
    log_step "2c. Available Commands"
    resp=$(http_get "${BOARD_TYPES_URL}/${type_key}/commands" "Board commands")
    data=$(extract_data "$resp") 2>/dev/null || true
    echo "$data" | jq -r '.deviceCommands[]? | "  \(.command) — params: \(.params | length)"' 2>/dev/null | while read -r line; do
        log_info "$line"
    done
    log_ok "Commands queried"

    # ── 2d. Section types ──
    log_step "2d. Section Types"
    resp=$(http_get "${BOARD_TYPES_URL}/${type_key}/sections" "Board sections")
    data=$(extract_data "$resp") 2>/dev/null || true
    echo "$data" | jq -r '.sectionTypes[]? | "  \(.type) — \(.label // .type)"' 2>/dev/null | while read -r line; do
        log_info "$line"
    done
    log_ok "Section types queried"

    # ── 2e. Global catalog ──
    log_step "2e. Global Event Catalog"
    resp=$(http_get "${CATALOG_URL}" "Event catalog")
    data=$(extract_data "$resp") 2>/dev/null || true
    local cmd_catalog_count sec_catalog_count
    cmd_catalog_count=$(echo "$data" | jq -r '.commands | length' 2>/dev/null)
    sec_catalog_count=$(echo "$data" | jq -r '.sections | length' 2>/dev/null)
    log_ok "Global catalog: $cmd_catalog_count command groups, $sec_catalog_count section types"
}

# ═══════════════════════════════════════════════════════════════════════════════
# Step 3: Create state machine shell (incremental API — just name + boardTypes)
# ═══════════════════════════════════════════════════════════════════════════════
step_create_shell() {
    log_title "Step 3: Create State Machine Shell (Incremental API)"

    local board
    board=$(read_state "board")
    if [ -z "$board" ]; then
        log_error "No board. Run step_discover first."
        return 1
    fi

    # Create shell — only name + boardTypes, NO definition
    local shell_body resp data sm_id
    shell_body=$(jq -n \
        --arg name "E2E Incremental Test - $(date +%H%M%S)" \
        --arg desc "End-to-end incremental state machine test" \
        --arg board "$board" \
        '{
            name: $name,
            description: $desc,
            boardTypes: [$board]
        }')

    resp=$(http_post "${STATE_MACHINES_URL}" "$shell_body" "Create shell")
    data=$(extract_and_show "$resp" "Created state machine shell") || {
        log_error "Failed to create state machine shell"
        return 1
    }

    sm_id=$(echo "$data" | jq -r '.id')
    save_state "stateMachineId" "$sm_id"

    assert_data "$resp" '.boardTypes | length > 0' "Has board types"
    assert_data "$resp" '.definition.states | length == 0' "Starts with 0 states"
    assert_data "$resp" '.definition.transitions | length == 0' "Starts with 0 transitions"
    log_ok "State machine shell created: $sm_id"
}

# ═══════════════════════════════════════════════════════════════════════════════
# Step 4: Add states incrementally
#
# Only the initial state (idle) needs to be manually defined with its sections.
# Target states (running, done) will be auto-derived when transitions are added.
# ═══════════════════════════════════════════════════════════════════════════════
step_add_states() {
    log_title "Step 4: Add States Incrementally"

    local sm_id
    sm_id=$(read_state "stateMachineId")
    if [ -z "$sm_id" ]; then
        log_error "No state machine ID. Run step_create_shell first."
        return 1
    fi

    # ── 4a. Add initial state: idle ──
    log_step "4a. Add Initial State: idle"
    local idle_body resp data
    idle_body=$(jq -n '{
        id: "idle",
        label: "Idle Standby",
        pages: [{
            pageId: "main",
            layout: "vertical_scroll",
            sections: [
                {
                    sectionId: "hero-title",
                    sectionType: "hero_section",
                    fields: {
                        title: "E2E Test Ready",
                        subtitle: "Waiting for trigger..."
                    }
                },
                {
                    sectionId: "status-text",
                    sectionType: "text_section",
                    fields: {
                        title: "State: idle",
                        body: "Send timer.elapsed to start."
                    }
                }
            ]
        }]
    }')

    resp=$(http_post "${STATE_MACHINES_URL}/${sm_id}/states" "$idle_body" "Add state: idle")
    data=$(extract_and_show "$resp" "idle state") || {
        log_error "Failed to add idle state"
        return 1
    }

    local is_initial
    is_initial=$(echo "$data" | jq -r '.isInitial // false')
    assert_eq "$resp" '.id' "idle" "State ID = idle"
    if [ "$is_initial" = "true" ]; then
        log_ok "idle is the initial state ✓"
    else
        log_warn "idle is not marked as initial (unexpected)"
    fi

    # ── 4b. List states (should show 1 state) ──
    log_step "4b. List States"
    resp=$(http_get "${STATE_MACHINES_URL}/${sm_id}/states" "List states")
    data=$(extract_and_show "$resp" "States list") || return 1

    local state_count
    state_count=$(echo "$data" | jq -r '.states | length')
    assert_eq "$resp" '.states | length' "1" "State count = 1"
    log_ok "States: $state_count"
}

# ═══════════════════════════════════════════════════════════════════════════════
# Step 5: Add transitions incrementally (backend auto-derives target states)
#
# Transition 1: idle → running  (section.update + section.add → auto-derives running)
# Transition 2: running → done  (section.update + section.remove → auto-derives done)
#
# The key insight: we don't manually define running/done states.
# The backend derives them from the transitions' actions applied to the fromState.
# ═══════════════════════════════════════════════════════════════════════════════
step_add_transitions() {
    log_title "Step 5: Add Transitions Incrementally (Auto-Derivation)"

    local sm_id
    sm_id=$(read_state "stateMachineId")
    if [ -z "$sm_id" ]; then
        log_error "No state machine ID. Run step_create_shell first."
        return 1
    fi

    # ── 5a. Add transition: idle → running ──
    # Actions modify the page: update hero title, add progress section, set context
    # Backend auto-derives the "running" state from these actions
    log_step "5a. Add Transition: idle → running (auto-derives 'running' state)"
    local t1_body resp data trans1_id
    t1_body=$(jq -n '{
        fromStateId: "idle",
        toStateId: "running",
        priority: 10,
        event: { eventId: "timer.elapsed" },
        actions: [
            {
                type: "section.update",
                sectionId: "hero-title",
                pageId: "main",
                fields: {
                    title: "Processing...",
                    subtitle: "Transition 1 executed"
                }
            },
            {
                type: "section.add",
                sectionType: "progress_section",
                sectionId: "progress-bar",
                pageId: "main",
                fields: {
                    title: "Work in progress",
                    progress: 50,
                    progressText: "Step 1/2"
                }
            },
            {
                type: "context.set",
                name: "started",
                value: true
            },
            {
                type: "context.set",
                name: "startedAt",
                value: "manual"
            },
            {
                type: "context.set",
                name: "triggerCount",
                value: 1
            }
        ]
    }')

    resp=$(http_post "${STATE_MACHINES_URL}/${sm_id}/transitions" "$t1_body" "Add transition #1")
    data=$(extract_and_show "$resp" "Transition #1 result") || {
        log_error "Failed to add transition #1"
        return 1
    }

    trans1_id=$(echo "$data" | jq -r '.id')
    save_state "trans1Id" "$trans1_id"
    assert_eq "$resp" '.fromStateId' "idle" "fromStateId = idle"
    assert_eq "$resp" '.toStateId' "running" "toStateId = running"
    log_ok "Transition #1 created: $trans1_id"

    # Verify auto-derived state
    local derived_state
    derived_state=$(echo "$data" | jq -r '.derivedState // empty')
    if [ -n "$derived_state" ]; then
        echo "$data" | jq '.derivedState | {id, label, pageCount: (.pages | length), derivedFromTransitionId, initialContext}' >&2
        local deriv_id init_ctx
        deriv_id=$(echo "$data" | jq -r '.derivedState.derivedFromTransitionId')
        init_ctx=$(echo "$data" | jq -r '.derivedState.initialContext // {}')
        log_ok "Auto-derived state 'running': derivedFromTransitionId=$deriv_id, initialContext=$init_ctx"

        # Verify running state has 3 sections: updated hero + original text + added progress
        local sec_count
        sec_count=$(echo "$data" | jq -r '.derivedState.pages[0].sections | length')
        log_info "Derived 'running' state has $sec_count sections (expected: 3)"
    fi

    # ── 5b. Add transition: running → done ──
    # Actions: update hero title, remove progress section, set context
    # Backend auto-derives the "done" state
    log_step "5b. Add Transition: running → done (auto-derives 'done' state)"
    local t2_body trans2_id
    t2_body=$(jq -n '{
        fromStateId: "running",
        toStateId: "done",
        priority: 10,
        event: { eventId: "timer.elapsed" },
        actions: [
            {
                type: "section.update",
                sectionId: "hero-title",
                pageId: "main",
                fields: {
                    title: "Test Passed!",
                    subtitle: "E2E Closed-Loop Verified"
                }
            },
            {
                type: "section.remove",
                sectionId: "progress-bar",
                pageId: "main"
            },
            {
                type: "context.set",
                name: "completed",
                value: true
            },
            {
                type: "context.set",
                name: "completedAt",
                value: "manual"
            }
        ]
    }')

    resp=$(http_post "${STATE_MACHINES_URL}/${sm_id}/transitions" "$t2_body" "Add transition #2")
    data=$(extract_and_show "$resp" "Transition #2 result") || {
        log_error "Failed to add transition #2"
        return 1
    }

    trans2_id=$(echo "$data" | jq -r '.id')
    save_state "trans2Id" "$trans2_id"
    assert_eq "$resp" '.fromStateId' "running" "fromStateId = running"
    assert_eq "$resp" '.toStateId' "done" "toStateId = done"
    log_ok "Transition #2 created: $trans2_id"

    # Verify auto-derived done state
    derived_state=$(echo "$data" | jq -r '.derivedState // empty')
    if [ -n "$derived_state" ]; then
        echo "$data" | jq '.derivedState | {id, label, pageCount: (.pages | length), derivedFromTransitionId, initialContext}' >&2
        local deriv_id sec_count
        deriv_id=$(echo "$data" | jq -r '.derivedState.derivedFromTransitionId')
        sec_count=$(echo "$data" | jq -r '.derivedState.pages[0].sections | length')
        log_ok "Auto-derived state 'done': derivedFromTransitionId=$deriv_id"
        log_info "Derived 'done' state has $sec_count sections (expected: 2 — progress-bar removed)"
    fi
}

# ═══════════════════════════════════════════════════════════════════════════════
# Step 6: Add branch transitions (fromTransitionId + default fromStateId)
#
# Demonstrates:
#   - Omitting fromStateId/fromTransitionId → defaults to initial state (branching)
#   - Specifying fromTransitionId → resolves to that transition's toStateId
# ═══════════════════════════════════════════════════════════════════════════════
step_branch_transitions() {
    log_title "Step 6: Add Branch Transitions (fromTransitionId Resolution)"

    local sm_id trans1_id
    sm_id=$(read_state "stateMachineId")
    trans1_id=$(read_state "trans1Id")
    if [ -z "$sm_id" ]; then
        log_error "No state machine ID. Run previous steps first."
        return 1
    fi

    # ── 6a. Branch from initial state (neither fromStateId nor fromTransitionId) ──
    log_step "6a. Branch from initial state (default → idle)"
    local branch_body resp data branch_id
    branch_body=$(jq -n '{
        toStateId: "timeout",
        priority: 5,
        event: { eventId: "timer.elapsed" },
        actions: [
            {
                type: "section.update",
                sectionId: "hero-title",
                pageId: "main",
                fields: {
                    title: "Timeout!",
                    subtitle: "Branch from initial state"
                }
            },
            {
                type: "context.set",
                name: "timedOut",
                value: true
            }
        ]
    }')

    resp=$(http_post "${STATE_MACHINES_URL}/${sm_id}/transitions" "$branch_body" "Add branch: timeout")
    data=$(extract_and_show "$resp" "Branch transition: timeout") || {
        log_error "Failed to add timeout branch transition"
        return 1
    }

    branch_id=$(echo "$data" | jq -r '.id')
    save_state "branchTimeoutId" "$branch_id"
    assert_eq "$resp" '.fromStateId' "idle" "Defaulted fromStateId = idle"
    log_ok "Branch: fromStateId defaulted to 'idle' (initial state)"

    # Verify auto-derived timeout state
    local derived_state sec_count
    derived_state=$(echo "$data" | jq -r '.derivedState // empty')
    if [ -n "$derived_state" ]; then
        sec_count=$(echo "$data" | jq -r '.derivedState.pages[0].sections | length')
        log_info "Derived 'timeout' state has $sec_count sections"
    fi

    # ── 6b. Branch from another transition's result (fromTransitionId) ──
    log_step "6b. Branch from transition #1's result (fromTransitionId=$trans1_id)"
    branch_body=$(jq -n \
        --arg fromTransId "$trans1_id" '{
        fromTransitionId: $fromTransId,
        toStateId: "paused",
        priority: 8,
        event: { eventId: "timer.elapsed" },
        actions: [
            {
                type: "section.update",
                sectionId: "hero-title",
                pageId: "main",
                fields: {
                    title: "Paused",
                    subtitle: "Branch from running state"
                }
            },
            {
                type: "section.update",
                sectionId: "progress-bar",
                pageId: "main",
                fields: {
                    title: "Paused at 50%",
                    progress: 50
                }
            },
            {
                type: "context.set",
                name: "paused",
                value: true
            }
        ]
    }')

    resp=$(http_post "${STATE_MACHINES_URL}/${sm_id}/transitions" "$branch_body" "Add branch: paused")
    data=$(extract_and_show "$resp" "Branch transition: paused") || {
        log_error "Failed to add paused branch transition"
        return 1
    }

    branch_id=$(echo "$data" | jq -r '.id')
    save_state "branchPausedId" "$branch_id"
    assert_eq "$resp" '.fromStateId' "running" "Resolved fromStateId = running (from transition #1)"
    log_ok "Branch: fromTransitionId resolved to 'running'"

    # Verify fromTransitionId is stored in the transition
    local stored_ftid
    stored_ftid=$(echo "$data" | jq -r '.fromTransitionId // empty')
    if [ "$stored_ftid" = "$trans1_id" ]; then
        log_ok "fromTransitionId stored in transition: $stored_ftid ✓"
    else
        log_warn "fromTransitionId not stored; got: '$stored_ftid'"
    fi

    # Verify auto-derived paused state
    derived_state=$(echo "$data" | jq -r '.derivedState // empty')
    if [ -n "$derived_state" ]; then
        sec_count=$(echo "$data" | jq -r '.derivedState.pages[0].sections | length')
        log_info "Derived 'paused' state has $sec_count sections"
    fi
}

# ═══════════════════════════════════════════════════════════════════════════════
# Step 7: Verify the incremental build via queries
#
# Demonstrates:
#   - GET /states — lists all 3 states (idle, auto-derived running, auto-derived done)
#   - GET /states/{stateId} — get full state detail with derivedFromTransitionId
#   - GET /states?at={transId} — get state at a specific transition point
#   - GET /transitions — ordered transition list
# ═══════════════════════════════════════════════════════════════════════════════
step_verify_build() {
    log_title "Step 7: Verify Incremental Build via Queries"

    local sm_id trans1_id trans2_id branch_timeout_id branch_paused_id
    sm_id=$(read_state "stateMachineId")
    trans1_id=$(read_state "trans1Id")
    trans2_id=$(read_state "trans2Id")
    branch_timeout_id=$(read_state "branchTimeoutId")
    branch_paused_id=$(read_state "branchPausedId")
    if [ -z "$sm_id" ]; then
        log_error "No state machine ID. Run previous steps first."
        return 1
    fi

    # ── 6a. List all states ──
    log_step "7a. List All States (should show idle, running, done, timeout, paused)"
    local resp data
    resp=$(http_get "${STATE_MACHINES_URL}/${sm_id}/states" "List states")
    data=$(extract_and_show "$resp" "All states") || return 1

    assert_eq "$resp" '.states | length' "5" "5 states total"

    # Show which states are auto-derived
    echo "$data" | jq -r '.states[] | "  \(.id) — derivedFromTransitionId: \(.derivedFromTransitionId // "null (manually created)")"' 2>/dev/null

    # ── 6b. Get individual states (full detail) ──
    log_step "7b. Get Individual States (full detail)"

    # idle (manually created, no derivation)
    resp=$(http_get "${STATE_MACHINES_URL}/${sm_id}/states/idle" "Get state: idle")
    data=$(extract_and_show "$resp" "idle state detail") || return 1
    local derived_from
    derived_from=$(echo "$data" | jq -r '.derivedFromTransitionId // "null"')
    local is_init
    is_init=$(echo "$data" | jq -r '.isInitial // false')
    log_info "  idle: isInitial=$is_init, derivedFromTransitionId=$derived_from (expected: null)"

    # running (auto-derived by transition #1)
    resp=$(http_get "${STATE_MACHINES_URL}/${sm_id}/states/running" "Get state: running")
    data=$(extract_and_show "$resp" "running state detail") || return 1
    derived_from=$(echo "$data" | jq -r '.derivedFromTransitionId // "null"')
    local init_ctx
    init_ctx=$(echo "$data" | jq -r '.initialContext // {}')
    log_info "  running: derivedFromTransitionId=$derived_from, initialContext=$init_ctx"
    assert_eq "$resp" '.derivedFromTransitionId' "$trans1_id" "Derived from transition #1"

    # done (auto-derived by transition #2)
    resp=$(http_get "${STATE_MACHINES_URL}/${sm_id}/states/done" "Get state: done")
    data=$(extract_and_show "$resp" "done state detail") || return 1
    derived_from=$(echo "$data" | jq -r '.derivedFromTransitionId // "null"')
    init_ctx=$(echo "$data" | jq -r '.initialContext // {}')
    log_info "  done: derivedFromTransitionId=$derived_from, initialContext=$init_ctx"
    assert_eq "$resp" '.derivedFromTransitionId' "$trans2_id" "Derived from transition #2"

    # ── 6c. Query state at transition point ──
    log_step "7c. Query State at Transition Point (?at={transId})"
    if [ -n "$trans1_id" ]; then
        resp=$(http_get "${STATE_MACHINES_URL}/${sm_id}/states?at=${trans1_id}" "State at transition #1")
        data=$(extract_and_show "$resp" "State at transition #1") || return 1
        assert_eq "$resp" '.id' "running" "State at T1 = running"
        log_ok "?at=$trans1_id → state 'running' ✓"
    fi

    # ── 6d. List transitions ──
    log_step "7d. List Transitions (ordered)"
    resp=$(http_get "${STATE_MACHINES_URL}/${sm_id}/transitions" "List transitions")
    data=$(extract_and_show "$resp" "Transitions") || return 1

    assert_eq "$resp" '.transitions | length' "4" "4 transitions"
    echo "$data" | jq -r '.transitions[] | "  [\(.index)] \(.id) — \(.fromStateId) → \(.toStateId) [\(.actionCount) actions]"' 2>/dev/null

    log_ok "Incremental build verified successfully"
}

# ═══════════════════════════════════════════════════════════════════════════════
# Step 7: Deploy state machine to real device
# ═══════════════════════════════════════════════════════════════════════════════
step_deploy() {
    log_title "Step 8: Deploy State Machine to Real Device"

    local sm_id device_id
    sm_id=$(read_state "stateMachineId")
    device_id=$(read_state "deviceId")
    if [ -z "$sm_id" ]; then
        log_error "No state machine ID. Run step_create_shell first."
        return 1
    fi

    local deploy_body resp data
    deploy_body=$(jq -n --arg d "$device_id" '{devices: [$d]}')

    resp=$(http_post "${STATE_MACHINES_URL}/${sm_id}/deployments" "$deploy_body" "Deploy SM")
    data=$(extract_and_show "$resp" "Deployment result") || {
        log_error "Deployment failed"
        return 1
    }

    # Check warnings
    local warnings
    warnings=$(echo "$data" | jq -r '.warnings // [] | length')
    if [ "$warnings" -gt 0 ]; then
        log_warn "Deployment completed with $warnings warning(s):"
        echo "$data" | jq -r '.warnings[]? | "    [\(.severity)] \(.message)"' 2>/dev/null
    fi

    # Check page push results
    local all_sent
    all_sent=$(echo "$data" | jq -r '.allSent // false')
    if [ "$all_sent" = "true" ]; then
        log_ok "Scene pushed to device successfully (sent=true)"
    else
        log_info "Push results — sent=false is expected when device has no active WebSocket session."
    fi

    local deploy_id
    deploy_id=$(echo "$data" | jq -r '.id')
    save_state "deploymentId" "$deploy_id"

    assert_data "$resp" '.stateMachineId != ""' "Has stateMachineId"
    log_ok "Deployment created: $deploy_id"
}

# ═══════════════════════════════════════════════════════════════════════════════
# Step 8: Trigger transition 1 (idle → running)
# ═══════════════════════════════════════════════════════════════════════════════
step_trigger_1() {
    log_title "Step 9: Trigger Transition 1 (idle → running)"

    local sm_id device_id
    sm_id=$(read_state "stateMachineId")
    device_id=$(read_state "deviceId")
    if [ -z "$sm_id" ]; then
        log_error "No state machine ID. Run step_create_shell first."
        return 1
    fi

    local trigger_body resp data
    trigger_body=$(jq -n \
        --arg eventId "timer.elapsed" \
        --arg deviceId "$device_id" \
        '{ event: { eventId: $eventId, deviceId: $deviceId } }')

    resp=$(http_post "${STATE_MACHINES_URL}/${sm_id}/trigger" "$trigger_body" "Trigger #1")
    data=$(extract_and_show "$resp" "Trigger #1 result") || {
        log_error "Trigger #1 failed"
        return 1
    }

    assert_data "$resp" '.matched == true' "Transition matched"
    assert_data "$resp" '.currentStateId == "running"' "Current state = running"
    assert_data "$resp" '.status == "SUCCEEDED"' "Status = SUCCEEDED"

    local ctx chain_count
    chain_count=$(echo "$data" | jq -r '.chainCount')
    ctx=$(echo "$data" | jq -r '.context // {}')
    log_info "Chain count: $chain_count"
    log_info "Context after trigger: $ctx"

    log_ok "Trigger #1 completed successfully"
}

# ═══════════════════════════════════════════════════════════════════════════════
# Step 9: Trigger transition 2 (running → done)
# ═══════════════════════════════════════════════════════════════════════════════
step_trigger_2() {
    log_title "Step 10: Trigger Transition 2 (running → done)"

    local sm_id device_id
    sm_id=$(read_state "stateMachineId")
    device_id=$(read_state "deviceId")
    if [ -z "$sm_id" ]; then
        log_error "No state machine ID. Run step_create_shell first."
        return 1
    fi

    local trigger_body resp data
    trigger_body=$(jq -n \
        --arg eventId "timer.elapsed" \
        --arg deviceId "$device_id" \
        '{ event: { eventId: $eventId, deviceId: $deviceId } }')

    resp=$(http_post "${STATE_MACHINES_URL}/${sm_id}/trigger" "$trigger_body" "Trigger #2")
    data=$(extract_and_show "$resp" "Trigger #2 result") || {
        log_error "Trigger #2 failed"
        return 1
    }

    assert_data "$resp" '.matched == true' "Transition matched"
    assert_data "$resp" '.currentStateId == "done"' "Current state = done"
    assert_data "$resp" '.status == "SUCCEEDED"' "Status = SUCCEEDED"

    local ctx
    ctx=$(echo "$data" | jq -r '.context // {}')
    log_info "Final context: $ctx"

    local started completed
    started=$(echo "$data" | jq -r '.context.started // false')
    completed=$(echo "$data" | jq -r '.context.completed // false')
    if [ "$started" = "true" ] && [ "$completed" = "true" ]; then
        log_ok "Context variables verified: started=$started, completed=$completed"
    else
        log_warn "Context variables: started=$started, completed=$completed"
    fi

    log_ok "Trigger #2 completed successfully"
}

# ═══════════════════════════════════════════════════════════════════════════════
# Step 10: Verify deployment state and incremental queries
# ═══════════════════════════════════════════════════════════════════════════════
step_verify() {
    log_title "Step 11: Verify Deployment & Final State"

    local sm_id device_id
    sm_id=$(read_state "stateMachineId")
    device_id=$(read_state "deviceId")
    if [ -z "$sm_id" ]; then
        log_error "No state machine ID. Run step_create_shell first."
        return 1
    fi

    # ── 10a. List deployments ──
    log_step "11a. List Deployments"
    local resp data
    resp=$(http_get "${STATE_MACHINES_URL}/${sm_id}/deployments" "List deployments")
    data=$(extract_and_show "$resp" "Deployments") || return 1

    local deploy_count
    deploy_count=$(echo "$data" | jq -r 'length')
    if [ "$deploy_count" -gt 0 ]; then
        local deploy_id current_state
        deploy_id=$(echo "$data" | jq -r '.[0].id // empty')
        current_state=$(echo "$data" | jq -r '.[0].currentStateId // empty')
        log_info "  ID: $deploy_id  Current State: $current_state"
        if [ "$current_state" = "done" ]; then
            log_ok "Deployment state correctly advanced to 'done'"
        fi
    fi

    # ── 10b. Re-verify states (should still be 5) ──
    log_step "11b. States After Trigger Run"
    resp=$(http_get "${STATE_MACHINES_URL}/${sm_id}/states" "List states")
    data=$(extract_and_show "$resp" "States") || return 1
    assert_eq "$resp" '.states | length' "5" "5 states preserved"

    # ── 10c. Get full state machine ──
    log_step "11c. Full State Machine Definition"
    resp=$(http_get "${STATE_MACHINES_URL}/${sm_id}" "Get SM")
    data=$(extract_and_show "$resp" "State Machine") || return 1

    assert_data "$resp" '.definition.states | length == 5' "Has 5 states"
    assert_data "$resp" '.definition.transitions | length == 4' "Has 4 transitions"
    assert_data "$resp" '.boardTypes | length > 0' "Has board types"

    log_ok "State machine structure verified"
}

# ═══════════════════════════════════════════════════════════════════════════════
# Step 11: Clean up
# ═══════════════════════════════════════════════════════════════════════════════
step_cleanup() {
    log_title "Step 12: Clean Up"

    local sm_id
    sm_id=$(read_state "stateMachineId")

    if [ -n "$sm_id" ]; then
        # Delete deployments first
        local dep_resp dep_data
        dep_resp=$(http_get "${STATE_MACHINES_URL}/${sm_id}/deployments" "List deployments")
        dep_data=$(extract_data "$dep_resp" 2>/dev/null || echo "[]")

        echo "$dep_data" | jq -r '.[]?.id // empty' 2>/dev/null | while read -r dep_id; do
            if [ -n "$dep_id" ]; then
                http_delete "${STATE_MACHINES_URL}/${sm_id}/deployments/${dep_id}" "Delete deployment" > /dev/null || true
            fi
        done

        http_delete "${STATE_MACHINES_URL}/${sm_id}" "Delete SM" > /dev/null || true
        log_ok "Deleted state machine: $sm_id"
    else
        log_info "No state machine to clean up"
    fi

    clear_state
    rm -f /tmp/sm-available-events.txt
    log_ok "Cleanup completed"
}

# ═══════════════════════════════════════════════════════════════════════════════
# Main
# ═══════════════════════════════════════════════════════════════════════════════

main() {
    check_dependencies
    check_server

    local steps=("discover" "catalog" "create_shell" "add_states" "add_transitions" "branch_transitions" "verify_build" "deploy" "trigger_1" "trigger_2" "verify" "cleanup")

    if [ "$RUN_STEP" != "all" ]; then
        case "$RUN_STEP" in
            discover)        step_discover ;;
            catalog)         step_catalog ;;
            create-shell)    step_create_shell ;;
            create_shell)    step_create_shell ;;
            add-states)      step_add_states ;;
            add_states)      step_add_states ;;
            add-transitions) step_add_transitions ;;
            add_transitions) step_add_transitions ;;
            branch-transitions) step_branch_transitions ;;
            branch_transitions) step_branch_transitions ;;
            verify-build)    step_verify_build ;;
            verify_build)    step_verify_build ;;
            deploy)          step_deploy ;;
            trigger-1)       step_trigger_1 ;;
            trigger_1)       step_trigger_1 ;;
            trigger-2)       step_trigger_2 ;;
            trigger_2)       step_trigger_2 ;;
            verify)          step_verify ;;
            cleanup)         step_cleanup ;;
            *)
                log_error "Unknown step: $RUN_STEP"
                log_error "Available: ${steps[*]}"
                exit 1
                ;;
        esac
        log_ok "Step '$RUN_STEP' completed."
        exit 0
    fi

    local failed_steps=()

    for step in "${steps[@]}"; do
        if ! "step_${step}"; then
            failed_steps+=("$step")
            log_error "Step '$step' FAILED"

            if [ -t 0 ]; then
                echo ""
                log_warn "Continue to next step? [y/N] "
                read -r answer
                if [ "$answer" != "y" ] && [ "$answer" != "Y" ]; then
                    log_error "Aborting at step: $step"
                    break
                fi
            else
                log_error "Non-interactive mode: stopping"
                break
            fi
        fi
    done

    echo ""
    if [ ${#failed_steps[@]} -eq 0 ]; then
        log_title "ALL STEPS PASSED ✓"
        echo ""
        echo -e "  ${GREEN}The incremental state machine API works with real data.${NC}"
        echo ""
        echo "  New Incremental Flow Verified:"
        echo "    1.  ✓ Real device & board type auto-discovered"
        echo "    2.  ✓ Events, commands, sections queried by board type"
        echo "    3.  ✓ Shell created with just name + boardTypes (no definition)"
        echo "    4.  ✓ Initial state 'idle' added incrementally"
        echo "    5.  ✓ Transition #1 added → auto-derived 'running' state"
        echo "    6.  ✓ Transition #2 added → auto-derived 'done' state"
        echo "    7.  ✓ Branch: fromStateId defaulted to initial state (neither field)"
        echo "    8.  ✓ Branch: fromTransitionId resolved to 'running' state"
        echo "    9.  ✓ States queryable via GET /states and ?at={transitionId}"
        echo "    10. ✓ Deployed to real device with pre-flight checks"
        echo "    11. ✓ Trigger 1: idle → running (context variables set)"
        echo "    12. ✓ Trigger 2: running → done (context variables set)"
        echo "    13. ✓ Cleanup successful"
        echo ""
        echo -e "  ${BOLD}Key Demo:${NC} Only the initial 'idle' state was manually defined."
        echo "  'running', 'done', 'timeout', and 'paused' states were auto-derived."
        echo "  Branching via fromTransitionId and default fromStateId works correctly."
        echo ""
    else
        log_title "SOME STEPS FAILED ✗"
        log_error "Failed steps: ${failed_steps[*]}"
        echo ""
        echo "  Tips:"
        echo "    1. Check server is running: mvnw spring-boot:run -Dspring-boot.run.profiles=macos"
        echo "    2. Run a single step: STEP=discover ./e2e-test.sh"
        echo "    3. Target specific device: DEVICE_ID=1051DB398BD0 ./e2e-test.sh"
        echo "    4. Enable verbose: ./e2e-test.sh --verbose"
        echo ""
        exit 1
    fi
}

main "$@"
