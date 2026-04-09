import asyncio
import copy
import json
import logging
import math
import os
import random
import time
from pathlib import Path

import websockets

# Script summary:
# - Purpose: UI2 MVP interaction lab script.
# - Scene: theme/scene/profile switching + overlay + viewport hydration logic.
# - Validation focus:
#   1) state transitions from click actions
#   2) per-page hydration and update revisions
#   3) reconnect recovery path for long-running sessions


logging.basicConfig(
    level=logging.INFO,
    format="%(asctime)s [%(levelname)s] %(message)s",
)
logging.getLogger("websockets").setLevel(logging.WARNING)

START_TIME = time.time()
devices = {}
PAGE_IDS = ["page_music", "page_dial", "page_settings"]


def now_str():
    return time.strftime("%H:%M:%S")


def make_state(websocket, remote):
    return {
        "ws": websocket,
        "addr": str(remote),
        "initialized": False,
        "theme": "ice",  # "ice" | "sunset"
        "scene": "aurora",  # "aurora" | "grid" | "orbit"
        "motion_profile": "steady",  # "steady" | "lively"
        "layout_variant": "compact",  # "compact" | "immersive"
        "visual_preset": "focus",
        "effects_level": "lite",  # "lite" | "balanced" | "rich"
        "current_page": "page_dial",
        "initial_page": 1,
        "overlay_visible": False,
        "overlay_phase": "hidden",
        "overlay_task": None,
        "debug_enabled": False,
        "hydrated_pages": set(),
        "pending_updates": {},
        "dial_value": 48,
        "phase": 0.0,
        "pump_task": None,
        "smoke_task": None,
        "visual_switch_last_ms": 0,
        "visual_switch_lock": False,
        "last_action": "ready",
        "update_revisions": {},
    }


def get_or_create_device(device_id, websocket, remote):
    if device_id not in devices:
        devices[device_id] = make_state(websocket, remote)
    else:
        state = devices[device_id]
        prev_ws = state.get("ws")
        state["ws"] = websocket
        state["addr"] = str(remote)
        # Reconnect recovery: ensure first upstream frame rebuilds UI for the new session.
        if prev_ws is not websocket:
            logging.info("[%s] reconnect detected, reset init state", device_id)
            state["initialized"] = False
            state["hydrated_pages"].clear()
            state["pending_updates"].clear()
    return devices[device_id]


async def send_topic(ws, topic, payload):
    if ws:
        await ws.send(json.dumps({"topic": topic, "payload": payload}, ensure_ascii=False))


def infer_page_for_update(widget_id):
    if not widget_id:
        return None
    if widget_id.startswith("music_"):
        return "page_music"
    if widget_id.startswith("dial_") or widget_id.startswith("btn_"):
        return "page_dial"
    if widget_id.startswith("settings_"):
        return "page_settings"
    return None


def queue_update(state, page_id, payload):
    q = state["pending_updates"].setdefault(page_id, [])
    wid = payload.get("id")
    if wid:
        for i in range(len(q) - 1, -1, -1):
            if q[i].get("id") == wid:
                q[i] = payload
                return
    q.append(payload)


async def send_ui_update(state, payload, *, page_hint=None, force=False):
    ws = state.get("ws")
    if not ws:
        return
    if force:
        await send_topic(ws, "ui/update", payload)
        return

    page_id = page_hint or infer_page_for_update(payload.get("id"))
    if page_id and page_id not in state["hydrated_pages"]:
        queue_update(state, page_id, payload)
        return

    await send_topic(ws, "ui/update", payload)


async def flush_page_updates(state, page_id):
    ws = state.get("ws")
    if not ws:
        return
    updates = state["pending_updates"].pop(page_id, [])
    for upd in updates:
        await send_topic(ws, "ui/update", upd)


def next_update_revision(state, page_id):
    revs = state.setdefault("update_revisions", {})
    revs[page_id] = int(revs.get(page_id, 0)) + 1
    return revs[page_id]


async def send_ui_ops_update(state, page_id, ops, *, transaction=True):
    ws = state.get("ws")
    if not ws:
        return
    payload = {
        "page_id": page_id,
        "revision": next_update_revision(state, page_id),
        "transaction": bool(transaction),
        "ops": ops,
    }
    await send_topic(ws, "ui/update", payload)


def build_values(phase, n=24, lo=6, hi=95, freq=1.0, jitter=4):
    vals = []
    amp = (hi - lo) * 0.5
    center = lo + amp
    for i in range(n):
        v = center + amp * math.sin(phase * freq + i * 0.38) + random.uniform(-jitter, jitter)
        v = max(lo, min(hi, int(v)))
        vals.append(v)
    return vals


LAYOUT_TOKENS = {
    "safe_pad": 16,
    "page": {
        "bg_color": "#0f1a25",
        "bg_opa": 92,
        "border_w": 0,
        "border_color": "#4FC4E8FF",
        "shadow_w": 0,
        "shadow_color": "#000000",
        "radius": 30,
    },
    "typography": {
        "heading_size": 16,
        "title_size": 16,
        "sub_size": 14,
    },
    "anchors": {
        "heading_y": 10,
        "title_y": -24,
        "sub_y": 4,
        "dial_value_y": -10,
        "dial_btn_row_y": -52,
        "settings_status_y": 78,
        "overlay_status_y": 72,
    },
    "widgets": {
        "particle_canvas": 420,
        "particle_count": 12,
        "particle_duration": 52,
        "spectrum_w": 248,
        "spectrum_h": 76,
        "wave_w": 248,
        "wave_h": 88,
    },
    "dial_buttons": {
        "row_w": 304,
        "row_h": 44,
        "btn_w": 68,
        "btn_h": 40,
        "radius": 12,
    },
    "page_card": {
        "w": "92%",
        "h": "88%",
        "pad": 14,
        "radius": 30,
    },
}

Z_LAYERS = {
    "scene": 0,
    "viewport": 10,
    "overlay": 90,
}

THEME_PRESETS = {
    "ice": {
        "root_bg": "#04070d",
        "page_bg": "#0f1a25",
        "page_border": "#4FC4E8FF",
        "heading_text": "#dff2ff",
        "title_text": "#d9e8f7",
        "sub_text": "#7f96aa",
        "scene_particle_color": "#6fb7e8",
        "wave_color": "#39d5ff",
        "spectrum_color": "#8affc1",
        "dial_ring_fg": "#35d0ff",
        "dial_ring_bg": "#1c2a36",
        "overlay_bg": "#000000",
        "overlay_status": "#e8f6ff",
        "glow_a": "#2f8cff",
        "glow_b": "#5ef0ff",
    },
    "sunset": {
        "root_bg": "#130b08",
        "page_bg": "#24170f",
        "page_border": "#4FD8A879",
        "heading_text": "#fff2db",
        "title_text": "#ffe1c4",
        "sub_text": "#b08d73",
        "scene_particle_color": "#ffb073",
        "wave_color": "#ffbf57",
        "spectrum_color": "#ffd971",
        "dial_ring_fg": "#ff9f5e",
        "dial_ring_bg": "#3a261b",
        "overlay_bg": "#140d09",
        "overlay_status": "#fff3df",
        "glow_a": "#ff8d4f",
        "glow_b": "#ffd37a",
    },
    "forest": {
        "root_bg": "#060c09",
        "page_bg": "#14221a",
        "page_border": "#61d49a66",
        "heading_text": "#ddffe9",
        "title_text": "#c9f0d8",
        "sub_text": "#88ac97",
        "scene_particle_color": "#73dca8",
        "wave_color": "#5fe39d",
        "spectrum_color": "#98ffbf",
        "dial_ring_fg": "#66df9f",
        "dial_ring_bg": "#1d3a2b",
        "overlay_bg": "#08110c",
        "overlay_status": "#ddffe9",
        "glow_a": "#3aba78",
        "glow_b": "#8cf6be",
    },
    "mono": {
        "root_bg": "#0b0b0c",
        "page_bg": "#181a1d",
        "page_border": "#aeb4bd40",
        "heading_text": "#eef1f4",
        "title_text": "#e1e6eb",
        "sub_text": "#9ca4ae",
        "scene_particle_color": "#d6dde6",
        "wave_color": "#b8c1cd",
        "spectrum_color": "#d8e0ea",
        "dial_ring_fg": "#d4dbe5",
        "dial_ring_bg": "#313843",
        "overlay_bg": "#0f1113",
        "overlay_status": "#eef1f4",
        "glow_a": "#a7b1bf",
        "glow_b": "#d5dde7",
    },
    "retro_amber": {
        "root_bg": "#120b02",
        "page_bg": "#2a1b07",
        "page_border": "#ffb24f6e",
        "heading_text": "#ffe8b8",
        "title_text": "#ffd796",
        "sub_text": "#b8945f",
        "scene_particle_color": "#ffbb55",
        "wave_color": "#ffb347",
        "spectrum_color": "#ffd16f",
        "dial_ring_fg": "#ffb84a",
        "dial_ring_bg": "#4a2e0f",
        "overlay_bg": "#1a1105",
        "overlay_status": "#ffe8b8",
        "glow_a": "#ff9e3a",
        "glow_b": "#ffd27d",
    },
    "corporate_blue": {
        "root_bg": "#06111f",
        "page_bg": "#13263a",
        "page_border": "#5fb2ff70",
        "heading_text": "#e8f3ff",
        "title_text": "#d8e9ff",
        "sub_text": "#8ca5bf",
        "scene_particle_color": "#78bfff",
        "wave_color": "#54b7ff",
        "spectrum_color": "#8fd6ff",
        "dial_ring_fg": "#5cbcff",
        "dial_ring_bg": "#22384f",
        "overlay_bg": "#081626",
        "overlay_status": "#e8f3ff",
        "glow_a": "#3f8de2",
        "glow_b": "#7fd1ff",
    },
    "clean_light": {
        "root_bg": "#e9eef5",
        "page_bg": "#f8fbff",
        "page_border": "#5a7a9a44",
        "heading_text": "#1d3249",
        "title_text": "#20374f",
        "sub_text": "#57718b",
        "scene_particle_color": "#8ca8c4",
        "wave_color": "#3f6f9e",
        "spectrum_color": "#5d88b0",
        "dial_ring_fg": "#4575a4",
        "dial_ring_bg": "#c6d7e8",
        "overlay_bg": "#dce7f2",
        "overlay_status": "#183047",
        "glow_a": "#9eb6ce",
        "glow_b": "#c0d4e6",
    },
}

