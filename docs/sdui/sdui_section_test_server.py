#!/usr/bin/env python3
"""Section protocol test server for SDUI terminal firmware.

This is a standalone test server for the Section-based UI protocol
(MSG_SECTION_SCENE=15 / MSG_SECTION_PATCH=16). It covers the full
real-world flow:

  1. Presets ALL section configurations (12 types + combos)
  2. Reads terminal capabilities from device/capabilities
  3. Prints supported section list with compatibility matrix
  4. Lets user choose target sections or send all
  5. Simulates real-world data updates via periodic patches

Run:
    python scripts/sdui_section_test_server.py --host 0.0.0.0 --port 8081

Then configure terminal WebSocket URL to ws://<PC_IP>:8081.
"""
from __future__ import annotations

import argparse
import asyncio
import copy
import json
import random
import shlex
import struct
import time
import zlib
from dataclasses import dataclass, field
from pathlib import Path
from typing import Any, Callable

import websockets

# ---------------------------------------------------------------------------
# protocol constants
# ---------------------------------------------------------------------------
UI3_MAGIC = 0x5344
UI3_VERSION = 1

MSG_TERMINAL_HELLO = 1
MSG_EVENT_INPUT = 9
MSG_ACK = 10
MSG_ERROR = 11
MSG_SECTION_SCENE = 15
MSG_SECTION_PATCH = 16

TLV_TEMPLATE_REVISION = 30
TLV_TEMPLATE_ID = 31
TLV_TEMPLATE_PAYLOAD_JSON = 32
TLV_TERMINAL_ID = 100
TLV_HELLO_SHAPE = 101
TLV_HELLO_SCREEN_W = 102
TLV_HELLO_SCREEN_H = 103
TLV_HELLO_INPUT_CAPS = 104
TLV_HELLO_KEY_CAPS = 105
TLV_HELLO_COLOR_DEPTH = 106
TLV_HELLO_MAX_ALLOC_KB = 107
TLV_HELLO_UI_RUNTIME_CAPS = 111
TLV_EVENT_KIND = 120
TLV_EVENT_NODE_ID = 121
TLV_EVENT_TS_MS = 125
TLV_ACK_CODE = 200
TLV_ACK_DETAIL = 201

ERR_NAMES: dict[int, str] = {
    0: "OK_APPLIED",
    1: "CRC",
    2: "TLV_PARSE",
    3: "UNKNOWN_FIELD",
    4: "SCHEMA",
    5: "LAYOUT_OVERFLOW",
    6: "OOM_NODE_POOL",
    7: "OOM_RESOURCE",
    8: "UNSUPPORTED_INPUT_MAP",
}

# ---------------------------------------------------------------------------
# TLV / frame helpers
# ---------------------------------------------------------------------------


def jdump(obj: Any) -> str:
    return json.dumps(obj, ensure_ascii=False, separators=(",", ":"))


def pretty(obj: Any) -> str:
    return json.dumps(obj, ensure_ascii=False, indent=2)


def encode_tlv(t: int, value: bytes) -> bytes:
    if len(value) > 0xFFFF:
        raise ValueError(f"TLV too large: type={t} len={len(value)}")
    return struct.pack("<HH", t, len(value)) + value


def parse_tlvs(payload: bytes) -> dict[int, bytes]:
    out: dict[int, bytes] = {}
    off = 0
    while off + 4 <= len(payload):
        t, ln = struct.unpack_from("<HH", payload, off)
        off += 4
        if off + ln > len(payload):
            raise ValueError("TLV truncated")
        out[t] = payload[off : off + ln]
        off += ln
    if off != len(payload):
        raise ValueError("TLV trailing bytes")
    return out


def u16(raw: bytes | None, default: int = 0) -> int:
    if not raw or len(raw) != 2:
        return default
    return struct.unpack("<H", raw)[0]


def u32(raw: bytes | None, default: int = 0) -> int:
    if not raw or len(raw) != 4:
        return default
    return struct.unpack("<I", raw)[0]


def text(raw: bytes | None, default: str = "") -> str:
    if not raw:
        return default
    return raw.decode("utf-8", errors="replace")


def encode_frame(msg_type: int, seq: int, payload: bytes) -> bytes:
    head_without_crc = struct.pack("<HBBII", UI3_MAGIC, UI3_VERSION, msg_type, seq, len(payload))
    crc = zlib.crc32(head_without_crc + struct.pack("<I", 0) + payload) & 0xFFFFFFFF
    return head_without_crc + struct.pack("<I", crc) + payload


def decode_frame(frame: bytes) -> tuple[int, int, bytes]:
    if len(frame) < 16:
        raise ValueError("frame too short")
    magic, version, msg_type, seq, payload_len, crc = struct.unpack_from("<HBBIII", frame, 0)
    if magic != UI3_MAGIC:
        raise ValueError(f"bad magic: {magic:#x}")
    if version != UI3_VERSION:
        raise ValueError(f"bad version: {version}")
    if len(frame) != 16 + payload_len:
        raise ValueError(f"bad frame length: got={len(frame)} expected={16 + payload_len}")
    payload = frame[16:]
    head_without_crc = struct.pack("<HBBII", magic, version, msg_type, seq, payload_len)
    expect = zlib.crc32(head_without_crc + struct.pack("<I", 0) + payload) & 0xFFFFFFFF
    if expect != crc:
        raise ValueError(f"bad crc: got={crc:#x} expected={expect:#x}")
    return msg_type, seq, payload


def split_frames(blob: bytes) -> list[bytes]:
    out: list[bytes] = []
    off = 0
    while off + 16 <= len(blob):
        payload_len = int.from_bytes(blob[off + 8 : off + 12], "little")
        frame_len = 16 + payload_len
        if off + frame_len > len(blob):
            break
        out.append(blob[off : off + frame_len])
        off += frame_len
    return out or [blob]


# ---------------------------------------------------------------------------
# ALL_SECTION_TYPES — the 12 section types the runtime supports
# ---------------------------------------------------------------------------
ALL_SECTION_TYPES = [
    "hero_section",
    "metric_section",
    "chart_section",
    "timer_section",
    "image_section",
    "action_section",
    "progress_section",
    "text_section",
    "overlay_section",
    "list_section",
    "toggle_section",
    "nav_section",
]


def _sec(type_: str, section_id: str, data: dict[str, Any]) -> dict[str, Any]:
    return {"type": type_, "section_id": section_id, "data": data}


# ---------------------------------------------------------------------------
# preset catalog — all section configurations
# ---------------------------------------------------------------------------

