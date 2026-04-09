#!/usr/bin/env python3
"""
Main template sample:
- Multi-page verification template for SDUI terminal.
- Each feature point is isolated to its own viewport page for easy testing.
- Includes theme/scene switching, chart widget updates, and font-size layout pages.
"""

import asyncio
import json
import logging
import math
import os
import random
import time

import websockets


logging.basicConfig(level=logging.INFO, format="%(asctime)s [%(levelname)s] %(message)s")
logging.getLogger("websockets").setLevel(logging.WARNING)

HOST = "0.0.0.0"
PORT = 8080
START_TIME = time.time()
SCREEN_DIAMETER = int(os.getenv("SDUI_SCREEN_DIAMETER", "466"))
TEXT_RISK_MARGIN = int(os.getenv("SDUI_TEXT_RISK_MARGIN", "10"))
TEXT_RISK_CHECK_ENABLED = os.getenv("SDUI_TEXT_RISK_CHECK", "1") != "0"
TEXT_RISK_CHECK_ONLY = os.getenv("SDUI_TEXT_RISK_CHECK_ONLY", "0") == "1"

PAGE_IDS = [
    "page_chart",
    "page_rings",
    "page_preview",
    "page_control",
    "page_font_small",
    "page_font_medium",
    "page_font_large",
    "page_debug",
]
INITIAL_PAGE_INDEX = 2

FONT_PROFILES = {
    "small": {"title": 14, "body": 14},
    "medium": {"title": 20, "body": 20},
    "large": {"title": 24, "body": 24},
}

THEMES = {
    "ice": {
        "root_bg": "#04070d",
        "title_text": "#dff2ff",
        "sub_text": "#88a8bf",
        "accent": "#46d4ff",
        "accent_2": "#9dffd2",
        "glow_a": "#2f8cff",
        "glow_b": "#5ef0ff",
    },
    "sunset": {
        "root_bg": "#160a0b",
        "title_text": "#ffe9d8",
        "sub_text": "#d5ab96",
        "accent": "#ff9f68",
        "accent_2": "#ffcc7a",
        "glow_a": "#ff7f59",
        "glow_b": "#ffc36b",
    },
}
THEME_ORDER = ["ice", "sunset"]

SCENES = {
    "aurora": {"label": "Aurora", "aurora_opa": 255, "grid_opa": 0, "orbit_opa": 0},
    "grid": {"label": "Grid", "aurora_opa": 0, "grid_opa": 255, "orbit_opa": 0},
    "orbit": {"label": "Orbit", "aurora_opa": 0, "grid_opa": 0, "orbit_opa": 255},
}
SCENE_ORDER = ["aurora", "grid", "orbit"]

DEVICES = {}


def cycle(value, options):
    if value not in options:
        return options[0]
    return options[(options.index(value) + 1) % len(options)]


def now_hms():
    return time.strftime("%H:%M:%S")


def estimate_text_units(text):
    units = 0.0
    for ch in text:
        code = ord(ch)
        if ch.isspace():
            units += 0.33
        elif "0" <= ch <= "9":
            units += 0.56
        elif "A" <= ch <= "Z":
            units += 0.62
        elif "a" <= ch <= "z":
            units += 0.56
        elif 0x4E00 <= code <= 0x9FFF:
            units += 1.00
        elif ch in ",.:;!?+-_/\\()[]{}":
            units += 0.38
        else:
            units += 0.72
    return units


def estimate_text_size(text, font_size):
    fs = max(8.0, float(font_size or 14))
    width = estimate_text_units(str(text or "")) * fs
    height = fs * 1.25
    return width, height


def parse_dim(value, base):
    if isinstance(value, (int, float)):
        return float(value)
    if isinstance(value, str):
        v = value.strip()
        if v.endswith("%"):
            try:
                return base * float(v[:-1]) / 100.0
            except ValueError:
                return None
        if v in ("full", "100%"):
            return float(base)
    return None