THEME_ORDER = ["ice", "sunset", "forest", "mono", "retro_amber", "corporate_blue", "clean_light"]
SCENE_ORDER = ["aurora", "grid", "orbit"]
MOTION_ORDER = ["steady", "lively"]
LAYOUT_ORDER = ["compact", "immersive"]
VISUAL_PRESET_ORDER = ["focus", "nightlab", "forest_hud", "amber_retro", "boardroom", "clear_day"]

EFFECT_LEVELS = {
    "lite": {
        "particle_count": 0,
        "particle_duration": 40,
        "update_tick_s": 0.9,
        "value_jitter": 2,
        "overlay_bg_opa": 78,
        "glow_opa": 26,
        "scene_step": 2,
        "music_step": 2,
        "overlay_step": 2,
        "status_step": 3,
        "spectrum_points": 14,
        "wave_points": 12,
    },
    "balanced": {
        "particle_count": 0,
        "particle_duration": 52,
        "update_tick_s": 0.7,
        "value_jitter": 4,
        "overlay_bg_opa": 92,
        "glow_opa": 32,
        "scene_step": 1,
        "music_step": 1,
        "overlay_step": 1,
        "status_step": 2,
        "spectrum_points": 18,
        "wave_points": 14,
    },
    "rich": {
        "particle_count": 0,
        "particle_duration": 64,
        "update_tick_s": 0.5,
        "value_jitter": 6,
        "overlay_bg_opa": 108,
        "glow_opa": 42,
        "scene_step": 1,
        "music_step": 1,
        "overlay_step": 1,
        "status_step": 2,
        "spectrum_points": 20,
        "wave_points": 16,
    },
}

MOTION_SPEC = {
    "dial": {"curve": "spring", "duration_ms": 220},
    "overlay_enter": {"curve": "linear", "duration_ms": 180},
    "overlay_exit": {"curve": "linear", "duration_ms": 120},
    "live_update": {"curve": "linear", "duration_ms": 120},
}

MOTION_PROFILES = {
    "steady": {
        "overlay_enter_ms": 220,
        "overlay_exit_ms": 150,
        "dial_curve": "ease_out",
    },
    "lively": {
        "overlay_enter_ms": 140,
        "overlay_exit_ms": 90,
        "dial_curve": "spring",
    },
}

LAYOUT_VARIANTS = {
    "compact": {
        "page_card_w": "92%",
        "page_card_h": "88%",
        "page_card_pad": 14,
        "dial_row_y": -52,
    },
    "immersive": {
        "page_card_w": "96%",
        "page_card_h": "92%",
        "page_card_pad": 10,
        "dial_row_y": -44,
    },
}

VISUAL_PRESETS = {
    "focus": {
        "theme": "ice",
        "scene": "aurora",
        "motion_profile": "steady",
        "layout_variant": "compact",
        "effects_level": "lite",
    },
    "nightlab": {
        "theme": "mono",
        "scene": "grid",
        "motion_profile": "lively",
        "layout_variant": "immersive",
        "effects_level": "balanced",
    },
    "forest_hud": {
        "theme": "forest",
        "scene": "orbit",
        "motion_profile": "steady",
        "layout_variant": "compact",
        "effects_level": "balanced",
    },
    "amber_retro": {
        "theme": "retro_amber",
        "scene": "grid",
        "motion_profile": "lively",
        "layout_variant": "immersive",
        "effects_level": "rich",
    },
    "boardroom": {
        "theme": "corporate_blue",
        "scene": "grid",
        "motion_profile": "steady",
        "layout_variant": "compact",
        "effects_level": "lite",
    },
    "clear_day": {
        "theme": "clean_light",
        "scene": "aurora",
        "motion_profile": "steady",
        "layout_variant": "immersive",
        "effects_level": "lite",
    },
}

_DEFAULT_VISUAL_CFG = Path(__file__).resolve().parents[2] / "configs" / "ui" / "ui2_visual_tokens.json"
_DEFAULT_VISUAL_LOCK = Path(__file__).resolve().parents[2] / "configs" / "ui" / "ui2_visual_tokens.locked.json"
_REPO_ROOT = Path(__file__).resolve().parents[3]
_VISUAL_CFG_CANDIDATES = [
    _REPO_ROOT / "docs" / "configs" / "ui" / "ui2_visual_tokens.json",
    _REPO_ROOT / "configs" / "ui" / "ui2_visual_tokens.json",
    _DEFAULT_VISUAL_CFG,
]
_VISUAL_LOCK_CANDIDATES = [
    _REPO_ROOT / "docs" / "configs" / "ui" / "ui2_visual_tokens.locked.json",
    _REPO_ROOT / "configs" / "ui" / "ui2_visual_tokens.locked.json",
    _DEFAULT_VISUAL_LOCK,
]
_VISUAL_CONFIG_ENV = os.environ.get("UI2_VISUAL_CONFIG_PATH", "").strip()
_LOCKED_CONFIG_ENV = os.environ.get("UI2_LOCKED_CONFIG_PATH", "").strip()
VISUAL_CONFIG_PATH = (
    Path(_VISUAL_CONFIG_ENV)
    if _VISUAL_CONFIG_ENV
    else next((p for p in _VISUAL_CFG_CANDIDATES if p.exists()), _VISUAL_CFG_CANDIDATES[0])
)
LOCKED_CONFIG_PATH = (
    Path(_LOCKED_CONFIG_ENV)
    if _LOCKED_CONFIG_ENV
    else next((p for p in _VISUAL_LOCK_CANDIDATES if p.exists()), _VISUAL_LOCK_CANDIDATES[0])
)


def page_base(page_id, pad, palette):
    return {
        "type": "container",
        "id": page_id,
        "bg_opa": 0,
        "border_w": 0,
        "shadow_w": 0,
        "radius": 0,
        "pad": pad,
    }


def wrap_page_card(page, palette, children, layout):
    card = {
        "type": "container",
        "id": f"{page['id']}_card",
        "w": layout["page_card_w"],
        "h": layout["page_card_h"],
        "align": "center",
        # Pure layout wrapper: avoid center rectangle artifacts after FX toggles.
        "bg_opa": 0,
        "border_w": 0,
        "shadow_w": 0,
        "radius": LAYOUT_TOKENS["page_card"]["radius"],
        "pad": layout["page_card_pad"],
        "children": children,
    }
    page["children"] = [card]
    return page