PRESETS: dict[str, dict[str, Any]] = {}

# -- single-section presets --

PRESETS["hero_dashboard"] = {
    "page_id": "hero_dashboard",
    "layout": "vertical_scroll",
    "description": "Hero section: large value + label + icon + progress bar",
    "sections": [
        _sec(
            "hero_section",
            "cpu_hero",
            {
                "value": "85%",
                "label": "CPU Usage",
                "subtitle": "Running Normal",
                "tone": "primary",
                "icon_src": "cpu",
                "progress": 85,
            },
        )
    ],
}

PRESETS["metrics_grid"] = {
    "page_id": "metrics_grid",
    "layout": "vertical_scroll",
    "description": "Metric section: 4-value K-V grid",
    "sections": [
        _sec(
            "metric_section",
            "sys_metrics",
            {
                "metrics": [
                    {"label": "Memory", "value": "62%"},
                    {"label": "Disk", "value": "41%"},
                    {"label": "Network", "value": "1.2G"},
                    {"label": "Load", "value": "1.8"},
                ]
            },
        )
    ],
}

PRESETS["chart_trend"] = {
    "page_id": "chart_trend",
    "layout": "vertical_scroll",
    "description": "Chart section: line chart with 16 data points",
    "sections": [
        _sec(
            "chart_section",
            "trend_chart",
            {
                "title": "CPU 10min Trend",
                "points": [30, 45, 38, 55, 50, 62, 58, 64, 59, 68, 52, 47, 54, 60, 49, 57],
                "progress": 68,
            },
        )
    ],
}

PRESETS["timer_display"] = {
    "page_id": "timer_display",
    "layout": "vertical_scroll",
    "description": "Timer section: elapsed time display",
    "sections": [
        _sec(
            "timer_section",
            "task_timer",
            {
                "title": "Session Timer",
                "progress": 42,
                "timer": {"elapsed_ms": 3720000, "running": True},
            },
        )
    ],
}

PRESETS["image_card"] = {
    "page_id": "image_card",
    "layout": "vertical_scroll",
    "description": "Image section: icon + title + subtitle card",
    "sections": [
        _sec(
            "image_section",
            "status_image",
            {
                "icon_src": "start",
                "title": "System Online",
                "subtitle": "All services healthy",
            },
        )
    ],
}

PRESETS["action_buttons"] = {
    "page_id": "action_buttons",
    "layout": "vertical_scroll",
    "description": "Action section: button group with varied tones",
    "sections": [
        _sec(
            "action_section",
            "page_actions",
            {
                "actions": [
                    {"id": "refresh", "label": "Refresh", "tone": "primary", "enabled": True},
                    {"id": "detail", "label": "Detail", "tone": "secondary", "enabled": True},
                    {"id": "reboot", "label": "Reboot", "tone": "danger", "enabled": True},
                    {"id": "config", "label": "Config", "tone": "warning", "enabled": False},
                ]
            },
        )
    ],
}

PRESETS["progress_bar"] = {
    "page_id": "progress_bar",
    "layout": "vertical_scroll",
    "description": "Progress section: progress bar with label",
    "sections": [
        _sec(
            "progress_section",
            "task_progress",
            {
                "title": "Data Sync",
                "progress": 67,
                "progress_text": "67% complete",
            },
        )
    ],
}

PRESETS["text_block"] = {
    "page_id": "text_block",
    "layout": "vertical_scroll",
    "description": "Text section: title + body paragraph",
    "sections": [
        _sec(
            "text_section",
            "info_text",
            {
                "title": "System Notice",
                "body": "Scheduled maintenance will occur on Sunday at 02:00 UTC. "
                "Expected downtime is approximately 15 minutes. "
                "Please save all work before this window.",
            },
        )
    ],
}

PRESETS["overlay_notify"] = {
    "page_id": "overlay_notify",
    "layout": "fixed_single",
    "description": "Overlay section: notification popup + hero background",
    "sections": [
        _sec(
            "hero_section",
            "main_status",
            {
                "value": "Ready",
                "label": "System Status",
                "tone": "success",
                "icon_src": "start",
                "progress": 100,
            },
        ),
        _sec(
            "overlay_section",
            "notify_overlay",
            {
                "title": "New Message",
                "body": "You have 3 unread emails",
                "tone": "warning",
                "unread_count": 3,
                "auto_hide_ms": 5000,
                "visible": True,
            },
        ),
    ],
}

PRESETS["list_items"] = {
    "page_id": "list_items",
    "layout": "vertical_scroll",
    "description": "List section: scrollable items with tones",
    "sections": [
        _sec(
            "list_section",
            "msg_list",
            {
                "items": [
                    {"id": "msg1", "title": "Server Restarted", "subtitle": "2 min ago", "tone": "success"},
                    {"id": "msg2", "title": "CPU Alert: 92%", "subtitle": "5 min ago", "tone": "danger"},
                    {"id": "msg3", "title": "Backup Complete", "subtitle": "12 min ago", "tone": "primary"},
                    {"id": "msg4", "title": "Update Available", "subtitle": "1 hour ago", "tone": "warning"},
                ]
            },
        )
    ],
}

PRESETS["toggle_switches"] = {
    "page_id": "toggle_switches",
    "layout": "vertical_scroll",
    "description": "Toggle section: on/off switches",
    "sections": [
        _sec(
            "toggle_section",
            "feature_toggles",
            {
                "options": [
                    {"id": "wifi", "label": "Wi-Fi", "active": True},
                    {"id": "bt", "label": "Bluetooth", "active": False},
                    {"id": "auto_update", "label": "Auto Update", "active": True},
                ]
            },
        )
    ],
}

PRESETS["nav_tabs"] = {
    "page_id": "nav_tabs",
    "layout": "horizontal_pages",
    "auto_scroll": True,
    "auto_scroll_ms": 3000,
    "description": "Nav section: tab bar for page switching",
    "sections": [
        _sec(
            "nav_section",
            "main_nav",
            {
                "tabs": [
                    {"id": "tab_status", "label": "Status"},
                    {"id": "tab_metrics", "label": "Metrics"},
                    {"id": "tab_logs", "label": "Logs"},
                    {"id": "tab_settings", "label": "Settings"},
                ],
                "active_tab": 0,
            },
        ),
        _sec(
            "hero_section",
            "status_hero",
            {
                "value": "Online",
                "label": "System Status",
                "tone": "success",
                "icon_src": "start",
                "progress": 100,
            },
        ),
    ],
}