def resolve_label_center(node, screen_size, safe_pad, est_h):
    align = node.get("align", "center")
    x_off = float(node.get("x", 0) or 0)
    y_off = float(node.get("y", 0) or 0)
    cx = screen_size / 2.0 + x_off

    if align == "top_mid":
        cy = safe_pad + y_off + est_h / 2.0
    elif align == "center":
        cy = screen_size / 2.0 + y_off
    else:
        # Conservative fallback for uncommon align keywords.
        cy = screen_size / 2.0 + y_off
    return cx, cy


def evaluate_text_layout_risk(layout):
    screen = float(SCREEN_DIAMETER)
    radius = screen / 2.0
    risk_r = max(20.0, radius - float(TEXT_RISK_MARGIN))
    center = screen / 2.0
    safe_pad = float(layout.get("safe_pad", 0) or 0)

    pages = []
    for child in layout.get("children", []):
        if child.get("type") == "viewport":
            pages = child.get("pages", [])
            break

    findings = []
    for page in pages:
        page_id = page.get("id", "unknown_page")
        for node in page.get("children", []):
            if node.get("type") != "label":
                continue
            text = str(node.get("text", ""))
            font_size = node.get("font_size", 14)
            est_w, est_h = estimate_text_size(text, font_size)
            explicit_w = parse_dim(node.get("w"), screen)
            # If width is constrained, rendered width may be clipped/wrapped; still evaluate raw risk.
            box_w = explicit_w if explicit_w is not None else est_w
            cx, cy = resolve_label_center(node, screen, safe_pad, est_h)
            top = cy - est_h / 2.0
            bottom = cy + est_h / 2.0
            left = cx - box_w / 2.0
            right = cx + box_w / 2.0

            if top < center - risk_r or bottom > center + risk_r:
                findings.append(
                    f"{page_id}:{node.get('id','label')} risk=vertical_clip top={top:.1f} bottom={bottom:.1f}"
                )
                continue

            dy_top = abs(top - center)
            dy_bottom = abs(bottom - center)
            chord_top = 2.0 * math.sqrt(max(0.0, risk_r * risk_r - dy_top * dy_top))
            chord_bottom = 2.0 * math.sqrt(max(0.0, risk_r * risk_r - dy_bottom * dy_bottom))
            max_w = min(chord_top, chord_bottom)
            if box_w > max_w:
                findings.append(
                    f"{page_id}:{node.get('id','label')} risk=horizontal_clip est_w={box_w:.1f} allowed={max_w:.1f}"
                )

            if explicit_w is None and (left < center - risk_r or right > center + risk_r):
                findings.append(
                    f"{page_id}:{node.get('id','label')} risk=edge_clip left={left:.1f} right={right:.1f}"
                )
    return findings


def log_text_risk(layout):
    if not TEXT_RISK_CHECK_ENABLED:
        return
    findings = evaluate_text_layout_risk(layout)
    if not findings:
        logging.info("[text-risk] no obvious overflow risk (estimate mode)")
        return
    logging.warning("[text-risk] %d potential overflow items (estimate mode)", len(findings))
    for item in findings:
        logging.warning("[text-risk] %s", item)
    logging.warning(
        "[text-risk] this is an estimate based on font_size/alignment/screen diameter; actual render may differ."
    )


def make_state(ws, remote):
    return {
        "ws": ws,
        "addr": str(remote),
        "initialized": False,
        "current_page": PAGE_IDS[INITIAL_PAGE_INDEX],
        "theme": "ice",
        "scene": "aurora",
        "tick": 0,
        "phase": 0.0,
        "pace_sec": 0.55,
        "pump_task": None,
        "update_rev": {},
        "hydrated_pages": {PAGE_IDS[INITIAL_PAGE_INDEX]},
        "deferred_ops": {},
    }


def get_or_create_device(device_id, ws, remote):
    if device_id not in DEVICES:
        DEVICES[device_id] = make_state(ws, remote)
        return DEVICES[device_id]

    st = DEVICES[device_id]
    prev_ws = st.get("ws")
    st["ws"] = ws
    st["addr"] = str(remote)
    if prev_ws is not ws:
        st["initialized"] = False
        st["current_page"] = PAGE_IDS[INITIAL_PAGE_INDEX]
        logging.info("[%s] reconnect detected, reset init state", device_id)
    return st