def heading_label(widget_id, text, palette):
    return {
        "type": "label",
        "id": widget_id,
        "text": text,
        "align": "top_mid",
        "y": LAYOUT_TOKENS["anchors"]["heading_y"],
        "text_color": palette["heading_text"],
        "font_size": LAYOUT_TOKENS["typography"]["heading_size"],
        "pointer_events": "none",
    }


def build_scene_widget(palette, fx):
    if fx["particle_count"] <= 0:
        return None
    w = {
        "type": "widget",
        "widget_type": "particle_field",
        "id": "scene_particle",
        "w": "full",
        "h": "full",
        "canvas_w": LAYOUT_TOKENS["widgets"]["particle_canvas"],
        "canvas_h": LAYOUT_TOKENS["widgets"]["particle_canvas"],
        "count": fx["particle_count"],
        "color": palette["scene_particle_color"],
        "particle_size": 2,
        "duration": fx["particle_duration"],
        "pointer_events": "none",
    }
    return w


def build_scene_glow_layers(palette, fx):
    glow_opa = fx.get("glow_opa", 28)
    return [
        {
            "type": "container",
            "id": "scene_glow_left",
            "w": 220,
            "h": 220,
            "align": "center",
            "x": -86,
            "y": -118,
            "radius": 999,
            "bg_color": palette["glow_a"],
            "bg_opa": glow_opa,
            "shadow_w": 18,
            "shadow_color": palette["glow_a"],
            "border_w": 0,
            "pointer_events": "none",
        },
        {
            "type": "container",
            "id": "scene_glow_right",
            "w": 180,
            "h": 180,
            "align": "center",
            "x": 94,
            "y": 120,
            "radius": 999,
            "bg_color": palette["glow_b"],
            "bg_opa": glow_opa - 6 if glow_opa > 8 else glow_opa,
            "shadow_w": 14,
            "shadow_color": palette["glow_b"],
            "border_w": 0,
            "pointer_events": "none",
        },
    ]