# -- multi-section presets --

PRESETS["full_dashboard"] = {
    "page_id": "full_dashboard_v1",
    "layout": "vertical_scroll",
    "auto_scroll": True,
    "auto_scroll_ms": 3000,
    "description": "Full dashboard: hero + metrics + chart + actions",
    "sections": [
        _sec(
            "hero_section",
            "cpu_hero",
            {
                "value": "85%",
                "label": "CPU Usage",
                "subtitle": "Running Normal",
                "tone": "primary",
                "icon_src": "cpu",
                "progress": 85,
            },
        ),
        _sec(
            "metric_section",
            "sys_metrics",
            {
                "metrics": [
                    {"label": "Memory", "value": "62%"},
                    {"label": "Disk", "value": "41%"},
                    {"label": "Network", "value": "1.2G"},
                    {"label": "Load", "value": "1.8"},
                ]
            },
        ),
        _sec(
            "chart_section",
            "trend_chart",
            {
                "title": "CPU 10min",
                "points": [30, 45, 38, 55, 50, 62, 58, 64, 59, 68, 52, 47, 54, 60, 49, 57],
                "progress": 68,
            },
        ),
        _sec(
            "action_section",
            "page_actions",
            {
                "actions": [
                    {"id": "refresh", "label": "Refresh", "tone": "primary", "enabled": True},
                    {"id": "detail", "label": "Detail", "tone": "secondary", "enabled": True},
                ]
            },
        ),
    ],
}

PRESETS["system_overview"] = {
    "page_id": "system_overview",
    "layout": "vertical_scroll",
    "auto_scroll": True,
    "auto_scroll_ms": 3000,
    "description": "System overview: hero + metrics + progress + chart",
    "sections": [
        _sec(
            "hero_section",
            "health_hero",
            {
                "value": "98%",
                "label": "System Health",
                "subtitle": "All systems nominal",
                "tone": "success",
                "icon_src": "start",
                "progress": 98,
            },
        ),
        _sec(
            "metric_section",
            "res_metrics",
            {
                "metrics": [
                    {"label": "CPU", "value": "23%"},
                    {"label": "RAM", "value": "5.2G"},
                    {"label": "IOPS", "value": "1.4K"},
                    {"label": "Temp", "value": "47C"},
                ]
            },
        ),
        _sec(
            "progress_section",
            "backup_progress",
            {
                "title": "Backup Progress",
                "progress": 72,
                "progress_text": "72% — ETA 3 min",
            },
        ),
        _sec(
            "chart_section",
            "load_chart",
            {
                "title": "Load 1h",
                "points": [20, 25, 22, 35, 30, 42, 38, 44, 40, 50, 35, 28, 32, 38, 26, 30],
                "progress": 50,
            },
        ),
    ],
}

PRESETS["alert_center"] = {
    "page_id": "alert_center",
    "layout": "fixed_single",
    "description": "Alert center: overlay notification + alert list",
    "sections": [
        _sec(
            "overlay_section",
            "critical_alert",
            {
                "title": "Critical Alert",
                "body": "Disk usage exceeded 90% threshold",
                "tone": "danger",
                "unread_count": 1,
                "auto_hide_ms": 0,
                "visible": True,
            },
        ),
        _sec(
            "list_section",
            "alert_list",
            {
                "items": [
                    {"id": "a1", "title": "CPU spike detected", "subtitle": "10:23 AM", "tone": "warning"},
                    {"id": "a2", "title": "Backup failed", "subtitle": "09:15 AM", "tone": "danger"},
                    {"id": "a3", "title": "Update completed", "subtitle": "08:00 AM", "tone": "success"},
                    {"id": "a4", "title": "New device paired", "subtitle": "07:42 AM", "tone": "primary"},
                ]
            },
        ),
    ],
}

PRESETS["settings_panel"] = {
    "page_id": "settings_panel",
    "layout": "vertical_scroll",
    "auto_scroll": True,
    "auto_scroll_ms": 3000,
    "description": "Settings panel: toggles + tabs + action buttons",
    "sections": [
        _sec(
            "toggle_section",
            "pref_toggles",
            {
                "options": [
                    {"id": "notifications", "label": "Notifications", "active": True},
                    {"id": "dark_mode", "label": "Dark Mode", "active": True},
                    {"id": "analytics", "label": "Analytics", "active": False},
                ]
            },
        ),
        _sec(
            "nav_section",
            "settings_nav",
            {
                "tabs": [
                    {"id": "tab_general", "label": "General"},
                    {"id": "tab_network", "label": "Network"},
                    {"id": "tab_advanced", "label": "Advanced"},
                ],
                "active_tab": 0,
            },
        ),
        _sec(
            "action_section",
            "settings_actions",
            {
                "actions": [
                    {"id": "save", "label": "Save", "tone": "primary", "enabled": True},
                    {"id": "reset", "label": "Reset", "tone": "danger", "enabled": True},
                ]
            },
        ),
    ],
}

PRESETS["monitoring_panel"] = {
    "page_id": "monitoring_panel",
    "layout": "vertical_scroll",
    "auto_scroll": True,
    "auto_scroll_ms": 3500,
    "description": "Monitoring panel: metrics + chart + timer",
    "sections": [
        _sec(
            "metric_section",
            "perf_metrics",
            {
                "metrics": [
                    {"label": "QPS", "value": "3.2K"},
                    {"label": "P99", "value": "45ms"},
                    {"label": "Errors", "value": "0.1%"},
                    {"label": "Uptime", "value": "12d"},
                ]
            },
        ),
        _sec(
            "chart_section",
            "qps_chart",
            {
                "title": "QPS 24h",
                "points": [55, 60, 58, 72, 65, 70, 80, 75, 68, 62, 58, 64, 70, 72, 68, 74],
                "progress": 74,
            },
        ),
        _sec(
            "timer_section",
            "incident_timer",
            {
                "title": "Time Since Last Incident",
                "progress": 88,
                "timer": {"elapsed_ms": 86400000, "running": True},
            },
        ),
    ],
}