async def send_topic(ws, topic, payload):
    if ws:
        await ws.send(json.dumps({"topic": topic, "payload": payload}, ensure_ascii=False))


def next_rev(state, page_id):
    revs = state["update_rev"]
    revs[page_id] = int(revs.get(page_id, 0)) + 1
    return revs[page_id]


async def send_ops(state, page_id, ops):
    await send_topic(
        state["ws"],
        "ui/update",
        {
            "page_id": page_id,
            "revision": next_rev(state, page_id),
            "transaction": True,
            "ops": ops,
        },
    )


async def send_ops_page_safe(state, page_id, ops):
    if page_id == state.get("current_page") or page_id in state.get("hydrated_pages", set()):
        await send_ops(state, page_id, ops)
        return
    # Keep only latest deferred ops per page to avoid backlog growth.
    state["deferred_ops"][page_id] = ops


async def flush_deferred_for_page(state, page_id):
    ops = state.get("deferred_ops", {}).pop(page_id, None)
    if ops:
        await send_ops(state, page_id, ops)


async def send_ops_global(state, ops):
    await send_topic(
        state["ws"],
        "ui/update",
        {
            "transaction": True,
            "ops": ops,
        },
    )


def scene_layers(theme_name, scene_name):
    t = THEMES[theme_name]
    s = SCENES[scene_name]
    return [
        {
            "type": "container",
            "id": "scene_aurora",
            "w": "full",
            "h": "full",
            "opa": s["aurora_opa"],
            "children": [
                {"type": "container", "id": "aurora_l", "w": 280, "h": 88, "align": "center", "x": -65, "y": -70, "radius": 999, "bg_color": t["glow_a"], "bg_opa": 46},
                {"type": "container", "id": "aurora_r", "w": 260, "h": 80, "align": "center", "x": 85, "y": 62, "radius": 999, "bg_color": t["glow_b"], "bg_opa": 46},
            ],
        },
        {
            "type": "container",
            "id": "scene_grid",
            "w": "full",
            "h": "full",
            "opa": s["grid_opa"],
            "children": [
                {"type": "container", "id": "grid_h", "w": "92%", "h": 2, "align": "center", "y": -18, "bg_color": t["accent"], "bg_opa": 34},
                {"type": "container", "id": "grid_v", "w": 2, "h": "72%", "align": "center", "bg_color": t["accent_2"], "bg_opa": 34},
            ],
        },
        {
            "type": "container",
            "id": "scene_orbit",
            "w": "full",
            "h": "full",
            "opa": s["orbit_opa"],
            "children": [
                {"type": "container", "id": "orbit_ring_outer", "w": 256, "h": 256, "align": "center", "radius": 999, "bg_opa": 0, "border_w": 2, "border_color": t["accent"]},
                {"type": "container", "id": "orbit_ring_inner", "w": 174, "h": 174, "align": "center", "radius": 999, "bg_opa": 0, "border_w": 2, "border_color": t["accent_2"]},
                {"type": "container", "id": "orbit_dot_a", "w": 14, "h": 14, "align": "center", "x": -128, "y": 0, "radius": 999, "bg_color": t["glow_a"]},
                {"type": "container", "id": "orbit_dot_b", "w": 14, "h": 14, "align": "center", "x": 128, "y": 0, "radius": 999, "bg_color": t["glow_b"]},
            ],
        },
    ]