def build_scene_grid_layers(palette, fx):
    line_opa = max(6, fx.get("glow_opa", 24) // 2)
    return [
        {
            "type": "container",
            "id": "scene_grid_h1",
            "w": "full",
            "h": 1,
            "align": "center",
            "y": -120,
            "bg_color": palette["glow_a"],
            "bg_opa": line_opa,
            "pointer_events": "none",
        },
        {
            "type": "container",
            "id": "scene_grid_h2",
            "w": "full",
            "h": 1,
            "align": "center",
            "y": -60,
            "bg_color": palette["glow_a"],
            "bg_opa": line_opa,
            "pointer_events": "none",
        },
        {
            "type": "container",
            "id": "scene_grid_h3",
            "w": "full",
            "h": 1,
            "align": "center",
            "y": 0,
            "bg_color": palette["glow_b"],
            "bg_opa": line_opa + 2,
            "pointer_events": "none",
        },
        {
            "type": "container",
            "id": "scene_grid_h4",
            "w": "full",
            "h": 1,
            "align": "center",
            "y": 60,
            "bg_color": palette["glow_a"],
            "bg_opa": line_opa,
            "pointer_events": "none",
        },
        {
            "type": "container",
            "id": "scene_grid_h5",
            "w": "full",
            "h": 1,
            "align": "center",
            "y": 120,
            "bg_color": palette["glow_a"],
            "bg_opa": line_opa,
            "pointer_events": "none",
        },
        {
            "type": "container",
            "id": "scene_grid_v1",
            "w": 1,
            "h": "full",
            "align": "center",
            "x": -120,
            "bg_color": palette["glow_a"],
            "bg_opa": line_opa,
            "pointer_events": "none",
        },
        {
            "type": "container",
            "id": "scene_grid_v2",
            "w": 1,
            "h": "full",
            "align": "center",
            "x": -60,
            "bg_color": palette["glow_a"],
            "bg_opa": line_opa,
            "pointer_events": "none",
        },
        {
            "type": "container",
            "id": "scene_grid_v3",
            "w": 1,
            "h": "full",
            "align": "center",
            "x": 0,
            "bg_color": palette["glow_b"],
            "bg_opa": line_opa + 2,
            "pointer_events": "none",
        },
        {
            "type": "container",
            "id": "scene_grid_v4",
            "w": 1,
            "h": "full",
            "align": "center",
            "x": 60,
            "bg_color": palette["glow_a"],
            "bg_opa": line_opa,
            "pointer_events": "none",
        },
        {
            "type": "container",
            "id": "scene_grid_v5",
            "w": 1,
            "h": "full",
            "align": "center",
            "x": 120,
            "bg_color": palette["glow_a"],
            "bg_opa": line_opa,
            "pointer_events": "none",
        },
    ]


def build_scene_orbit_layers(palette, fx):
    glow_opa = fx.get("glow_opa", 28)
    return [
        {
            "type": "container",
            "id": "scene_orbit_outer",
            "w": 360,
            "h": 360,
            "align": "center",
            "radius": 999,
            "bg_opa": 0,
            "border_w": 1,
            "border_color": palette["glow_a"],
            "border_opa": max(8, glow_opa - 10),
            "pointer_events": "none",
        },
        {
            "type": "container",
            "id": "scene_orbit_inner",
            "w": 250,
            "h": 250,
            "align": "center",
            "radius": 999,
            "bg_opa": 0,
            "border_w": 1,
            "border_color": palette["glow_b"],
            "border_opa": max(10, glow_opa - 8),
            "pointer_events": "none",
        },
        {
            "type": "container",
            "id": "scene_orbit_dot_a",
            "w": 14,
            "h": 14,
            "align": "center",
            "x": 126,
            "y": -64,
            "radius": 999,
            "bg_color": palette["glow_b"],
            "bg_opa": max(24, glow_opa + 8),
            "pointer_events": "none",
        },
        {
            "type": "container",
            "id": "scene_orbit_dot_b",
            "w": 10,
            "h": 10,
            "align": "center",
            "x": -148,
            "y": 82,
            "radius": 999,
            "bg_color": palette["glow_a"],
            "bg_opa": max(20, glow_opa + 4),
            "pointer_events": "none",
        },
    ]


def build_scene_groups(palette, fx, scene):
    aurora_opa = 255 if scene == "aurora" else 0
    grid_opa = 255 if scene == "grid" else 0
    orbit_opa = 255 if scene == "orbit" else 0

    groups = [
        {
            "type": "container",
            "id": "scene_layer_aurora",
            "w": "full",
            "h": "full",
            "align": "center",
            "bg_opa": 0,
            "border_w": 0,
            "opa": aurora_opa,
            "pointer_events": "none",
            "children": build_scene_glow_layers(palette, fx),
        },
        {
            "type": "container",
            "id": "scene_layer_grid",
            "w": "full",
            "h": "full",
            "align": "center",
            "bg_opa": 0,
            "border_w": 0,
            "opa": grid_opa,
            "pointer_events": "none",
            "children": build_scene_grid_layers(palette, fx),
        },
        {
            "type": "container",
            "id": "scene_layer_orbit",
            "w": "full",
            "h": "full",
            "align": "center",
            "bg_opa": 0,
            "border_w": 0,
            "opa": orbit_opa,
            "pointer_events": "none",
            "children": build_scene_orbit_layers(palette, fx),
        },
    ]

    scene_widget = build_scene_widget(palette, fx)
    if scene_widget:
        groups.append(scene_widget)
    return groups


def build_wave_widget(palette):
    w = {
        "type": "widget",
        "widget_type": "waveform",
        "id": "voice_wave",
        "align": "center",
        "canvas_w": LAYOUT_TOKENS["widgets"]["wave_w"],
        "canvas_h": LAYOUT_TOKENS["widgets"]["wave_h"],
        "color": palette["wave_color"],
        "values": [8, 22, 38, 28, 12, 42, 30, 14, 34, 18],
    }
    return w


def build_spectrum_widget(palette):
    w = {
        "type": "widget",
        "widget_type": "spectrum",
        "id": "music_spectrum",
        "align": "center",
        "y": 58,
        "canvas_w": LAYOUT_TOKENS["widgets"]["spectrum_w"],
        "canvas_h": LAYOUT_TOKENS["widgets"]["spectrum_h"],
        "color": palette["spectrum_color"],
        "values": [16, 28, 38, 30, 22, 50, 36, 24, 30, 18],
    }
    return w


def cycle_option(current, order):
    if not order:
        return current
    if current not in order:
        return order[0]
    idx = order.index(current)
    return order[(idx + 1) % len(order)]


def get_motion_profile(state):
    return MOTION_PROFILES.get(state.get("motion_profile", "steady"), MOTION_PROFILES["steady"])


def get_layout_variant(state):
    return LAYOUT_VARIANTS.get(state.get("layout_variant", "compact"), LAYOUT_VARIANTS["compact"])


def apply_visual_preset(state, preset_name):
    preset = VISUAL_PRESETS.get(preset_name)
    if not preset:
        return
    state["theme"] = preset["theme"]
    state["scene"] = preset["scene"]
    state["motion_profile"] = preset["motion_profile"]
    state["layout_variant"] = preset["layout_variant"]
    state["effects_level"] = preset["effects_level"]
    state["visual_preset"] = preset_name


async def run_visual_switch(state, op, cooldown_ms=180):
    now_ms = int(time.time() * 1000)
    last_ms = int(state.get("visual_switch_last_ms", 0))
    if state.get("visual_switch_lock"):
        return False
    if now_ms - last_ms < cooldown_ms:
        return False

    state["visual_switch_last_ms"] = now_ms
    state["visual_switch_lock"] = True
    try:
        await op()
    finally:
        state["visual_switch_lock"] = False
    return True


def load_visual_config(path=VISUAL_CONFIG_PATH):
    global LAYOUT_TOKENS
    global THEME_PRESETS
    global THEME_ORDER
    global SCENE_ORDER
    global MOTION_PROFILES
    global MOTION_ORDER
    global LAYOUT_VARIANTS
    global LAYOUT_ORDER
    global VISUAL_PRESETS
    global VISUAL_PRESET_ORDER
    global EFFECT_LEVELS

    if not path.exists():
        logging.info("visual config not found, using built-in defaults: %s", path)
        return

    try:
        with path.open("r", encoding="utf-8") as f:
            cfg = json.load(f)
    except Exception as exc:
        logging.warning("failed to load visual config (%s), using defaults: %r", path, exc)
        return

    def _map(name, default):
        value = cfg.get(name)
        if isinstance(value, dict) and value:
            return value
        return default

    def _order(name, default):
        value = cfg.get(name)
        if isinstance(value, list):
            out = [x for x in value if isinstance(x, str) and x]
            if out:
                return out
        return default

    LAYOUT_TOKENS = _map("layout_tokens", LAYOUT_TOKENS)
    THEME_PRESETS = _map("themes", THEME_PRESETS)
    THEME_ORDER = _order("theme_order", THEME_ORDER)
    SCENE_ORDER = _order("scene_order", SCENE_ORDER)
    MOTION_PROFILES = _map("motion_profiles", MOTION_PROFILES)
    MOTION_ORDER = _order("motion_order", MOTION_ORDER)
    LAYOUT_VARIANTS = _map("layout_variants", LAYOUT_VARIANTS)
    LAYOUT_ORDER = _order("layout_order", LAYOUT_ORDER)
    VISUAL_PRESETS = _map("visual_presets", VISUAL_PRESETS)
    VISUAL_PRESET_ORDER = _order("visual_preset_order", VISUAL_PRESET_ORDER)
    EFFECT_LEVELS = _map("effect_levels", EFFECT_LEVELS)

    if not THEME_ORDER:
        THEME_ORDER = list(THEME_PRESETS.keys())
    if not VISUAL_PRESET_ORDER:
        VISUAL_PRESET_ORDER = list(VISUAL_PRESETS.keys())

    logging.info(
        "visual config loaded: themes=%d scenes=%d motions=%d layouts=%d presets=%d",
        len(THEME_PRESETS),
        len(SCENE_ORDER),
        len(MOTION_PROFILES),
        len(LAYOUT_VARIANTS),
        len(VISUAL_PRESETS),
    )


def normalize_visual_state(state):
    if not state:
        return
    if state.get("theme") not in THEME_PRESETS:
        state["theme"] = THEME_ORDER[0] if THEME_ORDER else next(iter(THEME_PRESETS.keys()))
    if state.get("scene") not in SCENE_ORDER:
        state["scene"] = SCENE_ORDER[0] if SCENE_ORDER else "aurora"
    if state.get("motion_profile") not in MOTION_PROFILES:
        state["motion_profile"] = MOTION_ORDER[0] if MOTION_ORDER else next(iter(MOTION_PROFILES.keys()))
    if state.get("layout_variant") not in LAYOUT_VARIANTS:
        state["layout_variant"] = LAYOUT_ORDER[0] if LAYOUT_ORDER else next(iter(LAYOUT_VARIANTS.keys()))
    if state.get("effects_level") not in EFFECT_LEVELS:
        state["effects_level"] = "balanced" if "balanced" in EFFECT_LEVELS else next(iter(EFFECT_LEVELS.keys()))
    if state.get("visual_preset") not in VISUAL_PRESETS and state.get("visual_preset") != "custom":
        state["visual_preset"] = VISUAL_PRESET_ORDER[0] if VISUAL_PRESET_ORDER else "custom"


def build_locked_visual_config(state, preset_name="locked_live"):
    theme_name = state.get("theme")
    scene_name = state.get("scene")
    motion_name = state.get("motion_profile")
    layout_name = state.get("layout_variant")
    effects_name = state.get("effects_level")

    if theme_name not in THEME_PRESETS:
        theme_name = THEME_ORDER[0] if THEME_ORDER else next(iter(THEME_PRESETS.keys()))
    if scene_name not in SCENE_ORDER:
        scene_name = SCENE_ORDER[0] if SCENE_ORDER else "aurora"
    if motion_name not in MOTION_PROFILES:
        motion_name = MOTION_ORDER[0] if MOTION_ORDER else next(iter(MOTION_PROFILES.keys()))
    if layout_name not in LAYOUT_VARIANTS:
        layout_name = LAYOUT_ORDER[0] if LAYOUT_ORDER else next(iter(LAYOUT_VARIANTS.keys()))
    if effects_name not in EFFECT_LEVELS:
        effects_name = "balanced" if "balanced" in EFFECT_LEVELS else next(iter(EFFECT_LEVELS.keys()))

    effect_cfg = copy.deepcopy(EFFECT_LEVELS[effects_name])
    locked = {
        "layout_tokens": copy.deepcopy(LAYOUT_TOKENS),
        "theme_order": [theme_name],
        "themes": {theme_name: copy.deepcopy(THEME_PRESETS[theme_name])},
        "scene_order": [scene_name],
        "motion_order": [motion_name],
        "motion_profiles": {motion_name: copy.deepcopy(MOTION_PROFILES[motion_name])},
        "layout_order": [layout_name],
        "layout_variants": {layout_name: copy.deepcopy(LAYOUT_VARIANTS[layout_name])},
        "visual_preset_order": [preset_name],
        "visual_presets": {
            preset_name: {
                "theme": theme_name,
                "scene": scene_name,
                "motion_profile": motion_name,
                "layout_variant": layout_name,
                "effects_level": "balanced",
            }
        },
        "effect_levels": {
            "lite": copy.deepcopy(effect_cfg),
            "balanced": copy.deepcopy(effect_cfg),
            "rich": copy.deepcopy(effect_cfg),
        },
        "frozen_profile": {
            "name": preset_name,
            "source": "ui2_mvp_test_server",
            "locked_at": time.strftime("%Y-%m-%d %H:%M:%S"),
            "note": "Frozen from live terminal interactions (swipe/buttons).",
        },
    }
    return locked


def save_locked_visual_config(state, output_path=LOCKED_CONFIG_PATH):
    output_path.parent.mkdir(parents=True, exist_ok=True)
    locked = build_locked_visual_config(state)
    with output_path.open("w", encoding="utf-8") as f:
        json.dump(locked, f, ensure_ascii=False, indent=2)
        f.write("\n")
    return output_path


def build_music_page(palette, layout):
    page = page_base("page_music", pad=16, palette=palette)
    content = [
        heading_label("music_heading", "Music [v2]", palette),
        {
            "type": "label",
            "id": "music_title",
            "text": "Music Skeleton",
            "align": "center",
            "y": LAYOUT_TOKENS["anchors"]["title_y"],
            "text_color": palette["title_text"],
            "font_size": LAYOUT_TOKENS["typography"]["title_size"],
        },
        {
            "type": "label",
            "id": "music_sub",
            "text": "Waiting hydration...",
            "align": "center",
            "y": LAYOUT_TOKENS["anchors"]["sub_y"],
            "text_color": palette["sub_text"],
            "font_size": LAYOUT_TOKENS["typography"]["sub_size"],
        },
        build_spectrum_widget(palette),
    ]
    return wrap_page_card(page, palette, content, layout)


def build_dial_page(dial_value, palette, layout):
    bt = LAYOUT_TOKENS["dial_buttons"]
    page = page_base("page_dial", pad=10, palette=palette)
    content = [
        heading_label("dial_heading", "Dial [v2]", palette),
        {
            "type": "widget",
            "widget_type": "dial",
            "id": "dial_main",
            "w": 260,
            "h": 260,
            "align": "center",
            "min": 0,
            "max": 100,
            "value": dial_value,
            "inertia": True,
            "inertia_friction": 0.93,
            "inertia_min_velocity": 0.015,
            "throttle_ms": 120,
            "ring_fg": palette["dial_ring_fg"],
            "ring_bg": palette["dial_ring_bg"],
            "arc_width": 16,
            "value_format": "%ld",
            "on_change_final": "server://ui/dial_final",
            "bind": {"text_id": "dial_value_text"},
        },
        {
            "type": "label",
            "id": "dial_value_text",
            "text": f"{dial_value}",
            "align": "center",
            "y": LAYOUT_TOKENS["anchors"]["dial_value_y"],
            "z": 2,
            "text_color": "#e8f6ff",
            "font_size": 24,
            "pointer_events": "none",
        },
        {
            "type": "container",
            "id": "dial_btn_row",
            "w": bt["row_w"],
            "h": bt["row_h"],
            "align": "bottom_mid",
            "y": layout["dial_row_y"],
            "bg_opa": 0,
            "flex": "row",
            "justify": "space_between",
            "align_items": "center",
            "children": [
                {
                    "type": "button",
                    "id": "btn_overlay",
                    "text": "Voice",
                    "w": bt["btn_w"],
                    "h": bt["btn_h"],
                    "radius": bt["radius"],
                    "bg_color": "#2f8b6f",
                    "on_click": "server://ui/toggle_overlay",
                },
                {
                    "type": "button",
                    "id": "btn_debug",
                    "text": "Debug",
                    "w": bt["btn_w"],
                    "h": bt["btn_h"],
                    "radius": bt["radius"],
                    "bg_color": "#365f8a",
                    "on_click": "server://ui/toggle_debug",
                },
                {
                    "type": "button",
                    "id": "btn_smoke",
                    "text": "Smoke",
                    "w": bt["btn_w"],
                    "h": bt["btn_h"],
                    "radius": bt["radius"],
                    "bg_color": "#6f3a78",
                    "on_click": "server://ui/run_smoke",
                },
            ],
        },
    ]
    return wrap_page_card(page, palette, content, layout)


def build_settings_page(state, palette, layout):
    page = page_base("page_settings", pad=16, palette=palette)
    content = [
        heading_label("settings_heading", "Settings", palette),
        {
            "type": "label",
            "id": "settings_title",
            "text": "Settings Skeleton",
            "align": "center",
            "y": -20,
            "text_color": palette["title_text"],
            "font_size": LAYOUT_TOKENS["typography"]["title_size"],
        },
        {
            "type": "label",
            "id": "settings_sub",
            "text": "Waiting hydration...",
            "align": "center",
            "y": 8,
            "text_color": palette["sub_text"],
            "font_size": LAYOUT_TOKENS["typography"]["sub_size"],
        },
        {
            "type": "container",
            "id": "settings_controls",
            "w": 304,
            "h": 42,
            "align": "center",
            "y": 44,
            "bg_opa": 0,
            "flex": "row",
            "justify": "space_between",
            "align_items": "center",
            "children": [
                {"type": "button", "id": "btn_prev_page", "text": "Prev", "w": 68, "h": 38, "on_click": "server://ui/page_prev"},
                {"type": "button", "id": "btn_next_page", "text": "Next", "w": 68, "h": 38, "on_click": "server://ui/page_next"},
                {"type": "button", "id": "btn_debug2", "text": "Debug", "w": 68, "h": 38, "on_click": "server://ui/toggle_debug"},
                {"type": "button", "id": "btn_smoke2", "text": "Smoke", "w": 68, "h": 38, "on_click": "server://ui/run_smoke"},
            ],
        },
        {
            "type": "container",
            "id": "settings_visual_controls",
            "w": 304,
            "h": 42,
            "align": "center",
            "y": 88,
            "bg_opa": 0,
            "flex": "row",
            "justify": "space_between",
            "align_items": "center",
            "children": [
                {"type": "button", "id": "btn_theme", "text": "Theme", "w": 96, "h": 38, "on_click": "server://ui/switch_theme"},
                {"type": "button", "id": "btn_scene", "text": "Scene", "w": 96, "h": 38, "on_click": "server://ui/switch_scene"},
                {"type": "button", "id": "btn_fx", "text": "FX", "w": 96, "h": 38, "on_click": "server://ui/toggle_effects"},
            ],
        },
        {
            "type": "container",
            "id": "settings_motion_controls",
            "w": 304,
            "h": 42,
            "align": "center",
            "y": 132,
            "bg_opa": 0,
            "flex": "row",
            "justify": "space_between",
            "align_items": "center",
            "children": [
                {"type": "button", "id": "btn_motion", "text": "Motion", "w": 146, "h": 38, "on_click": "server://ui/switch_motion"},
                {"type": "button", "id": "btn_layout", "text": "Layout", "w": 146, "h": 38, "on_click": "server://ui/switch_layout"},
            ],
        },
        {
            "type": "container",
            "id": "settings_preset_controls",
            "w": 304,
            "h": 42,
            "align": "center",
            "y": 176,
            "bg_opa": 0,
            "flex": "row",
            "justify": "space_between",
            "align_items": "center",
            "children": [
                {"type": "button", "id": "btn_preset", "text": "Preset", "w": 96, "h": 38, "on_click": "server://ui/switch_preset"},
                {"type": "button", "id": "btn_reload_visual", "text": "Reload", "w": 96, "h": 38, "on_click": "server://ui/reload_visual_config"},
                {"type": "button", "id": "btn_freeze_visual", "text": "Freeze", "w": 96, "h": 38, "on_click": "server://ui/freeze_profile"},
            ],
        },
        {
            "type": "container",
            "id": "settings_ops_controls",
            "w": 304,
            "h": 42,
            "align": "center",
            "y": 220,
            "bg_opa": 0,
            "flex": "row",
            "justify": "space_between",
            "align_items": "center",
            "children": [
                {"type": "button", "id": "btn_ops_tx", "text": "OpsTx", "w": 146, "h": 38, "on_click": "server://ui/demo_ops_tx?source=settings"},
                {"type": "button", "id": "btn_ops_fail", "text": "OpsFail", "w": 146, "h": 38, "on_click": "server://ui/demo_ops_fail?source=settings"},
            ],
        },
        {
            "type": "label",
            "id": "settings_status",
            "text": "Scheduler / Overlay / Update contract",
            "align": "center",
            "y": 252,
            "text_color": "#98b0c4",
            "font_size": 12,
        },
    ]
    return wrap_page_card(page, palette, content, layout)


def build_overlay(state, palette, fx):
    return {
        "type": "overlay",
        "id": "voice_modal",
        "z": Z_LAYERS["overlay"],
        "hidden": not state["overlay_visible"],
        "children": [
            {
                "type": "container",
                "id": "voice_overlay_bg",
                "w": "full",
                "h": "full",
                "bg_color": palette["overlay_bg"],
                "bg_opa": fx["overlay_bg_opa"],
                "children": [
                    build_wave_widget(palette),
                    {
                        "type": "label",
                        "id": "voice_status",
                        "text": "Listening...",
                        "align": "center",
                        "y": LAYOUT_TOKENS["anchors"]["overlay_status_y"],
                        "text_color": palette["overlay_status"],
                        "font_size": 16,
                    },
                ],
            }
        ],
    }


def build_layout(state):
    palette = THEME_PRESETS.get(state["theme"])
    if palette is None:
        if THEME_ORDER and THEME_ORDER[0] in THEME_PRESETS:
            palette = THEME_PRESETS[THEME_ORDER[0]]
        elif THEME_PRESETS:
            palette = next(iter(THEME_PRESETS.values()))
        else:
            palette = {
                "root_bg": "#04070d",
                "heading_text": "#dff2ff",
                "title_text": "#d9e8f7",
                "sub_text": "#7f96aa",
                "wave_color": "#39d5ff",
                "spectrum_color": "#8affc1",
                "dial_ring_fg": "#35d0ff",
                "dial_ring_bg": "#1c2a36",
                "overlay_bg": "#000000",
                "overlay_status": "#e8f6ff",
                "glow_a": "#2f8cff",
                "glow_b": "#5ef0ff",
                "scene_particle_color": "#6fb7e8",
            }
    fx = EFFECT_LEVELS.get(state["effects_level"])
    if fx is None:
        fx = EFFECT_LEVELS.get("balanced") or next(iter(EFFECT_LEVELS.values()))
    scene = state.get("scene", "aurora")
    layout = get_layout_variant(state)

    scene_children = build_scene_groups(palette, fx, scene)

    return {
        "safe_pad": LAYOUT_TOKENS["safe_pad"],
        "w": "full",
        "h": "full",
        "bg_color": palette["root_bg"],
        "children": [
            {
                "type": "scene",
                "id": "global_scene",
                "z": Z_LAYERS["scene"],
                "children": scene_children,
            },
            {
                "type": "viewport",
                "id": "main_swiper",
                "z": Z_LAYERS["viewport"],
                "w": "full",
                "h": "full",
                "bg_color": "#000000",
                "bg_opa": 0,
                "initial_page": state["initial_page"],
                "direction": "horizontal",
                "pages": [
                    build_music_page(palette, layout),
                    build_dial_page(state["dial_value"], palette, layout),
                    build_settings_page(state, palette, layout),
                ],
            },
            build_overlay(state, palette, fx),
        ],
    }


async def hydrate_page(state, page_id):
    if page_id == "page_music":
        await send_ui_update(state, {"id": "music_title", "text": "Now Playing"}, page_hint="page_music")
        await send_ui_update(state, {"id": "music_sub", "text": f"{now_str()} hydrated"}, page_hint="page_music")
        fx = EFFECT_LEVELS.get(state["effects_level"])
        if fx is None:
            fx = EFFECT_LEVELS.get("balanced") or next(iter(EFFECT_LEVELS.values()))
        spec = build_values(state["phase"], n=20, lo=8, hi=92, freq=1.1, jitter=fx["value_jitter"])
        await send_ui_update(state, {"id": "music_spectrum", "values": spec}, page_hint="page_music")
    elif page_id == "page_settings":
        await send_ui_update(state, {"id": "settings_title", "text": "Settings"}, page_hint="page_settings")
        await refresh_settings_panel(state)


async def refresh_settings_panel(state):
    await send_ui_update(
        state,
        {
            "id": "settings_sub",
            "text": (
                f"theme={state['theme']} scene={state['scene']} "
                f"fx={state['effects_level']} motion={state['motion_profile']} "
                f"layout={state['layout_variant']} preset={state['visual_preset']} "
                f"debug={state['debug_enabled']}"
            ),
        },
        page_hint="page_settings",
    )
    await send_ui_update(
        state,
        {
            "id": "settings_status",
            "text": (
                f"motion={state['motion_profile']} dial={get_motion_profile(state)['dial_curve']} "
                f"layout={state['layout_variant']} preset={state['visual_preset']} "
                f"action={state.get('last_action', 'ready')}"
            ),
        },
        page_hint="page_settings",
    )


async def apply_effects_runtime(state, refresh=True):
    fx = EFFECT_LEVELS.get(state["effects_level"])
    if fx is None:
        fx = EFFECT_LEVELS.get("balanced") or next(iter(EFFECT_LEVELS.values()))

    await send_ui_update(state, {"id": "voice_overlay_bg", "bg_opa": fx["overlay_bg_opa"]}, force=True)
    if refresh:
        await refresh_settings_panel(state)


async def apply_scene_runtime(state, refresh=True):
    scene = state.get("scene", "aurora")
    await send_ui_update(state, {"id": "scene_layer_aurora", "opa": 255 if scene == "aurora" else 0}, force=True)
    await send_ui_update(state, {"id": "scene_layer_grid", "opa": 255 if scene == "grid" else 0}, force=True)
    await send_ui_update(state, {"id": "scene_layer_orbit", "opa": 255 if scene == "orbit" else 0}, force=True)
    if refresh:
        await refresh_settings_panel(state)


async def apply_layout_runtime(state, refresh=True):
    layout = get_layout_variant(state)

    for cid in ("page_music_card", "page_dial_card", "page_settings_card"):
        await send_ui_update(
            state,
            {"id": cid, "w": layout["page_card_w"], "h": layout["page_card_h"], "pad": layout["page_card_pad"]},
            force=True,
        )

    await send_ui_update(state, {"id": "dial_btn_row", "y": layout["dial_row_y"]}, page_hint="page_dial")
    if refresh:
        await refresh_settings_panel(state)


async def apply_theme_runtime(state, refresh=True):
    palette = THEME_PRESETS.get(state["theme"])
    if palette is None:
        if THEME_ORDER and THEME_ORDER[0] in THEME_PRESETS:
            palette = THEME_PRESETS[THEME_ORDER[0]]
        elif THEME_PRESETS:
            palette = next(iter(THEME_PRESETS.values()))
        else:
            return

    # Scene colors (all scene layers are always present; update all for consistency).
    await send_ui_update(state, {"id": "scene_glow_left", "bg_color": palette["glow_a"]}, force=True)
    await send_ui_update(state, {"id": "scene_glow_right", "bg_color": palette["glow_b"]}, force=True)

    ids = [
        "scene_grid_h1", "scene_grid_h2", "scene_grid_h3", "scene_grid_h4", "scene_grid_h5",
        "scene_grid_v1", "scene_grid_v2", "scene_grid_v3", "scene_grid_v4", "scene_grid_v5",
    ]
    for sid in ids:
        color = palette["glow_b"] if sid.endswith("3") else palette["glow_a"]
        await send_ui_update(state, {"id": sid, "bg_color": color}, force=True)

    await send_ui_update(state, {"id": "scene_orbit_dot_a", "bg_color": palette["glow_b"]}, force=True)
    await send_ui_update(state, {"id": "scene_orbit_dot_b", "bg_color": palette["glow_a"]}, force=True)

    # Shared overlay and widgets.
    await send_ui_update(state, {"id": "voice_overlay_bg", "bg_color": palette["overlay_bg"]}, force=True)
    await send_ui_update(state, {"id": "global_scene", "bg_color": palette["root_bg"]}, force=True)
    await send_ui_update(state, {"id": "voice_wave", "color": palette["wave_color"]}, force=True)
    await send_ui_update(state, {"id": "music_spectrum", "color": palette["spectrum_color"]}, page_hint="page_music")
    await send_ui_update(
        state,
        {"id": "dial_main", "ring_fg": palette["dial_ring_fg"], "ring_bg": palette["dial_ring_bg"]},
        page_hint="page_dial",
    )

    await send_ui_update(state, {"id": "music_heading", "text_color": palette["heading_text"]}, page_hint="page_music")
    await send_ui_update(state, {"id": "music_title", "text_color": palette["title_text"]}, page_hint="page_music")
    await send_ui_update(state, {"id": "music_sub", "text_color": palette["sub_text"]}, page_hint="page_music")
    await send_ui_update(state, {"id": "dial_heading", "text_color": palette["heading_text"]}, page_hint="page_dial")
    await send_ui_update(state, {"id": "settings_heading", "text_color": palette["heading_text"]}, page_hint="page_settings")
    await send_ui_update(state, {"id": "settings_title", "text_color": palette["title_text"]}, page_hint="page_settings")
    await send_ui_update(state, {"id": "settings_sub", "text_color": palette["sub_text"]}, page_hint="page_settings")

    if refresh:
        await refresh_settings_panel(state)


async def apply_visual_runtime_bundle(state):
    await apply_scene_runtime(state, refresh=False)
    await apply_theme_runtime(state, refresh=False)
    await apply_layout_runtime(state, refresh=False)
    await apply_effects_runtime(state, refresh=False)
    await refresh_settings_panel(state)


async def set_overlay_visible(state, visible, reason):
    task = state.get("overlay_task")
    if task and not task.done():
        task.cancel()

    async def _run():
        motion = get_motion_profile(state)
        enter_s = motion["overlay_enter_ms"] / 1000.0
        exit_s = motion["overlay_exit_ms"] / 1000.0
        if visible:
            state["overlay_phase"] = "entering"
            state["overlay_visible"] = True
            await send_ui_update(state, {"id": "voice_modal", "hidden": False}, force=True)
            await send_ui_update(state, {"id": "voice_status", "text": f"Opening... ({reason})"}, force=True)
            await asyncio.sleep(enter_s)
            state["overlay_phase"] = "active"
            await send_ui_update(state, {"id": "voice_status", "text": "Listening..."}, force=True)
        else:
            state["overlay_phase"] = "exiting"
            await send_ui_update(state, {"id": "voice_status", "text": f"Closing... ({reason})"}, force=True)
            await asyncio.sleep(exit_s)
            await send_ui_update(state, {"id": "voice_modal", "hidden": True}, force=True)
            state["overlay_visible"] = False
            state["overlay_phase"] = "hidden"

    state["overlay_task"] = asyncio.create_task(_run())
    try:
        await state["overlay_task"]
    except asyncio.CancelledError:
        pass


async def push_live_updates(device_id, state):
    tick = 0
    while True:
        fx = EFFECT_LEVELS.get(state["effects_level"])
        if fx is None:
            fx = EFFECT_LEVELS.get("balanced") or next(iter(EFFECT_LEVELS.values()))
        await asyncio.sleep(fx["update_tick_s"])
        ws = state.get("ws")
        if not ws:
            continue
        tick += 1

        state["phase"] += 0.23
        p = state["phase"]
        scene = state.get("scene", "aurora")
        scene_step = max(1, int(fx.get("scene_step", 1)))
        music_step = max(1, int(fx.get("music_step", 1)))
        overlay_step = max(1, int(fx.get("overlay_step", 1)))
        status_step = max(1, int(fx.get("status_step", 2)))
        spectrum_points = max(8, int(fx.get("spectrum_points", 20)))
        wave_points = max(8, int(fx.get("wave_points", 16)))

        if tick % scene_step == 0:
            if scene == "aurora":
                glow = fx.get("glow_opa", 24)
                opa_l = int(max(8, glow - 8) + (math.sin(p * 0.8) + 1.0) * (glow * 0.45))
                opa_r = int(max(6, glow - 10) + (math.cos(p * 0.7) + 1.0) * (glow * 0.40))
                # Use bg_opa here. The glow nodes already have low base alpha, and animating
                # object-level opa multiplies alpha again, making them almost invisible.
                await send_ui_update(state, {"id": "scene_glow_left", "bg_opa": opa_l}, force=True)
                await send_ui_update(state, {"id": "scene_glow_right", "bg_opa": opa_r}, force=True)
            elif scene == "grid":
                glow = fx.get("glow_opa", 24)
                pulse = int(max(6, glow // 3) + (math.sin(p * 1.4) + 1.0) * (glow * 0.35))
                await send_ui_update(state, {"id": "scene_grid_h3", "bg_opa": pulse}, force=True)
                await send_ui_update(state, {"id": "scene_grid_v3", "bg_opa": pulse}, force=True)
            elif scene == "orbit":
                dot_ax = int(126 * math.cos(p * 0.9))
                dot_ay = int(126 * math.sin(p * 0.9))
                dot_bx = int(-148 * math.cos(p * 0.55))
                dot_by = int(148 * math.sin(p * 0.55))
                await send_ui_update(state, {"id": "scene_orbit_dot_a", "x": dot_ax, "y": dot_ay}, force=True)
                await send_ui_update(state, {"id": "scene_orbit_dot_b", "x": dot_bx, "y": dot_by}, force=True)

        if state.get("current_page") == "page_music" and tick % music_step == 0:
            spec = build_values(p, n=spectrum_points, lo=8, hi=92, freq=1.1, jitter=fx["value_jitter"])
            await send_ui_update(state, {"id": "music_spectrum", "values": spec}, page_hint="page_music")

        if state.get("overlay_visible") and tick % overlay_step == 0:
            wave = build_values(p, n=wave_points, lo=4, hi=96, freq=1.8, jitter=fx["value_jitter"])
            await send_ui_update(state, {"id": "voice_wave", "values": wave}, force=True)
            if tick % status_step == 0:
                await send_ui_update(state, {"id": "voice_status", "text": f"Listening... {now_str()}"}, force=True)

        if state.get("current_page") == "page_music" and tick % 8 == 0:
            await send_ui_update(
                state,
                {"id": "music_sub", "text": f"{now_str()} theme={state['theme']} fx={state['effects_level']}"},
                page_hint="page_music",
            )


async def rebuild_layout(state, reason):
    ws = state["ws"]
    state["hydrated_pages"].clear()
    state["pending_updates"].clear()
    await send_topic(ws, "ui/layout", build_layout(state))
    logging.info(
        "rebuild layout reason=%s theme=%s scene=%s fx=%s motion=%s layout=%s initial_page=%d contract=wait_page_changed_before_update",
        reason,
        state["theme"],
        state["scene"],
        state["effects_level"],
        state["motion_profile"],
        state["layout_variant"],
        state["initial_page"],
    )


async def replay_key_runtime_state(state):
    # Keep dial state consistent across rebuilds.
    await send_ui_update(state, {"id": "dial_main", "value": state["dial_value"]}, page_hint="page_dial")
    await send_ui_update(state, {"id": "dial_value_text", "text": f"{state['dial_value']}"}, page_hint="page_dial")

    # Keep overlay intent consistent across rebuilds.
    if state.get("overlay_visible"):
        await send_ui_update(state, {"id": "voice_modal", "hidden": False}, force=True)
        if state.get("overlay_phase") == "active":
            await send_ui_update(state, {"id": "voice_status", "text": "Listening..."}, force=True)
        elif state.get("overlay_phase") == "entering":
            await send_ui_update(state, {"id": "voice_status", "text": "Opening..."}, force=True)
        else:
            await send_ui_update(state, {"id": "voice_status", "text": "Listening..."}, force=True)
    else:
        await send_ui_update(state, {"id": "voice_modal", "hidden": True}, force=True)


async def rebuild_with_runtime_replay(state, reason):
    await rebuild_layout(state, reason)
    await apply_visual_runtime_bundle(state)
    await replay_key_runtime_state(state)


async def run_smoke(state):
    if state.get("smoke_task") and not state["smoke_task"].done():
        return

    async def _run():
        ws = state["ws"]
        logging.info("smoke start")
        await send_topic(ws, "ui/debug", "on")
        state["debug_enabled"] = True
        await asyncio.sleep(0.4)
        await set_overlay_visible(state, True, "smoke_enter")
        await asyncio.sleep(0.9)
        await set_overlay_visible(state, False, "smoke_exit")
        await asyncio.sleep(0.4)

        for v in (12, 55, 87):
            state["dial_value"] = v
            await send_ui_update(state, {"id": "dial_main", "value": v}, page_hint="page_dial")
            await send_ui_update(state, {"id": "dial_value_text", "text": f"{v}"}, page_hint="page_dial")
            await asyncio.sleep(0.25)

        state["theme"] = cycle_option(state["theme"], THEME_ORDER)
        state["visual_preset"] = "custom"
        await rebuild_layout(state, "smoke_switch_theme")
        await asyncio.sleep(0.5)
        state["scene"] = cycle_option(state["scene"], SCENE_ORDER)
        state["visual_preset"] = "custom"
        await rebuild_layout(state, "smoke_switch_scene")
        await asyncio.sleep(0.5)
        if state["effects_level"] == "lite":
            state["effects_level"] = "balanced"
        elif state["effects_level"] == "balanced":
            state["effects_level"] = "rich"
        else:
            state["effects_level"] = "lite"
        state["visual_preset"] = "custom"
        await rebuild_layout(state, "smoke_switch_fx")
        await asyncio.sleep(0.5)
        state["motion_profile"] = cycle_option(state["motion_profile"], MOTION_ORDER)
        state["visual_preset"] = "custom"
        await rebuild_layout(state, "smoke_switch_motion")
        await asyncio.sleep(0.5)
        state["layout_variant"] = cycle_option(state["layout_variant"], LAYOUT_ORDER)
        state["visual_preset"] = "custom"
        await rebuild_layout(state, "smoke_switch_layout")
        await asyncio.sleep(0.5)
        await send_topic(ws, "ui/debug", "off")
        state["debug_enabled"] = False
        logging.info("smoke done")

    state["smoke_task"] = asyncio.create_task(_run())


async def handle_topic(websocket, device_id, state, topic, payload):
    if topic == "sys/ping":
        await send_topic(
            websocket,
            "sys/pong",
            {
                "uptime": int(time.time() - START_TIME),
                "status": "online",
                "server_time": time.strftime("%Y-%m-%d %H:%M:%S"),
            },
        )
        return

    if topic == "telemetry/heartbeat":
        if not state.get("initialized"):
            state["initialized"] = True
            await rebuild_layout(state, "telemetry_init")
            if not state.get("pump_task"):
                state["pump_task"] = asyncio.create_task(push_live_updates(device_id, state))
        return

    if topic == "ui/page_changed":
        page = payload.get("page", "")
        idx = payload.get("index")
        logging.info("[%s] page_changed page=%s idx=%s", device_id, page, idx)
        if page in PAGE_IDS:
            state["current_page"] = page
        if isinstance(idx, int) and 0 <= idx < len(PAGE_IDS):
            state["initial_page"] = idx
        if page and page not in state["hydrated_pages"]:
            state["hydrated_pages"].add(page)
            await hydrate_page(state, page)
            await flush_page_updates(state, page)
        return

    if topic == "ui/dial_final":
        state["dial_value"] = int(payload.get("value", state["dial_value"]))
        await send_ui_update(state, {"id": "dial_main", "value": state["dial_value"]}, page_hint="page_dial")
        await send_ui_update(state, {"id": "dial_value_text", "text": f"{state['dial_value']}"}, page_hint="page_dial")
        return

    if topic == "ui/toggle_overlay":
        await set_overlay_visible(state, not state["overlay_visible"], "user_toggle")
        return

    if topic == "ui/toggle_debug":
        state["debug_enabled"] = not state["debug_enabled"]
        await send_topic(websocket, "ui/debug", "on" if state["debug_enabled"] else "off")
        await refresh_settings_panel(state)
        return

    if topic == "ui/page_next":
        async def _op():
            state["initial_page"] = (state["initial_page"] + 1) % len(PAGE_IDS)
            state["current_page"] = PAGE_IDS[state["initial_page"]]
            await rebuild_layout(state, "page_next_button")
        await run_visual_switch(state, _op)
        return

    if topic == "ui/page_prev":
        async def _op():
            state["initial_page"] = (state["initial_page"] - 1 + len(PAGE_IDS)) % len(PAGE_IDS)
            state["current_page"] = PAGE_IDS[state["initial_page"]]
            await rebuild_layout(state, "page_prev_button")
        await run_visual_switch(state, _op)
        return

    if topic == "ui/switch_theme":
        async def _op():
            state["theme"] = cycle_option(state["theme"], THEME_ORDER)
            state["visual_preset"] = "custom"
            state["last_action"] = f"theme={state['theme']}"
            # Theme hot-switches supported runtime color fields to avoid full layout rebuild.
            await apply_theme_runtime(state)
        await run_visual_switch(state, _op)
        return

    if topic == "ui/switch_scene":
        async def _op():
            state["scene"] = cycle_option(state["scene"], SCENE_ORDER)
            state["visual_preset"] = "custom"
            state["last_action"] = f"scene={state['scene']}"
            await apply_scene_runtime(state)
        await run_visual_switch(state, _op)
        return

    if topic == "ui/switch_motion":
        async def _op():
            state["motion_profile"] = cycle_option(state["motion_profile"], MOTION_ORDER)
            state["visual_preset"] = "custom"
            state["last_action"] = f"motion={state['motion_profile']}"
            # Motion profile does not change node tree; apply incrementally to avoid layout rebuild jitter.
            await refresh_settings_panel(state)
        await run_visual_switch(state, _op)
        return

    if topic == "ui/switch_layout":
        async def _op():
            state["layout_variant"] = cycle_option(state["layout_variant"], LAYOUT_ORDER)
            state["visual_preset"] = "custom"
            state["last_action"] = f"layout={state['layout_variant']}"
            await apply_layout_runtime(state)
        await run_visual_switch(state, _op)
        return

    if topic == "ui/switch_preset":
        async def _op():
            next_preset = cycle_option(state["visual_preset"], VISUAL_PRESET_ORDER)
            if next_preset == "custom":
                next_preset = VISUAL_PRESET_ORDER[0]
            apply_visual_preset(state, next_preset)
            state["last_action"] = f"preset={state['visual_preset']}"
            await apply_visual_runtime_bundle(state)
        await run_visual_switch(state, _op)
        return

    if topic == "ui/reload_visual_config":
        async def _op():
            load_visual_config()
            normalize_visual_state(state)
            state["last_action"] = "reload_visual_config"
            await rebuild_layout(state, "reload_visual_config")
            await apply_visual_runtime_bundle(state)
        await run_visual_switch(state, _op, cooldown_ms=250)
        return

    if topic == "ui/freeze_profile":
        async def _op():
            output = save_locked_visual_config(state)
            state["last_action"] = f"frozen->{output.name}"
            logging.info("[%s] visual profile frozen to %s", device_id, output)
            await refresh_settings_panel(state)
        await run_visual_switch(state, _op, cooldown_ms=300)
        return

    if topic == "ui/toggle_effects":
        async def _op():
            if state["effects_level"] == "lite":
                state["effects_level"] = "balanced"
            elif state["effects_level"] == "balanced":
                state["effects_level"] = "rich"
            else:
                state["effects_level"] = "lite"
            state["visual_preset"] = "custom"
            state["last_action"] = f"fx={state['effects_level']}"
            # Effects level now hot-switches for runtime params without full layout rebuild.
            await apply_effects_runtime(state)
        await run_visual_switch(state, _op)
        return

    if topic == "ui/run_smoke":
        await run_smoke(state)
        return

    if topic == "ui/demo_ops_tx":
        src = payload.get("source", "unknown")
        state["last_action"] = f"ops_tx:{src}"
        await send_ui_ops_update(
            state,
            "page_settings",
            [
                {"op": "set", "id": "settings_status", "path": "text", "value": f"ops tx ok @{now_str()} [{src}]"},
                {"op": "patch", "id": "settings_status", "path": "text_color", "value": "#7EE787"},
            ],
            transaction=True,
        )
        return

    if topic == "ui/demo_ops_fail":
        src = payload.get("source", "unknown")
        state["last_action"] = f"ops_fail:{src}"
        await send_ui_ops_update(
            state,
            "page_settings",
            [
                {"op": "set", "id": "settings_status", "path": "text", "value": "this op should rollback"},
                {"op": "set", "id": "missing_widget", "path": "text", "value": "trigger_tx_fail"},
            ],
            transaction=True,
        )
        await send_ui_update(
            state,
            {"id": "settings_status", "text": f"ops fail injected @{now_str()} [{src}]"},
            page_hint="page_settings",
        )
        return


async def sdui_handler(websocket):
    remote = websocket.remote_address
    device_id = None
    state = None

    try:
        async for message in websocket:
            data = json.loads(message)
            topic = data.get("topic")
            payload = data.get("payload", {})
            device_id = data.get("device_id") or device_id

            if not device_id:
                logging.warning("[%s] message missing device_id", remote)
                continue

            state = get_or_create_device(device_id, websocket, remote)
            if not state.get("initialized"):
                state["initialized"] = True
                await rebuild_layout(state, "first_message_init")
                if not state.get("pump_task"):
                    state["pump_task"] = asyncio.create_task(push_live_updates(device_id, state))

            await handle_topic(websocket, device_id, state, topic, payload)

    except websockets.exceptions.ConnectionClosed:
        pass
    except Exception as exc:
        logging.exception("[%s] websocket handler crashed: %r", remote, exc)
    finally:
        # Only clean tasks if this handler still owns the active websocket.
        # Avoid canceling tasks that belong to a newer reconnected session.
        if state and state.get("ws") is websocket:
            if state.get("pump_task"):
                state["pump_task"].cancel()
                state["pump_task"] = None
            if state.get("smoke_task"):
                state["smoke_task"].cancel()
                state["smoke_task"] = None
            if state.get("overlay_task"):
                state["overlay_task"].cancel()
                state["overlay_task"] = None
        logging.info("disconnected: %s", remote)


async def main():
    load_visual_config()
    async with websockets.serve(
        sdui_handler,
        "0.0.0.0",
        8080,
        ping_interval=None,
        ping_timeout=None,
        close_timeout=60,
        max_size=2**20,
    ):
        logging.info("ui2 full-coverage test server started on ws://0.0.0.0:8080")
        await asyncio.Future()


if __name__ == "__main__":
    asyncio.run(main())