PRESETS["content_page"] = {
    "page_id": "content_page",
    "layout": "vertical_scroll",
    "auto_scroll": True,
    "auto_scroll_ms": 3000,
    "description": "Content page: image + text + actions",
    "sections": [
        _sec(
            "image_section",
            "banner_image",
            {
                "icon_src": "start",
                "title": "Welcome",
                "subtitle": "v3.2.1 — Latest Release",
            },
        ),
        _sec(
            "text_section",
            "release_notes",
            {
                "title": "Release Notes",
                "body": "This update includes performance improvements, "
                "security patches, and new dashboard widgets. "
                "See the full changelog for details.",
            },
        ),
        _sec(
            "action_section",
            "content_actions",
            {
                "actions": [
                    {"id": "learn_more", "label": "Learn More", "tone": "primary", "enabled": True},
                    {"id": "dismiss", "label": "Dismiss", "tone": "secondary", "enabled": True},
                ]
            },
        ),
    ],
}

# "all_sections" — all 12 section types, one per horizontal page
PRESETS["all_sections"] = {
    "page_id": "all_sections",
    "layout": "horizontal_pages",
    "auto_scroll": True,
    "auto_scroll_ms": 2500,
    "description": "All 12 section types (horizontal pages)",
    "sections": [
        _sec("hero_section", "hero_1", {"value": "85%", "label": "CPU", "subtitle": "Running", "tone": "primary", "icon_src": "cpu", "progress": 85}),
        _sec("metric_section", "metric_1", {"metrics": [{"label": "CPU", "value": "23%"}, {"label": "RAM", "value": "62%"}]}),
        _sec("chart_section", "chart_1", {"title": "Trend", "points": [30, 45, 38, 55, 50, 62, 58, 64, 59, 68, 52, 47, 54, 60, 49, 57], "progress": 68}),
        _sec("timer_section", "timer_1", {"title": "Timer", "timer": {"elapsed_ms": 3720000, "running": True}}),
        _sec("image_section", "image_1", {"icon_src": "start", "title": "Status", "subtitle": "Online"}),
        _sec("action_section", "action_1", {"actions": [{"id": "a1", "label": "OK", "tone": "primary", "enabled": True}, {"id": "a2", "label": "Cancel", "tone": "danger", "enabled": True}]}),
        _sec("progress_section", "progress_1", {"title": "Sync", "progress": 67, "progress_text": "67%"}),
        _sec("text_section", "text_1", {"title": "Notice", "body": "Scheduled maintenance at 02:00 UTC. Expected downtime ~15 minutes."}),
        _sec("overlay_section", "overlay_1", {"title": "Alert", "body": "Disk at 90%", "tone": "warning", "unread_count": 2, "auto_hide_ms": 5000, "visible": True}),
        _sec("list_section", "list_1", {"items": [{"id": "l1", "title": "Server OK", "subtitle": "2m ago", "tone": "success"}, {"id": "l2", "title": "CPU 92%", "subtitle": "5m ago", "tone": "danger"}]}),
        _sec("toggle_section", "toggle_1", {"options": [{"id": "t1", "label": "Wi-Fi", "active": True}, {"id": "t2", "label": "BT", "active": False}]}),
        _sec("nav_section", "nav_1", {"tabs": [{"id": "n1", "label": "Home"}, {"id": "n2", "label": "Stats"}, {"id": "n3", "label": "Settings"}], "active_tab": 0}),
    ],
}

# "stress_test" — all 12 types in vertical_scroll (tests layout engine pagination)
PRESETS["stress_test"] = {
    "page_id": "stress_test",
    "layout": "horizontal_pages",
    "auto_scroll": True,
    "auto_scroll_ms": 2500,
    "description": "Stress test: all 12 section types (horizontal pages, tests pagination)",
    "sections": PRESETS["all_sections"]["sections"],
}

# ---------------------------------------------------------------------------
# auto-update patch generators
# ---------------------------------------------------------------------------


def _patch_item(section_id: str, data: dict[str, Any]) -> dict[str, Any]:
    return {"section_id": section_id, "op": "update", "data": data}


_AUTO_GENERATORS: dict[str, Callable[[dict[str, Any], int, int], dict[str, Any] | None]] = {}


def _register(type_name: str):
    """Decorator to register an auto-update generator for a section type."""

    def decorator(fn):
        _AUTO_GENERATORS[type_name] = fn
        return fn

    return decorator


@_register("hero_section")
def _patch_hero(data: dict[str, Any], tick: int, interval_s: int) -> dict[str, Any] | None:
    """Random walk the hero value and update tone/progress."""
    cur = int(data.get("value", "50%").rstrip("%"))
    cur = cur + random.randint(-5, 5)
    cur = max(5, min(99, cur))
    tone = "danger" if cur > 90 else "warning" if cur > 70 else "success" if cur < 30 else "primary"
    return {"value": f"{cur}%", "progress": cur, "tone": tone, "subtitle": _subtitle_for_value(cur)}


@_register("metric_section")
def _patch_metric(data: dict[str, Any], tick: int, interval_s: int) -> dict[str, Any] | None:
    """Random walk each metric value."""
    metrics = data.get("metrics", [])
    for m in metrics:
        old = m.get("value", "0%")
        if old.endswith("%"):
            v = int(old.rstrip("%")) + random.randint(-3, 3)
            m["value"] = f"{max(0, min(100, v))}%"
        elif old.endswith("G"):
            v = float(old.rstrip("G")) + random.uniform(-0.3, 0.3)
            m["value"] = f"{max(0, round(v, 1)):.1f}G"
        elif old.endswith("K"):
            v = float(old.rstrip("K")) + random.uniform(-0.1, 0.1)
            m["value"] = f"{max(0, round(v, 1)):.1f}K"
        elif old.endswith("ms"):
            v = int(old.rstrip("ms")) + random.randint(-5, 5)
            m["value"] = f"{max(1, v)}ms"
        elif old.endswith("d"):
            v = int(old.rstrip("d")) + (1 if random.random() < 0.1 else 0)
            m["value"] = f"{v}d"
    return {"metrics": metrics}


@_register("chart_section")
def _patch_chart(data: dict[str, Any], tick: int, interval_s: int) -> dict[str, Any] | None:
    """Shift points left and append a new random value."""
    pts: list[int] = data.get("points", [])
    if not pts:
        return None
    last = pts[-1]
    new_val = last + random.randint(-12, 12)
    new_val = max(0, min(100, new_val))
    shifted = pts[1:] + [new_val]
    return {"points": shifted, "progress": new_val}


@_register("timer_section")
def _patch_timer(data: dict[str, Any], tick: int, interval_s: int) -> dict[str, Any] | None:
    """Increment elapsed time."""
    timer = data.get("timer", {})
    if not timer or not timer.get("running", True):
        return None
    elapsed = timer.get("elapsed_ms", 0) + interval_s * 1000
    return {"timer": {**timer, "elapsed_ms": elapsed}}