def build_font_page(page_id, profile_name, theme):
    font = FONT_PROFILES[profile_name]
    label = profile_name.capitalize()
    return {
        "id": page_id,
        "children": [
            {"type": "label", "id": f"{page_id}_title", "text": f"Font {label}", "align": "top_mid", "y": 28, "font_size": font["title"], "text_color": theme["title_text"]},
            {"type": "label", "id": f"{page_id}_line1", "text": f"{label} title={font['title']}", "align": "center", "y": -28, "font_size": font["body"], "text_color": theme["sub_text"]},
            {"type": "label", "id": f"{page_id}_line2", "text": f"{label} body={font['body']} 123", "align": "center", "y": 8, "font_size": font["body"], "text_color": theme["sub_text"]},
            {"type": "button", "id": f"{page_id}_btn", "text": "Action", "w": 140, "h": 42, "align": "center", "y": 62, "bg_color": theme["accent"], "font_size": font["body"], "on_click": "server://ui/font_demo_action"},
        ],
    }


def build_layout(state):
    t = THEMES[state["theme"]]
    scene_label = SCENES[state["scene"]]["label"]
    return {
        "safe_pad": 0,
        "w": "full",
        "h": "full",
        "bg_color": t["root_bg"],
        "children": [
            {"type": "scene", "id": "global_scene", "w": "full", "h": "full", "children": scene_layers(state["theme"], state["scene"])},
            {
                "type": "viewport",
                "id": "main_viewport",
                "direction": "horizontal",
                "initial_page": INITIAL_PAGE_INDEX,
                "pages": [
                    {
                        "id": "page_chart",
                        "children": [
                            {"type": "label", "id": "chart_title", "text": "Component Charts", "align": "top_mid", "y": 28, "font_size": 18, "text_color": t["title_text"]},
                            {"type": "widget", "widget_type": "spectrum", "id": "chart_spectrum", "w": 300, "h": 98, "align": "center", "y": -40, "canvas_w": 300, "canvas_h": 98, "color": t["accent_2"], "values": [28, 36, 48, 42, 55, 62, 58, 64, 52, 40, 38, 46]},
                            {"type": "bar", "id": "chart_progress", "w": 230, "h": 14, "align": "center", "y": 78, "min": 0, "max": 100, "value": 42, "bg_color": "#203246", "indic_color": t["accent_2"], "radius": 10},
                            {"type": "label", "id": "chart_sub", "text": "tick=0 load=42%", "align": "center", "y": 108, "font_size": 14, "text_color": t["sub_text"]},
                        ],
                    },
                    {
                        "id": "page_rings",
                        "children": [
                            {
                                "type": "widget",
                                "widget_type": "dial",
                                "id": "ring_cpu",
                                "w": 460,
                                "h": 460,
                                "align": "center",
                                "y": 0,
                                "pointer_events": "none",
                                "min": 0,
                                "max": 100,
                                "value": 62,
                                "start_angle": 0,
                                "sweep_angle": 359,
                                "arc_width": 20,
                                "ring_bg": "#233346",
                                "ring_fg": "#FF6B6B",
                                "value_format": " ",
                            },
                            {
                                "type": "widget",
                                "widget_type": "dial",
                                "id": "ring_mem",
                                "w": 414,
                                "h": 414,
                                "align": "center",
                                "y": 0,
                                "pointer_events": "none",
                                "min": 0,
                                "max": 100,
                                "value": 48,
                                "start_angle": 0,
                                "sweep_angle": 359,
                                "arc_width": 20,
                                "ring_bg": "#233346",
                                "ring_fg": "#4FD1FF",
                                "value_format": " ",
                            },
                            {
                                "type": "widget",
                                "widget_type": "dial",
                                "id": "ring_net",
                                "w": 368,
                                "h": 368,
                                "align": "center",
                                "y": 0,
                                "pointer_events": "none",
                                "min": 0,
                                "max": 100,
                                "value": 74,
                                "start_angle": 0,
                                "sweep_angle": 359,
                                "arc_width": 20,
                                "ring_bg": "#233346",
                                "ring_fg": "#7CFFB2",
                                "value_format": " ",
                            },
                            {"type": "label", "id": "ring_center_title", "text": "Server Usage", "align": "center", "y": -26, "font_size": 16, "text_color": t["title_text"]},
                            {"type": "label", "id": "ring_cpu_text", "text": "CPU 62%", "align": "center", "y": 8, "font_size": 14, "text_color": "#FF6B6B"},
                            {"type": "label", "id": "ring_mem_text", "text": "MEM 48%", "align": "center", "y": 34, "font_size": 14, "text_color": "#4FD1FF"},
                            {"type": "label", "id": "ring_net_text", "text": "NET 74%", "align": "center", "y": 60, "font_size": 14, "text_color": "#7CFFB2"},
                            {"type": "label", "id": "ring_sub", "text": "tick=0", "align": "center", "y": 166, "font_size": 13, "text_color": t["sub_text"]},
                        ],
                    },
                    {
                        "id": "page_preview",
                        "children": [
                            {"type": "label", "id": "preview_title", "text": "Theme + Scene", "align": "top_mid", "y": 28, "font_size": 18, "text_color": t["title_text"]},
                            {"type": "label", "id": "preview_sub", "text": f"{state['theme']} / {scene_label}", "align": "top_mid", "y": 44, "font_size": 14, "text_color": t["sub_text"]},
                            {"type": "label", "id": "preview_hint", "text": "Swipe pages to verify isolated features", "align": "center", "y": 90, "font_size": 14, "text_color": t["sub_text"]},
                        ],
                    },
                    {
                        "id": "page_control",
                        "children": [
                            {"type": "label", "id": "control_title", "text": "Runtime Controls", "align": "top_mid", "y": 28, "font_size": 18, "text_color": t["title_text"]},
                            {"type": "button", "id": "btn_theme", "text": "Theme", "w": 130, "h": 42, "align": "center", "x": -74, "y": -32, "bg_color": t["accent"], "on_click": "server://ui/switch_theme"},
                            {"type": "button", "id": "btn_scene", "text": "Scene", "w": 130, "h": 42, "align": "center", "x": 74, "y": -32, "bg_color": t["accent_2"], "on_click": "server://ui/switch_scene"},
                            {"type": "button", "id": "btn_faster", "text": "Faster", "w": 130, "h": 40, "align": "center", "x": -74, "y": 24, "bg_color": t["accent"], "on_click": "server://ui/pace_faster"},
                            {"type": "button", "id": "btn_slower", "text": "Slower", "w": 130, "h": 40, "align": "center", "x": 74, "y": 24, "bg_color": t["accent_2"], "on_click": "server://ui/pace_slower"},
                            {"type": "label", "id": "status", "text": f"theme={state['theme']} scene={scene_label} pace={state['pace_sec']:.2f}s", "align": "center", "y": 82, "font_size": 14, "text_color": t["sub_text"]},
                        ],
                    },
                    build_font_page("page_font_small", "small", t),
                    build_font_page("page_font_medium", "medium", t),
                    build_font_page("page_font_large", "large", t),
                    {
                        "id": "page_debug",
                        "children": [
                            {"type": "label", "id": "debug_title", "text": "Debug State", "align": "top_mid", "y": 28, "font_size": 18, "text_color": t["title_text"]},
                            {"type": "label", "id": "debug_line1", "text": "page=page_preview", "align": "center", "y": -26, "font_size": 14, "text_color": t["sub_text"]},
                            {"type": "label", "id": "debug_line2", "text": "theme=ice scene=Aurora", "align": "center", "y": 8, "font_size": 14, "text_color": t["sub_text"]},
                            {"type": "label", "id": "debug_line3", "text": "tick=0 uptime=0", "align": "center", "y": 42, "font_size": 14, "text_color": t["sub_text"]},
                        ],
                    },
                ],
            },
        ],
    }


