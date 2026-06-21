#!/usr/bin/env bash
# ─────────────────────────────────────────────────────────────────────────────
# common.sh — Shared utilities for state machine API test scripts
#
# No mock data. No test-only endpoints. Pure HTTP client against real APIs.
# ─────────────────────────────────────────────────────────────────────────────
set -euo pipefail

# ── Configuration (all overridable via env) ──
BASE_URL="${BASE_URL:-http://localhost:8080}"
API_PREFIX="/api/v1/sdui"
STATE_MACHINES_URL="${BASE_URL}${API_PREFIX}/state-machines"
BOARD_TYPES_URL="${BASE_URL}${API_PREFIX}/board-types"
CATALOG_URL="${BASE_URL}${API_PREFIX}/events/catalog"
DEVICES_URL="${BASE_URL}${API_PREFIX}/devices"

# Defaults — discovered dynamically at runtime
DEVICE_ID="${DEVICE_ID:-}"
BOARD_TYPE_KEY="${BOARD_TYPE_KEY:-}"
BOARD="${BOARD:-}"

# State file to pass data between steps
STATE_FILE="${STATE_FILE:-/tmp/sm-e2e-state.json}"

# ── Colors ──
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
BLUE='\033[0;34m'
CYAN='\033[0;36m'
BOLD='\033[1m'
NC='\033[0m'

# ── Logging (all to stderr to keep stdout clean for data capture) ──

log_info()  { echo -e "${BLUE}[INFO]${NC}  $*" >&2; }
log_ok()    { echo -e "${GREEN}[OK]${NC}    $*" >&2; }
log_warn()  { echo -e "${YELLOW}[WARN]${NC}  $*" >&2; }
log_error() { echo -e "${RED}[ERROR]${NC} $*" >&2; }
log_step()  { echo "" >&2; echo -e "${BOLD}${CYAN}━━━ $* ━━━${NC}" >&2; echo "" >&2; }
log_title() {
    echo "" >&2
    echo -e "${BOLD}${CYAN}╔══════════════════════════════════════════════════════════════╗${NC}" >&2
    printf "${BOLD}${CYAN}║${NC}  %s\n" "$*" >&2
    echo -e "${BOLD}${CYAN}╚══════════════════════════════════════════════════════════════╝${NC}" >&2
    echo "" >&2
}

# ── State management ──

save_state() {
    local key="$1" value="$2"
    local tmp
    if [ -f "$STATE_FILE" ]; then tmp=$(cat "$STATE_FILE"); else tmp="{}"; fi
    echo "$tmp" | jq --arg k "$key" --arg v "$value" '. + {($k): $v}' > "$STATE_FILE"
}

read_state() {
    local key="$1"
    if [ -f "$STATE_FILE" ]; then
        jq -r ".${key} // empty" "$STATE_FILE"
    else
        echo ""
    fi
}

clear_state() { rm -f "$STATE_FILE"; }

# ── HTTP helpers ──

http_get() {
    local url="$1" label="${2:-GET}"
    log_info "${label}: GET ${url}"
    local resp
    resp=$(curl -s -w "\n%{http_code}" -X GET "$url" \
        -H "Content-Type: application/json" \
        -H "X-Space-Id: default")
    local http_code body
    http_code=$(echo "$resp" | tail -1)
    body=$(echo "$resp" | sed '$d')
    log_info "  → HTTP ${http_code}"
    echo "$body"
    return 0
}

http_post() {
    local url="$1" data="$2" label="${3:-POST}"
    log_info "${label}: POST ${url}"
    if [ -n "${VERBOSE:-}" ]; then
        log_info "  Body: $(echo "$data" | jq -c '.' 2>/dev/null || echo "$data")"
    fi
    local resp
    resp=$(curl -s -w "\n%{http_code}" -X POST "$url" \
        -H "Content-Type: application/json" \
        -H "X-Space-Id: default" \
        -d "$data")
    local http_code body
    http_code=$(echo "$resp" | tail -1)
    body=$(echo "$resp" | sed '$d')
    log_info "  → HTTP ${http_code}"
    echo "$body"
    return 0
}

http_delete() {
    local url="$1" label="${2:-DELETE}"
    log_info "${label}: DELETE ${url}"
    local resp
    resp=$(curl -s -w "\n%{http_code}" -X DELETE "$url" \
        -H "Content-Type: application/json" \
        -H "X-Space-Id: default")
    local http_code body
    http_code=$(echo "$resp" | tail -1)
    body=$(echo "$resp" | sed '$d')
    log_info "  → HTTP ${http_code}"
    echo "$body"
    return 0
}

# ── ApiResponse helpers ──

# Extract .data field from ApiResponse. Returns data as JSON on stdout.
# If code != 20000, logs error to stderr and returns non-zero.
extract_data() {
    local body="$1"
    local code
    code=$(echo "$body" | jq -r '.code // empty')
    if [ "$code" != "20000" ]; then
        local msg
        msg=$(echo "$body" | jq -r '.msg // .message // "unknown error"')
        log_error "API returned code=${code}: ${msg}"
        return 1
    fi
    echo "$body" | jq -c '.data'
}