@_register("progress_section")
def _patch_progress(data: dict[str, Any], tick: int, interval_s: int) -> dict[str, Any] | None:
    """Random walk progress value."""
    cur = data.get("progress", 50)
    cur = cur + random.randint(-3, 3)
    cur = max(0, min(100, cur))
    return {"progress": cur, "progress_text": f"{cur}% complete"}


@_register("overlay_section")
def _patch_overlay(data: dict[str, Any], tick: int, interval_s: int) -> dict[str, Any] | None:
    """Cycle overlay messages."""
    messages = [
        ("CPU Usage Alert", "Usage exceeded 85% threshold", "warning", 1),
        ("Backup Complete", "Daily backup finished successfully", "success", 1),
        ("Connection Lost", "Wi-Fi disconnected from AP", "danger", 2),
        ("Update Ready", "Firmware v3.2.1 available", "primary", 3),
        ("Disk Warning", "Storage at 82% capacity", "warning", 1),
    ]
    msg = messages[tick % len(messages)]
    return {
        "title": msg[0],
        "body": msg[1],
        "tone": msg[2],
        "unread_count": msg[3],
        "auto_hide_ms": 5000,
        "visible": True,
    }


@_register("list_section")
def _patch_list(data: dict[str, Any], tick: int, interval_s: int) -> dict[str, Any] | None:
    """Rotate item titles and tones."""
    items: list[dict[str, Any]] = data.get("items", [])
    if not items:
        return None
    tones = ["success", "warning", "danger", "primary"]
    for i, item in enumerate(items):
        item["tone"] = tones[(tick + i) % len(tones)]
        item["subtitle"] = f"{random.randint(1, 60)} min ago"
    return {"items": items}


@_register("toggle_section")
def _patch_toggle(data: dict[str, Any], tick: int, interval_s: int) -> dict[str, Any] | None:
    """Randomly flip toggle states."""
    options: list[dict[str, Any]] = data.get("options", [])
    if not options:
        return None
    if tick % 3 == 0:  # flip one every 3 ticks
        idx = random.randint(0, len(options) - 1)
        options[idx]["active"] = not options[idx].get("active", False)
    return {"options": options}


@_register("nav_section")
def _patch_nav(data: dict[str, Any], tick: int, interval_s: int) -> dict[str, Any] | None:
    """Cycle active tab. Include full tabs array so the renderer has complete data."""
    tabs: list[dict[str, Any]] = data.get("tabs", [])
    if not tabs:
        return None
    new_active = tick % len(tabs)
    return {"tabs": tabs, "active_tab": new_active}


# static sections: no auto-update
_register("image_section")(lambda d, t, i: None)
_register("action_section")(lambda d, t, i: None)
_register("text_section")(lambda d, t, i: None)


def _subtitle_for_value(v: int) -> str:
    if v > 90:
        return "Critical Load"
    elif v > 70:
        return "High Load Warning"
    elif v < 20:
        return "Idle — Low Usage"
    else:
        return "Running Normal"


def generate_patches(preset: dict[str, Any], tick: int, interval_s: int) -> list[dict[str, Any]]:
    """Generate one round of patch updates for all sections in a preset."""
    patches: list[dict[str, Any]] = []
    for sec in preset.get("sections", []):
        type_ = sec.get("type", "")
        sec_id = sec.get("section_id", "")
        gen = _AUTO_GENERATORS.get(type_)
        if gen is None:
            continue
        data = copy.deepcopy(sec.get("data", {}))
        result = gen(data, tick, interval_s)
        if result is not None:
            patches.append(_patch_item(sec_id, result))
    return patches


# ---------------------------------------------------------------------------
# client state
# ---------------------------------------------------------------------------


@dataclass
class Client:
    key: str
    ws: Any
    remote: str
    connected_at: float = field(default_factory=time.time)
    last_seen: float = field(default_factory=time.time)
    device_id: str = ""
    terminal_id: str = ""
    screen_w: int = 0
    screen_h: int = 0
    shape: int = 0
    input_caps: int = 0
    key_caps: int = 0
    color_depth: int = 0
    max_alloc_kb: int = 0
    ui_runtime_caps: int = 0
    next_seq: int = 1
    capabilities: dict[str, Any] = field(default_factory=dict)

    # section-specific state
    supported_section_types: list[str] = field(default_factory=list)
    supported_layouts: list[str] = field(default_factory=list)
    section_limits: dict[str, Any] = field(default_factory=dict)
    active_preset: str = ""
    auto_update_task: asyncio.Task[Any] | None = field(default=None, repr=False)
    auto_update_tick: int = 0

    @property
    def id(self) -> str:
        return self.device_id or self.terminal_id or self.key

    @property
    def has_capabilities(self) -> bool:
        return bool(self.supported_section_types)


# ---------------------------------------------------------------------------
# server
# ---------------------------------------------------------------------------