async def apply_theme_scene_runtime(state):
    t = THEMES[state["theme"]]
    s = SCENES[state["scene"]]
    scene_label = s["label"]

    global_scene_ops = [
        {"op": "set", "id": "scene_aurora", "path": "opa", "value": s["aurora_opa"]},
        {"op": "set", "id": "scene_grid", "path": "opa", "value": s["grid_opa"]},
        {"op": "set", "id": "scene_orbit", "path": "opa", "value": s["orbit_opa"]},
        {"op": "set", "id": "aurora_l", "path": "bg_color", "value": t["glow_a"]},
        {"op": "set", "id": "aurora_r", "path": "bg_color", "value": t["glow_b"]},
        {"op": "set", "id": "grid_h", "path": "bg_color", "value": t["accent"]},
        {"op": "set", "id": "grid_v", "path": "bg_color", "value": t["accent_2"]},
        {"op": "set", "id": "orbit_ring_outer", "path": "border_color", "value": t["accent"]},
        {"op": "set", "id": "orbit_ring_inner", "path": "border_color", "value": t["accent_2"]},
        {"op": "set", "id": "orbit_dot_a", "path": "bg_color", "value": t["glow_a"]},
        {"op": "set", "id": "orbit_dot_b", "path": "bg_color", "value": t["glow_b"]},
    ]
    await send_ops_global(state, global_scene_ops)

    preview_ops = [
        {"op": "set", "id": "preview_title", "path": "text_color", "value": t["title_text"]},
        {"op": "set", "id": "preview_sub", "path": "text", "value": f"{state['theme']} / {scene_label}"},
        {"op": "set", "id": "preview_sub", "path": "text_color", "value": t["sub_text"]},
        {"op": "set", "id": "preview_hint", "path": "text_color", "value": t["sub_text"]},
    ]
    await send_ops_page_safe(state, "page_preview", preview_ops)

    control_ops = [
        {"op": "set", "id": "control_title", "path": "text_color", "value": t["title_text"]},
        {"op": "set", "id": "status", "path": "text", "value": f"theme={state['theme']} scene={scene_label} pace={state['pace_sec']:.2f}s"},
        {"op": "set", "id": "status", "path": "text_color", "value": t["sub_text"]},
        {"op": "set", "id": "btn_theme", "path": "bg_color", "value": t["accent"]},
        {"op": "set", "id": "btn_scene", "path": "bg_color", "value": t["accent_2"]},
        {"op": "set", "id": "btn_faster", "path": "bg_color", "value": t["accent"]},
        {"op": "set", "id": "btn_slower", "path": "bg_color", "value": t["accent_2"]},
    ]
    await send_ops_page_safe(state, "page_control", control_ops)

    await send_ops_page_safe(
        state,
        "page_chart",
        [
            {"op": "set", "id": "chart_title", "path": "text_color", "value": t["title_text"]},
            {"op": "set", "id": "chart_sub", "path": "text_color", "value": t["sub_text"]},
            {"op": "set", "id": "chart_progress", "path": "indic_color", "value": t["accent_2"]},
        ],
    )

    await send_ops_page_safe(
        state,
        "page_rings",
        [
            {"op": "set", "id": "ring_center_title", "path": "text_color", "value": t["title_text"]},
            {"op": "set", "id": "ring_sub", "path": "text_color", "value": t["sub_text"]},
        ],
    )

    for page_id in ["page_font_small", "page_font_medium", "page_font_large"]:
        await send_ops_page_safe(
            state,
            page_id,
            [
                {"op": "set", "id": f"{page_id}_title", "path": "text_color", "value": t["title_text"]},
                {"op": "set", "id": f"{page_id}_line1", "path": "text_color", "value": t["sub_text"]},
                {"op": "set", "id": f"{page_id}_line2", "path": "text_color", "value": t["sub_text"]},
                {"op": "set", "id": f"{page_id}_btn", "path": "bg_color", "value": t["accent"]},
            ],
        )

    await send_ops_page_safe(
        state,
        "page_debug",
        [
            {"op": "set", "id": "debug_title", "path": "text_color", "value": t["title_text"]},
            {"op": "set", "id": "debug_line1", "path": "text_color", "value": t["sub_text"]},
            {"op": "set", "id": "debug_line2", "path": "text_color", "value": t["sub_text"]},
            {"op": "set", "id": "debug_line3", "path": "text_color", "value": t["sub_text"]},
        ],
    )

    # Widget color is not an ops-path field; update it via direct ui/update payload.
    if "page_chart" in state.get("hydrated_pages", set()) or state.get("current_page") == "page_chart":
        await send_topic(state["ws"], "ui/update", {"id": "chart_spectrum", "color": t["accent_2"]})