# Extract data and display it (display to stderr, data to stdout)
extract_and_show() {
    local body="$1" label="${2:-Response}"
    local data
    if data=$(extract_data "$body" 2>/dev/null); then
        echo -e "${GREEN}${label}:${NC}" >&2
        echo "$data" | jq '.' 2>/dev/null >&2 || echo "$data" >&2
        echo "$data"
    else
        return 1
    fi
}

# ── Assertions ──

assert_data() {
    local body="$1" jq_filter="$2" description="$3"
    local data
    data=$(extract_data "$body") || return 1
    local result
    result=$(echo "$data" | jq -r "$jq_filter" 2>/dev/null)
    if [ "$result" = "true" ] || [ "$result" = "yes" ] || { [ "$result" != "false" ] && [ "$result" != "null" ] && [ -n "$result" ]; }; then
        log_ok "$description: $result"
        return 0
    else
        log_error "$description FAILED: got '$result'"
        return 1
    fi
}

assert_eq() {
    local body="$1" jq_filter="$2" expected="$3" description="$4"
    local data actual
    data=$(extract_data "$body") || return 1
    actual=$(echo "$data" | jq -r "$jq_filter" 2>/dev/null)
    if [ "$actual" = "$expected" ]; then
        log_ok "$description: $actual"
        return 0
    else
        log_error "$description FAILED: expected='$expected', actual='$actual'"
        return 1
    fi
}

# ── Pre-flight ──

check_dependencies() {
    local missing=""
    for cmd in curl jq; do
        if ! command -v "$cmd" &>/dev/null; then
            log_error "Missing dependency: $cmd"
            missing="$missing $cmd"
        fi
    done
    if [ -n "$missing" ]; then
        log_error "Please install:$missing"
        exit 1
    fi
    log_ok "Dependencies: curl, jq"
}

check_server() {
    log_info "Checking server at ${BASE_URL}..."
    local code
    code=$(curl -s -o /dev/null -w "%{http_code}" "${BASE_URL}/api/v1/sdui/board-types" 2>/dev/null || echo "000")
    if [ "$code" = "200" ]; then
        log_ok "Server is reachable"
        return 0
    fi
    log_error "Cannot reach server at ${BASE_URL} (HTTP $code). Is it running?"
    log_error "Start with: mvnw spring-boot:run -Dspring-boot.run.profiles=macos"
    exit 1
}

# ── Dynamic discovery ──

# Discover the first ONLINE device and its board type
# Sets global DEVICE_ID, BOARD_TYPE_KEY, BOARD
discover_device() {
    log_info "Discovering real devices..."

    local resp data
    resp=$(http_get "${DEVICES_URL}?size=50" "List devices")
    data=$(extract_data "$resp") || { log_error "Cannot list devices"; return 1; }

    local count
    count=$(echo "$data" | jq -r '.totalCount // 0')
    log_info "Found $count device(s) total"

    # Prefer ONLINE device, fall back to any CLAIMED device
    local device_id board
    device_id=$(echo "$data" | jq -r '.items[] | select(.status == "ONLINE") | .deviceId' | head -1)
    if [ -z "$device_id" ]; then
        device_id=$(echo "$data" | jq -r '.items[] | select(.registrationStatus == "CLAIMED") | .deviceId' | head -1)
    fi
    if [ -z "$device_id" ]; then
        log_error "No usable device found (need at least one CLAIMED device)"
        echo "$data" | jq '.items[] | {deviceId, status, registrationStatus, board}' >&2
        return 1
    fi

    board=$(echo "$data" | jq -r --arg id "$device_id" '.items[] | select(.deviceId == $id) | .board')
    local status
    status=$(echo "$data" | jq -r --arg id "$device_id" '.items[] | select(.deviceId == $id) | .status')

    DEVICE_ID="$device_id"
    BOARD="$board"
    save_state "deviceId" "$device_id"
    save_state "board" "$board"

    log_ok "Device: $device_id  board=$board  status=$status"

    # Find the matching board type key
    local bt_resp bt_data type_key
    bt_resp=$(http_get "${BOARD_TYPES_URL}" "List board types")
    bt_data=$(extract_data "$bt_resp") || { log_error "Cannot list board types"; return 1; }

    type_key=$(echo "$bt_data" | jq -r --arg b "$board" '.[] | select(.board == $b) | .key' | head -1)
    if [ -z "$type_key" ]; then
        log_error "No board type found for board '$board'"
        echo "$bt_data" | jq '.[] | {key, board}' >&2
        return 1
    fi

    BOARD_TYPE_KEY="$type_key"
    save_state "boardTypeKey" "$type_key"
    log_ok "Board type: $type_key"
}