class SectionTestServer:
    def __init__(self, host: str, port: int) -> None:
        self.host = host
        self.port = port
        self.clients: dict[str, Client] = {}
        self.by_device: dict[str, str] = {}
        self._client_seq = 1
        self._server: Any = None

    def _new_key(self) -> str:
        key = f"client-{self._client_seq}"
        self._client_seq += 1
        return key

    def _resolve(self, target: str) -> list[Client]:
        if target == "all":
            return list(self.clients.values())
        if target in self.clients:
            return [self.clients[target]]
        key = self.by_device.get(target)
        if key and key in self.clients:
            return [self.clients[key]]
        return [c for c in self.clients.values() if c.id == target or c.terminal_id == target]

    # ---- frame sending ----

    async def _send_section_frame(self, client: Client, msg_type: int, payload_obj: dict[str, Any]) -> None:
        payload_json = jdump(payload_obj).encode("utf-8")
        seq = client.next_seq
        client.next_seq += 1
        tlv = (
            encode_tlv(TLV_TEMPLATE_REVISION, struct.pack("<I", int(time.time())))
            + encode_tlv(TLV_TEMPLATE_ID, b"section")
            + encode_tlv(TLV_TEMPLATE_PAYLOAD_JSON, payload_json)
        )
        frame = encode_frame(msg_type, seq, tlv)
        await client.ws.send(frame)

    async def send_scene(self, target: str, preset_name: str) -> None:
        if preset_name not in PRESETS:
            print(f"[WARN] unknown preset: {preset_name}")
            return
        preset = PRESETS[preset_name]
        clients = self._resolve(target)
        if not clients:
            print(f"[WARN] no target: {target}")
            return
        for client in clients:
            await self._send_section_frame(client, MSG_SECTION_SCENE, preset)
            client.active_preset = preset_name
            client.auto_update_tick = 0
            label = preset.get("layout", "?")
            sec_ids = [s.get("section_id", "?") for s in preset.get("sections", [])]
            print(f"[TX SCENE] {preset_name} -> {client.id} layout={label} sections={sec_ids}")

    async def send_patches(self, client: Client, patches: list[dict[str, Any]]) -> None:
        if not patches:
            return
        preset_name = client.active_preset
        page_id = PRESETS.get(preset_name, {}).get("page_id", "unknown")
        payload = {"page_id": page_id, "patches": patches}
        await self._send_section_frame(client, MSG_SECTION_PATCH, payload)
        sec_ids = [p.get("section_id", "?") for p in patches]
        print(f"[TX PATCH] tick={client.auto_update_tick} -> {client.id} sections={sec_ids}")

    # ---- auto-update ----

    async def _auto_update_loop(self, client: Client, interval_s: float) -> None:
        print(f"[AUTO] started for {client.id} preset={client.active_preset} interval={interval_s}s")
        try:
            while True:
                await asyncio.sleep(interval_s)
                client.auto_update_tick += 1
                preset = PRESETS.get(client.active_preset)
                if not preset:
                    continue
                patches = generate_patches(preset, client.auto_update_tick, int(interval_s))
                await self.send_patches(client, patches)
        except asyncio.CancelledError:
            pass
        finally:
            print(f"[AUTO] stopped for {client.id}")

    def start_auto_update(self, target: str, interval_s: float) -> None:
        clients = self._resolve(target)
        for client in clients:
            if not client.active_preset:
                print(f"[WARN] {client.id}: no active preset — send a scene first")
                continue
            if client.auto_update_task and not client.auto_update_task.done():
                client.auto_update_task.cancel()
            client.auto_update_tick = 0
            client.auto_update_task = asyncio.create_task(self._auto_update_loop(client, interval_s))

    def stop_auto_update(self, target: str) -> None:
        clients = self._resolve(target)
        for client in clients:
            if client.auto_update_task and not client.auto_update_task.done():
                client.auto_update_task.cancel()

    # ---- WebSocket handler ----

    async def handler(self, ws: Any) -> None:
        key = self._new_key()
        remote = str(getattr(ws, "remote_address", "unknown"))
        client = Client(key=key, ws=ws, remote=remote)
        self.clients[key] = client
        print(f"[CONNECT] {key} from {remote}")
        try:
            async for msg in ws:
                client.last_seen = time.time()
                if isinstance(msg, bytes):
                    await self._handle_binary(client, msg)
                else:
                    await self._handle_json(client, msg)
        except websockets.ConnectionClosed:
            pass
        finally:
            self.stop_auto_update(key)
            print(f"[DISCONNECT] {client.id}")
            self.clients.pop(key, None)
            if client.device_id:
                self.by_device.pop(client.device_id, None)
            if client.terminal_id:
                self.by_device.pop(client.terminal_id, None)

    async def _handle_json(self, client: Client, raw: str) -> None:
        try:
            obj = json.loads(raw)
        except json.JSONDecodeError:
            print(f"[RX TEXT] {client.id} {raw[:240]}")
            return
        topic = obj.get("topic", "")
        device_id = obj.get("device_id")
        payload = obj.get("payload")
        if isinstance(device_id, str) and device_id:
            client.device_id = device_id
            self.by_device[device_id] = client.key
        if topic == "device/capabilities" and isinstance(payload, dict):
            client.capabilities = payload
            self._parse_section_caps(client, payload)
            return
        print(f"[RX] {client.id} topic={topic}")

    async def _handle_binary(self, client: Client, blob: bytes) -> None:
        for frame in split_frames(blob):
            try:
                msg_type, seq, payload = decode_frame(frame)
                tlvs = parse_tlvs(payload)
            except Exception as exc:
                print(f"[RX BIN] {client.id} decode failed: {exc}")
                continue
            if msg_type == MSG_TERMINAL_HELLO:
                client.terminal_id = text(tlvs.get(TLV_TERMINAL_ID), client.terminal_id)
                if client.terminal_id:
                    self.by_device[client.terminal_id] = client.key
                client.shape = u16(tlvs.get(TLV_HELLO_SHAPE))
                client.screen_w = u16(tlvs.get(TLV_HELLO_SCREEN_W))
                client.screen_h = u16(tlvs.get(TLV_HELLO_SCREEN_H))
                client.input_caps = u16(tlvs.get(TLV_HELLO_INPUT_CAPS))
                client.key_caps = u16(tlvs.get(TLV_HELLO_KEY_CAPS))
                client.color_depth = u16(tlvs.get(TLV_HELLO_COLOR_DEPTH))
                client.max_alloc_kb = u16(tlvs.get(TLV_HELLO_MAX_ALLOC_KB))
                client.ui_runtime_caps = u32(tlvs.get(TLV_HELLO_UI_RUNTIME_CAPS))
                print(
                    f"[HELLO] {client.id} {client.screen_w}x{client.screen_h} "
                    f"shape={client.shape} input=0x{client.input_caps:x} ui_caps=0x{client.ui_runtime_caps:x}"
                )
            elif msg_type == MSG_ACK:
                code = u16(tlvs.get(TLV_ACK_CODE))
                detail = text(tlvs.get(TLV_ACK_DETAIL))
                print(f"[ACK] {client.id} seq={seq} code={ERR_NAMES.get(code, code)} detail={detail}")
            elif msg_type == MSG_ERROR:
                code = u16(tlvs.get(TLV_ACK_CODE))
                detail = text(tlvs.get(TLV_ACK_DETAIL))
                print(f"[ERROR] {client.id} seq={seq} code={ERR_NAMES.get(code, code)} detail={detail}")
            elif msg_type == MSG_EVENT_INPUT:
                event_kind = u16(tlvs.get(TLV_EVENT_KIND))
                node_id = text(tlvs.get(TLV_EVENT_NODE_ID), "unknown")
                ts = u32(tlvs.get(TLV_EVENT_TS_MS))
                print(f"[EVENT] {client.id} kind={event_kind} node={node_id} ts={ts}")
            else:
                print(f"[RX BIN] {client.id} msg_type={msg_type} seq={seq}")

    def _parse_section_caps(self, client: Client, caps: dict[str, Any]) -> None:
        """Extract supported section types, layouts, and limits from device/capabilities."""
        outputs = caps.get("outputs", [])
        for item in outputs:
            if not isinstance(item, dict):
                continue
            capability = item.get("capability", "")
            if "section" not in capability:
                continue
            types = item.get("supported_section_types", [])
            layouts = item.get("supported_layouts", [])
            limits = item.get("limits", {})
            if isinstance(types, list):
                client.supported_section_types = [t for t in types if isinstance(t, str)]
            if isinstance(layouts, list):
                client.supported_layouts = [t for t in layouts if isinstance(t, str)]
            if isinstance(limits, dict):
                client.section_limits = limits

        self._print_section_caps(client)

    def _print_section_caps(self, client: Client) -> None:
        caps = client.capabilities
        profile = caps.get("device_profile", {}) if isinstance(caps, dict) else {}
        screen = profile.get("screen", {}) if isinstance(profile, dict) else {}

        print(f"\n{'=' * 60}")
        print(f"[CAPS] {client.id}")
        print(f"  Board: {profile.get('board', '?')}  "
              f"Screen: {screen.get('w', '?')}x{screen.get('h', '?')}  "
              f"Shape: {screen.get('shape', '?')}  "
              f"Input: {profile.get('input_mode', '?')}")
        print(f"  Supported section types ({len(client.supported_section_types)}):")
        for t in client.supported_section_types:
            print(f"    - {t}")
        print(f"  Supported layouts ({len(client.supported_layouts)}):")
        for lt in client.supported_layouts:
            print(f"    - {lt}")
        if client.section_limits:
            print(f"  Limits: {pretty(client.section_limits)}")

        # compatibility summary
        supported_set = set(client.supported_section_types)
        compatible = 0
        total = len(PRESETS)
        for name, preset in PRESETS.items():
            types_in_preset = {s["type"] for s in preset.get("sections", [])}
            if types_in_preset.issubset(supported_set) or not supported_set:
                compatible += 1
        print(f"  Compatible presets: {compatible}/{total}")
        print(f"{'=' * 60}\n")
        print("Type 'presets' for the full catalog, or 'send <name>' to push a scene.\n")

    # ---- interactive CLI ----

    async def command_loop(self) -> None:
        print_help()
        while True:
            try:
                line = await asyncio.to_thread(input, "section> ")
            except (EOFError, KeyboardInterrupt):
                line = "quit"
            line = line.strip()
            if not line:
                continue
            try:
                stop = await self._run_command(line)
            except Exception as exc:
                print(f"[CMD ERROR] {exc}")
                stop = False
            if stop:
                return

    async def _run_command(self, line: str) -> bool:
        parts = shlex.split(line)
        if not parts:
            return False
        cmd = parts[0].lower()

        if cmd in {"quit", "exit"}:
            return True
        if cmd in {"help", "?"}:
            print_help()
            return False
        if cmd == "devices":
            self._print_devices()
            return False
        if cmd == "caps":
            target = parts[1] if len(parts) > 1 else "all"
            self._print_caps(target)
            return False
        if cmd == "presets":
            self._print_presets(parts[1] if len(parts) > 1 else "all")
            return False
        if cmd == "send":
            if len(parts) < 2:
                print("usage: send <preset_name> [target|all]")
                return False
            target = parts[2] if len(parts) > 2 else "all"
            await self.send_scene(target, parts[1])
            return False
        if cmd == "send_all":
            target = parts[1] if len(parts) > 1 else "all"
            await self._send_all(target)
            return False
        if cmd == "select":
            if len(parts) < 2:
                print("usage: select <type1,type2,...> [target|all]")
                print("  e.g.: select hero_section,metric_section,chart_section")
                return False
            target = parts[2] if len(parts) > 2 else "all"
            types = [t.strip() for t in parts[1].split(",")]
            await self._send_selected(target, types)
            return False
        if cmd == "auto":
            if len(parts) < 2:
                print("usage: auto <preset_name|stop> [interval_s] [target|all]")
                print("  e.g.: auto full_dashboard 3")
                print("        auto stop")
                return False
            if parts[1].lower() == "stop":
                target = parts[2] if len(parts) > 2 else "all"
                self.stop_auto_update(target)
                return False
            interval = float(parts[2]) if len(parts) > 2 else 3.0
            target = parts[3] if len(parts) > 3 else "all"
            clients = self._resolve(target)
            for c in clients:
                if c.active_preset != parts[1]:
                    await self.send_scene(c.key, parts[1])
            self.start_auto_update(target, interval)
            return False
        print(f"unknown command: {cmd}")
        print_help()
        return False

    async def _send_all(self, target: str) -> None:
        """Build and send a scene containing all section types the terminal supports."""
        clients = self._resolve(target)
        for client in clients:
            supported = set(client.supported_section_types) if client.has_capabilities else set(ALL_SECTION_TYPES)
            sections = _build_all_sections_scene(supported)
            page_id = "all_supported"
            layout = "vertical_scroll" if "vertical_scroll" in client.supported_layouts else "horizontal_pages"
            preset = {"page_id": page_id, "layout": layout, "auto_scroll": True, "auto_scroll_ms": 3000, "sections": sections}
            await self._send_section_frame(client, MSG_SECTION_SCENE, preset)
            client.active_preset = "__all_supported"
            client.auto_update_tick = 0
            print(f"[TX ALL] {len(sections)} sections -> {client.id} layout={layout}")

    async def _send_selected(self, target: str, types: list[str]) -> None:
        """Build and send a scene with user-selected section types."""
        clients = self._resolve(target)
        supported = set(ALL_SECTION_TYPES)
        for client in clients:
            if client.has_capabilities:
                supported = set(client.supported_section_types)
            break
        sections = _build_all_sections_scene(supported, filter_types=types)
        if not sections:
            print(f"[WARN] no matching sections for types: {types}")
            return
        for client in clients:
            layout = "vertical_scroll" if "vertical_scroll" in client.supported_layouts else "horizontal_pages"
            preset = {"page_id": "custom_selection", "layout": layout, "auto_scroll": True, "auto_scroll_ms": 3000, "sections": sections}
            await self._send_section_frame(client, MSG_SECTION_SCENE, preset)
            client.active_preset = "__custom"
            client.auto_update_tick = 0
            print(f"[TX SELECT] {[s['type'] for s in sections]} -> {client.id}")

    def _print_devices(self) -> None:
        if not self.clients:
            print("no connected terminals")
            return
        for client in self.clients.values():
            age = int(time.time() - client.connected_at)
            seen = int(time.time() - client.last_seen)
            auto = "AUTO" if client.auto_update_task and not client.auto_update_task.done() else ""
            print(
                f"  {client.key}  id={client.id}  "
                f"screen={client.screen_w}x{client.screen_h}  "
                f"preset={client.active_preset or '-'}  "
                f"sections={len(client.supported_section_types)}  "
                f"conn={age}s  seen={seen}s  {auto}"
            )

    def _print_caps(self, target: str) -> None:
        clients = self._resolve(target)
        if not clients:
            print(f"[WARN] no target: {target}")
            return
        for client in clients:
            if not client.has_capabilities:
                print(f"{client.id}: no device/capabilities received yet")
            else:
                self._print_section_caps(client)

    def _print_presets(self, target: str) -> None:
        clients = self._resolve(target)
        supported_set: set[str] = set(ALL_SECTION_TYPES)
        if clients:
            for client in clients:
                if client.has_capabilities:
                    supported_set = set(client.supported_section_types)
                    break

        print(f"\n{'name':<24} {'layout':<18} {'compat':<8} description")
        print("-" * 80)
        for name in sorted(PRESETS):
            preset = PRESETS[name]
            types = [s["type"] for s in preset.get("sections", [])]
            all_ok = all(t in supported_set for t in types)
            compat = "OK" if all_ok else "PARTIAL" if any(t in supported_set for t in types) else "NO"
            layout = preset.get("layout", "?")
            desc = preset.get("description", "")
            print(f"  {name:<22} {layout:<18} {compat:<8} {desc}")
            print(f"  {'':>24}sections: {', '.join(types)}")
        print()

    async def start(self) -> None:
        self._server = await websockets.serve(self.handler, self.host, self.port, max_size=4 * 1024 * 1024)
        print(f"Section test server listening on ws://{self.host}:{self.port}")
        print(f"Presets loaded: {len(PRESETS)}")
        print()
        await self.command_loop()
        self._server.close()
        await self._server.wait_closed()