def build_spectrum_values(phase):
    values = []
    for i in range(16):
        v = 50 + 34 * math.sin(phase + i * 0.42) + random.uniform(-4, 4)
        values.append(max(8, min(92, int(v))))
    return values


async def update_debug_page(state):
    scene_label = SCENES[state["scene"]]["label"]
    await send_ops_page_safe(
        state,
        "page_debug",
        [
            {"op": "set", "id": "debug_line1", "path": "text", "value": f"page={state['current_page']}"},
            {"op": "set", "id": "debug_line2", "path": "text", "value": f"theme={state['theme']} scene={scene_label}"},
            {"op": "set", "id": "debug_line3", "path": "text", "value": f"tick={state['tick']} uptime={int(time.time() - START_TIME)}"},
        ],
    )


async def pump(device_id, state):
    try:
        while True:
            await asyncio.sleep(state["pace_sec"])
            ws = state.get("ws")
            if ws is None:
                break

            state["tick"] += 1
            state["phase"] += 0.28
            p = state["phase"]

            if state["scene"] == "aurora":
                opa_l = int(30 + 24 * (0.5 + 0.5 * math.sin(p)))
                opa_r = int(30 + 24 * (0.5 + 0.5 * math.cos(p)))
                await send_topic(ws, "ui/update", {"id": "aurora_l", "bg_opa": opa_l})
                await send_topic(ws, "ui/update", {"id": "aurora_r", "bg_opa": opa_r})
            elif state["scene"] == "grid":
                pulse = int(16 + 26 * (0.5 + 0.5 * math.sin(p * 1.3)))
                await send_topic(ws, "ui/update", {"id": "grid_h", "bg_opa": pulse})
                await send_topic(ws, "ui/update", {"id": "grid_v", "bg_opa": pulse})
            else:
                ax = int(128 * math.cos(p))
                ay = int(128 * math.sin(p))
                bx = int(128 * math.cos(p + math.pi))
                by = int(128 * math.sin(p + math.pi))
                await send_topic(ws, "ui/update", {"id": "orbit_dot_a", "x": ax, "y": ay})
                await send_topic(ws, "ui/update", {"id": "orbit_dot_b", "x": bx, "y": by})

            chart_ready = "page_chart" in state.get("hydrated_pages", set()) or state.get("current_page") == "page_chart"
            rings_ready = "page_rings" in state.get("hydrated_pages", set()) or state.get("current_page") == "page_rings"

            if chart_ready:
                await send_topic(ws, "ui/update", {"id": "chart_spectrum", "values": build_spectrum_values(p)})
                load = max(0, min(100, int(50 + 38 * math.sin(p * 0.9))))
                await send_topic(ws, "ui/update", {"id": "chart_progress", "value": load})
                await send_topic(ws, "ui/update", {"id": "chart_sub", "text": f"tick={state['tick']} load={load}% {now_hms()}"})

            if rings_ready:
                cpu = max(0, min(100, int(58 + 30 * math.sin(p * 0.82))))
                mem = max(0, min(100, int(50 + 22 * math.sin(p * 0.53 + 0.9))))
                net = max(0, min(100, int(40 + 44 * math.sin(p * 1.37 + 1.8))))
                await send_topic(ws, "ui/update", {"id": "ring_cpu", "value": cpu})
                await send_topic(ws, "ui/update", {"id": "ring_mem", "value": mem})
                await send_topic(ws, "ui/update", {"id": "ring_net", "value": net})
                await send_topic(ws, "ui/update", {"id": "ring_cpu_text", "text": f"CPU {cpu}%"})
                await send_topic(ws, "ui/update", {"id": "ring_mem_text", "text": f"MEM {mem}%"})
                await send_topic(ws, "ui/update", {"id": "ring_net_text", "text": f"NET {net}%"})
                await send_topic(ws, "ui/update", {"id": "ring_sub", "text": f"tick={state['tick']}  {now_hms()}"})
            await update_debug_page(state)
    except asyncio.CancelledError:
        pass
    finally:
        state["pump_task"] = None
        logging.info("[%s] pump stopped", device_id)