# ---------------------------------------------------------------------------
# helper: build "all sections" scene
# ---------------------------------------------------------------------------

# fallback data for each section type when building ad-hoc scenes
_FALLBACK_SECTION_DATA: dict[str, dict[str, Any]] = {
    "hero_section": {"value": "72%", "label": "Status", "subtitle": "Operational", "tone": "primary", "icon_src": "cpu", "progress": 72},
    "metric_section": {"metrics": [{"label": "CPU", "value": "45%"}, {"label": "RAM", "value": "58%"}]},
    "chart_section": {"title": "Trend", "points": [30, 45, 38, 55, 50, 62, 58, 64, 59, 68, 52, 47, 54, 60, 49, 57], "progress": 68},
    "timer_section": {"title": "Timer", "timer": {"elapsed_ms": 120000, "running": True}},
    "image_section": {"icon_src": "start", "title": "Status", "subtitle": "Online"},
    "action_section": {"actions": [{"id": "a1", "label": "OK", "tone": "primary", "enabled": True}, {"id": "a2", "label": "Cancel", "tone": "danger", "enabled": True}]},
    "progress_section": {"title": "Progress", "progress": 45, "progress_text": "45% complete"},
    "text_section": {"title": "Notice", "body": "System operating normally. No issues detected in the last 24 hours."},
    "overlay_section": {"title": "Alert", "body": "New notification received", "tone": "warning", "unread_count": 1, "auto_hide_ms": 5000, "visible": True},
    "list_section": {"items": [{"id": "l1", "title": "Event A", "subtitle": "recent", "tone": "primary"}, {"id": "l2", "title": "Event B", "subtitle": "earlier", "tone": "warning"}]},
    "toggle_section": {"options": [{"id": "t1", "label": "Option A", "active": True}, {"id": "t2", "label": "Option B", "active": False}]},
    "nav_section": {"tabs": [{"id": "n1", "label": "Tab 1"}, {"id": "n2", "label": "Tab 2"}, {"id": "n3", "label": "Tab 3"}], "active_tab": 0},
}