async def ensure_pump(device_id, state):
    task = state.get("pump_task")
    if task and not task.done():
        return
    state["pump_task"] = asyncio.create_task(pump(device_id, state))


async def stop_pump(state):
    task = state.get("pump_task")
    if task and not task.done():
        task.cancel()
        try:
            await task
        except asyncio.CancelledError:
            pass
    state["pump_task"] = None


async def update_status(state):
    scene_label = SCENES[state["scene"]]["label"]
    await send_topic(
        state["ws"],
        "ui/update",
        {"id": "status", "text": f"theme={state['theme']} scene={scene_label} pace={state['pace_sec']:.2f}s"},
    )


async def handle_topic(device_id, state, topic, payload):
    if topic == "sys/ping":
        await send_topic(
            state["ws"],
            "sys/pong",
            {"uptime": int(time.time() - START_TIME), "status": "online"},
        )
        return

    if topic == "telemetry/heartbeat":
        return

    if topic == "ui/page_changed":
        page = (payload or {}).get("page")
        if isinstance(page, str) and page in PAGE_IDS:
            state["current_page"] = page
            state["hydrated_pages"].add(page)
            await flush_deferred_for_page(state, page)
            await update_debug_page(state)
        return

    if topic == "ui/switch_theme":
        state["theme"] = cycle(state["theme"], THEME_ORDER)
        await apply_theme_scene_runtime(state)
        await update_debug_page(state)
        return

    if topic == "ui/switch_scene":
        state["scene"] = cycle(state["scene"], SCENE_ORDER)
        await apply_theme_scene_runtime(state)
        await update_debug_page(state)
        return

    if topic == "ui/pace_faster":
        state["pace_sec"] = max(0.22, state["pace_sec"] - 0.08)
        await update_status(state)
        await update_debug_page(state)
        return

    if topic == "ui/pace_slower":
        state["pace_sec"] = min(1.10, state["pace_sec"] + 0.08)
        await update_status(state)
        await update_debug_page(state)
        return

    if topic == "ui/font_demo_action":
        await send_topic(state["ws"], "ui/update", {"id": "preview_hint", "text": f"font action @ {now_hms()}"})
        return