def _build_all_sections_scene(supported: set[str], filter_types: list[str] | None = None) -> list[dict[str, Any]]:
    """Build sections array from supported types with fallback data."""
    out: list[dict[str, Any]] = []
    # use consistent order
    ordered = [t for t in ALL_SECTION_TYPES if t in supported]
    if filter_types:
        ordered = [t for t in ordered if t in filter_types]
    for idx, type_ in enumerate(ordered):
        data = copy.deepcopy(_FALLBACK_SECTION_DATA.get(type_, {}))
        sec_id = type_.replace("_section", f"_{idx}")
        out.append({"type": type_, "section_id": sec_id, "data": data})
    return out


# ---------------------------------------------------------------------------
# help
# ---------------------------------------------------------------------------


def print_help() -> None:
    print(
        """
Section Test Server Commands
============================
  help | ?                        — show this help
  devices                         — list connected terminals
  caps [target|all]               — show terminal section capabilities
  presets [target|all]            — list all presets with compatibility

  send <preset> [target|all]      — send a preset scene to terminal(s)
  send_all [target|all]           — send all supported sections in one scene
  select <t1,t2,...> [target]     — send custom scene with selected types

  auto <preset> [interval] [tgt]  — start auto-update simulation
  auto stop [target|all]          — stop auto-update

  quit | exit                     — exit server

Examples:
  send full_dashboard             — push the full dashboard preset
  send all                        — push all supported sections
  select hero_section,metric_section,chart_section
  auto full_dashboard 3           — auto-update full_dashboard every 3s
  auto stop                       — stop all auto-updates

Available presets (use 'presets' for full list):
  Single-type:  hero_dashboard, metrics_grid, chart_trend, timer_display,
                image_card, action_buttons, progress_bar, text_block,
                overlay_notify, list_items, toggle_switches, nav_tabs
  Multi-type:   full_dashboard, system_overview, alert_center,
                settings_panel, monitoring_panel, content_page,
                all_sections, stress_test
""".strip()
        + "\n"
    )


# ---------------------------------------------------------------------------
# main
# ---------------------------------------------------------------------------


def main() -> None:
    parser = argparse.ArgumentParser(description="SDUI Section protocol test WebSocket server")
    parser.add_argument("--host", default="0.0.0.0")
    parser.add_argument("--port", type=int, default=8080)
    args = parser.parse_args()
    asyncio.run(SectionTestServer(args.host, args.port).start())


if __name__ == "__main__":
    main()