async def on_client(websocket):
    remote = websocket.remote_address
    device_id = None
    state = None
    logging.info("connected: %s", remote)
    try:
        async for message in websocket:
            try:
                data = json.loads(message)
            except Exception:
                continue

            incoming_device_id = data.get("device_id")
            if isinstance(incoming_device_id, str) and incoming_device_id:
                device_id = incoming_device_id
            if not device_id:
                continue

            state = get_or_create_device(device_id, websocket, remote)
            if not state.get("initialized"):
                state["initialized"] = True
                logging.info("[%s] first upstream frame -> send initial layout", device_id)
                layout = build_layout(state)
                log_text_risk(layout)
                await send_topic(state["ws"], "ui/layout", layout)
                await ensure_pump(device_id, state)

            topic = data.get("topic", "")
            payload = data.get("payload", {})
            await handle_topic(device_id, state, topic, payload)
    except websockets.ConnectionClosed:
        pass
    finally:
        if state and state.get("ws") is websocket:
            state["ws"] = None
            await stop_pump(state)
        logging.info("[%s] disconnected", device_id or "unknown")


async def main():
    if TEXT_RISK_CHECK_ONLY:
        state = make_state(None, "local")
        layout = build_layout(state)
        log_text_risk(layout)
        return

    async with websockets.serve(on_client, HOST, PORT):
        logging.info("ui2 template sample started at ws://%s:%d", HOST, PORT)
        await asyncio.Future()


if __name__ == "__main__":
    asyncio.run(main())
