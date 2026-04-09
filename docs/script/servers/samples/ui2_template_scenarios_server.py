#!/usr/bin/env python3
from __future__ import annotations

import asyncio
import json
import logging
import math
import os
import time
from typing import Any, Dict, List

import websockets
from websockets.exceptions import ConnectionClosed

logging.basicConfig(level=logging.INFO, format="%(asctime)s [%(levelname)s] %(message)s")
logging.getLogger("websockets").setLevel(logging.WARNING)

HOST = "0.0.0.0"
PORT = int(os.getenv("SDUI_SERVER_PORT", "8080"))
WS_PING_INTERVAL = float(os.getenv("SDUI_WS_PING_INTERVAL", "0"))
WS_PING_TIMEOUT = float(os.getenv("SDUI_WS_PING_TIMEOUT", "0"))
START = time.time()
PAGE_IDS = ["page_font_sizes", "page_font_xl", "page_interactive", "page_terminal_actions", "page_info", "page_viz", "page_cards", "page_data", "page_alerts", "page_tasks", "page_form", "page_theme", "page_control", "page_runtime"]
DEFAULT_PAGE_ID = "page_info"
INITIAL_PAGE_INDEX = PAGE_IDS.index(DEFAULT_PAGE_ID)
ACTIVE_ANIM_PAGES = {"page_viz", "page_theme", "page_control"}
THEME_ORDER = ["ice", "sunset"]
SCENE_ORDER = ["aurora", "grid", "orbit"]
DEVICES: Dict[str, Dict[str, Any]] = {}

THEMES = {
    "ice": {"root": "#04070D", "title": "#E8F5FF", "sub": "#8AAEC8", "a": "#4DD7FF", "a2": "#A6FFD8", "ok": "#63E08A", "warn": "#FFD971", "bad": "#FF8B8B"},
    "sunset": {"root": "#160A0B", "title": "#FFF0E2", "sub": "#D9AE98", "a": "#FFAA74", "a2": "#FFD48A", "ok": "#7BE598", "warn": "#FFD971", "bad": "#FF8B8B"},
}
SCENES = {
    "aurora": {"label": "Aurora", "aurora_opa": 255, "grid_opa": 0, "orbit_opa": 0},
    "grid": {"label": "Grid", "aurora_opa": 0, "grid_opa": 255, "orbit_opa": 0},
    "orbit": {"label": "Orbit", "aurora_opa": 0, "grid_opa": 0, "orbit_opa": 255},
}


def clamp(v: int, lo: int, hi: int) -> int:
    return max(lo, min(hi, int(v)))


def derive_profile_from_caps(caps: Dict[str, Any]) -> Dict[str, Any]:
    device_profile = caps.get("device_profile") if isinstance(caps.get("device_profile"), dict) else {}
    screen = device_profile.get("screen") if isinstance(device_profile.get("screen"), dict) else {}
    w = clamp(screen.get("w", 466) or 466, 200, 1024)
    h = clamp(screen.get("h", 466) or 466, 200, 1024)
    shape = str(screen.get("shape") or "round")
    safe_default = clamp(device_profile.get("safe_pad_default", 20) or 20, 0, 60)

    if shape == "round":
        recommended = clamp(int(min(w, h) * 0.045), 16, 26)
        safe_pad = clamp(max(recommended, int(safe_default * 0.6)), 16, 30)
    else:
        recommended = clamp(int(min(w, h) * 0.03), 8, 18)
        safe_pad = clamp(max(recommended, int(safe_default * 0.5)), 8, 24)

    return {"screen_w": w, "screen_h": h, "shape": shape, "safe_pad": safe_pad}


def cycle(v: str, xs: List[str]) -> str:
    return xs[(xs.index(v) + 1) % len(xs)] if v in xs else xs[0]


def now_hms() -> str:
    return time.strftime("%H:%M:%S")


def state(ws: Any, remote: Any) -> Dict[str, Any]:
    return {
        "ws": ws,
        "addr": str(remote),
        "init": False,
        "page": DEFAULT_PAGE_ID,
        "hydrated": {DEFAULT_PAGE_ID},
        "deferred": {},
        "rev": {},
        "theme": "ice",
        "scene": "grid",
        "tick": 0,
        "phase": 0.0,
        "pump": None,
        "pace": 0.9,
        "alerts_muted": False,
        "form_env": "prod",
        "form_auto": True,
        "demo_btn_count": 0,
        "demo_modal_open": False,
        "term_last_action": "idle",
        "term_action_count": 0,
        "diag_counts": {"overflow": 0, "overlap": 0, "text_overflow": 0, "reports": 0},
        "diag_last": "none",
        "diag_page": "_",
        "diag_reason": "_",
        "caps": {},
        "profile": {"screen_w": 466, "screen_h": 466, "shape": "round", "safe_pad": 20},
    }


def snap(p: float) -> Dict[str, Any]:
    nodes = []
    bad = 0
    for i in range(4):
        cpu = max(8, min(98, int(42 + 34 * math.sin(p + i * 0.6))))
        mem = max(12, min(98, int(48 + 28 * math.sin(p * 0.9 + i * 0.4 + 0.8))))
        net = max(4, min(98, int(40 + 36 * math.sin(p * 1.1 + i * 0.3 + 1.1))))
        st = "healthy"
        if cpu > 85 or mem > 88:
            st = "degraded"
            bad += 1
        elif cpu > 72 or mem > 76:
            st = "warning"
        nodes.append({"name": f"node-{i+1:02d}", "cpu": cpu, "mem": mem, "net": net, "st": st})
    cs = "healthy" if bad == 0 else ("warning" if bad == 1 else "degraded")
    return {"h": max(58, min(99, int(82 + 14 * math.sin(p)))), "cs": cs, "nodes": nodes, "alerts": bad, "spec": [n["cpu"] for n in nodes]}


def col(t: Dict[str, str], s: str) -> str:
    return t["ok"] if s == "healthy" else (t["warn"] if s == "warning" else (t["bad"] if s == "degraded" else t["sub"]))


def row(n: Dict[str, Any]) -> str:
    return f"{n['name']:<8} {n['cpu']:>3}% {n['mem']:>3}% {n['net']:>3}% {n['st']:<8}"


async def send(ws: Any, topic: str, payload: Dict[str, Any]) -> None:
    if ws:
        try:
            await ws.send(json.dumps({"topic": topic, "payload": payload}, ensure_ascii=False))
        except ConnectionClosed:
            return


def rev(st: Dict[str, Any], pid: str) -> int:
    st["rev"][pid] = int(st["rev"].get(pid, 0)) + 1
    return st["rev"][pid]


async def send_ops(st: Dict[str, Any], pid: str, ops: List[Dict[str, Any]]) -> None:
    await send(st["ws"], "ui/update", {"page_id": pid, "revision": rev(st, pid), "transaction": True, "ops": ops})


async def send_ops_safe(st: Dict[str, Any], pid: str, ops: List[Dict[str, Any]]) -> None:
    # Keep update pressure low: only push realtime updates to current page.
    if pid == st["page"]:
        await send_ops(st, pid, ops)
    else:
        st["deferred"][pid] = ops


async def flush_page(st: Dict[str, Any], pid: str) -> None:
    ops = st["deferred"].pop(pid, None)
    if ops:
        await send_ops(st, pid, ops)


def layout(st: Dict[str, Any]) -> Dict[str, Any]:
    t = THEMES[st["theme"]]
    s = SCENES[st["scene"]]
    p = st.get("profile", {"screen_w": 466, "screen_h": 466, "shape": "round", "safe_pad": 20})
    safe_pad = clamp(p.get("safe_pad", 20) or 20, 0, 60)
    usable = clamp(min(p.get("screen_w", 466), p.get("screen_h", 466)) - safe_pad * 2, 180, 520)
    ring_outer = clamp(int(usable * 0.92), 220, 460)
    ring_mid = clamp(int(usable * 0.83), 180, 414)
    ring_inner = clamp(int(usable * 0.74), 150, 368)
    ring_y = clamp(int(safe_pad * 0.35), -8, 12)
    ring_sub_y = clamp(int(usable * 0.34), 126, 178)
    return {
        "safe_pad": safe_pad,
        "w": "full",
        "h": "full",
        "bg_opa": 0,
        "children": [
            {
                "type": "scene",
                "id": "bg",
                "w": "full",
                "h": "full",
                "bg_opa": 255,
                "bg_color": t["root"],
                "children": [
                    {
                        "type": "container",
                        "id": "scene_aurora",
                        "w": "full",
                        "h": "full",
                        "bg_opa": 0,
                        "opa": s["aurora_opa"],
                        "children": [
                            {"type": "container", "id": "g1", "w": 260, "h": 80, "align": "center", "x": -70, "y": -70, "radius": 999, "bg_color": t["a"], "bg_opa": 44},
                            {"type": "container", "id": "g2", "w": 220, "h": 76, "align": "center", "x": 84, "y": 66, "radius": 999, "bg_color": t["a2"], "bg_opa": 40},
                        ],
                    },
                    {
                        "type": "container",
                        "id": "scene_grid",
                        "w": "full",
                        "h": "full",
                        "bg_opa": 0,
                        "opa": s["grid_opa"],
                        "children": [
                            {"type": "container", "id": "grid_h", "w": "92%", "h": 2, "align": "center", "y": -18, "bg_color": t["a"], "bg_opa": 34},
                            {"type": "container", "id": "grid_v", "w": 2, "h": "72%", "align": "center", "bg_color": t["a2"], "bg_opa": 34},
                        ],
                    },
                    {
                        "type": "container",
                        "id": "scene_orbit",
                        "w": "full",
                        "h": "full",
                        "bg_opa": 0,
                        "opa": s["orbit_opa"],
                        "children": [
                            {"type": "container", "id": "orbit_o", "w": 256, "h": 256, "align": "center", "radius": 999, "bg_opa": 0, "border_w": 2, "border_color": t["a"]},
                            {"type": "container", "id": "orbit_i", "w": 174, "h": 174, "align": "center", "radius": 999, "bg_opa": 0, "border_w": 2, "border_color": t["a2"]},
                            {"type": "container", "id": "orbit_a", "w": 14, "h": 14, "align": "center", "x": -128, "y": 0, "radius": 999, "bg_color": t["a"]},
                            {"type": "container", "id": "orbit_b", "w": 14, "h": 14, "align": "center", "x": 128, "y": 0, "radius": 999, "bg_color": t["a2"]},
                        ],
                    },
                ],
            },
            {
                "type": "viewport",
                "id": "main_viewport",
                "direction": "horizontal",
                "initial_page": INITIAL_PAGE_INDEX,
                "bg_opa": 0,
                "pages": [
                    {"id": "page_font_sizes", "bg_opa": 0, "children": [{"type": "label", "id": "fs_t", "text": "Font Size Baseline", "align": "top_mid", "y": 22, "font_size": 18, "text_color": t["title"]}, {"type": "label", "id": "fs_s", "text": "small 14", "align": "center", "y": -66, "font_size": 14, "text_color": t["sub"]}, {"type": "label", "id": "fs_m", "text": "medium 20", "align": "center", "y": -20, "font_size": 20, "text_color": t["a"]}, {"type": "label", "id": "fs_l", "text": "large 26", "align": "center", "y": 36, "font_size": 26, "text_color": t["a2"]}, {"type": "label", "id": "fs_h", "text": "Default small/medium/large test", "align": "center", "y": 112, "font_size": 14, "text_color": t["sub"]}]},
                    {"id": "page_font_xl", "bg_opa": 0, "children": [{"type": "label", "id": "fx_t", "text": "Pixel Font Size", "align": "top_mid", "y": 24, "font_size": 18, "text_color": t["title"]}, {"type": "label", "id": "fx_a", "text": "48px", "align": "center", "y": -28, "font_size": 48, "text_color": t["a"]}, {"type": "label", "id": "fx_b", "text": "42px", "align": "center", "y": 30, "font_size": 42, "text_color": t["a2"]}, {"type": "label", "id": "fx_h", "text": "font_size uses direct pixel value", "align": "center", "y": 108, "font_size": 14, "text_color": t["sub"]}]},
                    {"id": "page_interactive", "bg_opa": 0, "children": [{"type": "label", "id": "it_t", "text": "Interaction Playground", "align": "top_mid", "y": 34, "font_size": 18, "text_color": t["title"]}, {"type": "button", "id": "it_add", "text": "Add Button", "w": 126, "h": 38, "align": "center", "x": -70, "y": -90, "bg_color": t["a"], "on_click": "server://ui/demo_add_button"}, {"type": "button", "id": "it_modal", "text": "Show Popup", "w": 126, "h": 38, "align": "center", "x": 70, "y": -90, "bg_color": t["a2"], "on_click": "server://ui/demo_open_modal"}, {"type": "button", "id": "it_dyn1", "text": "dyn#1", "w": 134, "h": 34, "align": "center", "y": -34, "bg_color": "#334155", "text_color": "#FFFFFF", "opa": 0}, {"type": "button", "id": "it_dyn2", "text": "dyn#2", "w": 134, "h": 34, "align": "center", "y": 6, "bg_color": "#334155", "text_color": "#FFFFFF", "opa": 0}, {"type": "button", "id": "it_dyn3", "text": "dyn#3", "w": 134, "h": 34, "align": "center", "y": 46, "bg_color": "#334155", "text_color": "#FFFFFF", "opa": 0}, {"type": "label", "id": "it_stat", "text": "buttons=0 popup=off", "align": "center", "y": 112, "font_size": 14, "text_color": t["sub"]}, {"type": "container", "id": "it_popup", "w": 258, "h": 132, "align": "center", "y": 8, "radius": 16, "bg_color": "#0F172A", "bg_opa": 0, "border_w": 1, "border_color": t["a"], "children": [{"type": "label", "id": "it_popup_t", "text": "New Card Popup", "align": "top_mid", "y": 14, "font_size": 16, "text_color": t["title"]}, {"type": "label", "id": "it_popup_s", "text": "modal style card sample", "align": "center", "y": 4, "font_size": 14, "text_color": t["sub"]}, {"type": "button", "id": "it_close", "text": "Close", "w": 110, "h": 34, "align": "bottom_mid", "y": -14, "bg_color": t["a"], "on_click": "server://ui/demo_close_modal"}]}]},
                    {"id": "page_terminal_actions", "bg_opa": 0, "children": [{"type": "label", "id": "ta_t", "text": "Terminal Motion Control", "align": "top_mid", "y": 26, "font_size": 18, "text_color": t["title"]}, {"type": "button", "id": "ta_flip", "text": "Flip", "w": 112, "h": 40, "align": "center", "x": -86, "y": -54, "bg_color": t["a"], "on_click": "server://ui/term_flip"}, {"type": "button", "id": "ta_lift", "text": "Lift", "w": 112, "h": 40, "align": "center", "x": 86, "y": -54, "bg_color": t["a2"], "on_click": "server://ui/term_lift"}, {"type": "button", "id": "ta_shake", "text": "Shake", "w": 170, "h": 42, "align": "center", "y": 4, "bg_color": "#F97316", "text_color": "#FFFFFF", "on_click": "server://ui/term_shake"}, {"type": "container", "id": "ta_panel", "w": 286, "h": 116, "align": "center", "y": 114, "radius": 14, "bg_color": "#0F172A", "bg_opa": 210, "border_w": 1, "border_color": t["a"], "children": [{"type": "label", "id": "ta_s1", "text": "last=idle", "align": "top_mid", "y": 14, "font_size": 14, "text_color": t["sub"]}, {"type": "label", "id": "ta_s2", "text": "count=0", "align": "center", "y": -6, "font_size": 14, "text_color": t["sub"]}, {"type": "label", "id": "ta_s3", "text": "ready for motion events", "align": "bottom_mid", "y": -14, "font_size": 14, "text_color": t["sub"]}]}]},
                    {"id": "page_info", "bg_opa": 0, "children": [{"type": "label", "id": "i_t", "text": "Information Layout", "align": "top_mid", "y": 28, "font_size": 18, "text_color": t["title"]}, {"type": "label", "id": "i_s", "text": "Title / Text / Buttons / Contrast", "align": "top_mid", "y": 44, "font_size": 14, "text_color": t["sub"]}, {"type": "label", "id": "i_ok", "text": "Healthy text", "align": "center", "y": -20, "font_size": 14, "text_color": t["ok"]}, {"type": "label", "id": "i_wa", "text": "Warning text", "align": "center", "y": 6, "font_size": 14, "text_color": t["warn"]}, {"type": "label", "id": "i_bd", "text": "Degraded text", "align": "center", "y": 32, "font_size": 14, "text_color": t["bad"]}, {"type": "button", "id": "i_btn", "text": "Info Action", "w": 140, "h": 42, "align": "center", "y": 84, "bg_color": t["a"], "on_click": "server://ui/info_action"}, {"type": "label", "id": "i_stat", "text": "ready", "align": "bottom_mid", "y": -20, "font_size": 14, "text_color": t["sub"]}]},
                    {"id": "page_viz", "bg_opa": 0, "children": [{"type": "widget", "widget_type": "dial", "id": "v_cpu", "w": ring_outer, "h": ring_outer, "align": "center", "y": ring_y, "pointer_events": "none", "min": 0, "max": 100, "value": 62, "start_angle": 0, "sweep_angle": 359, "arc_width": 20, "ring_bg": "#233346", "ring_fg": "#FF6B6B", "value_format": " "}, {"type": "widget", "widget_type": "dial", "id": "v_mem", "w": ring_mid, "h": ring_mid, "align": "center", "y": ring_y, "pointer_events": "none", "min": 0, "max": 100, "value": 48, "start_angle": 0, "sweep_angle": 359, "arc_width": 20, "ring_bg": "#233346", "ring_fg": "#4FD1FF", "value_format": " "}, {"type": "widget", "widget_type": "dial", "id": "v_net", "w": ring_inner, "h": ring_inner, "align": "center", "y": ring_y, "pointer_events": "none", "min": 0, "max": 100, "value": 74, "start_angle": 0, "sweep_angle": 359, "arc_width": 20, "ring_bg": "#233346", "ring_fg": "#7CFFB2", "value_format": " "}, {"type": "label", "id": "v_t", "text": "Visualization", "align": "top_mid", "y": 16, "font_size": 18, "text_color": t["title"]}, {"type": "label", "id": "v_c", "text": "Server Usage", "align": "center", "y": -18, "font_size": 16, "text_color": t["title"]}, {"type": "label", "id": "v_cpu_t", "text": "CPU 62%", "align": "center", "y": 14, "font_size": 14, "text_color": "#FF6B6B"}, {"type": "label", "id": "v_mem_t", "text": "MEM 48%", "align": "center", "y": 38, "font_size": 14, "text_color": "#4FD1FF"}, {"type": "label", "id": "v_net_t", "text": "NET 74%", "align": "center", "y": 62, "font_size": 14, "text_color": "#7CFFB2"}, {"type": "label", "id": "v_sub", "text": "tick=0", "align": "center", "y": ring_sub_y, "font_size": 13, "text_color": t["sub"]}]},
                    {"id": "page_cards", "bg_opa": 0, "children": [{"type": "label", "id": "c_t", "text": "Card Regions", "align": "top_mid", "y": 24, "font_size": 18, "text_color": t["title"]}, {"type": "container", "id": "c_d", "w": 150, "h": 56, "align": "center", "x": -78, "y": -98, "radius": 14, "bg_color": "#2563EB", "bg_opa": 255, "children": [{"type": "label", "id": "c_d_t", "text": "NET I/O", "align": "center", "y": 0, "font_size": 14, "text_color": "#FFFFFF"}]}, {"type": "container", "id": "c_e", "w": 150, "h": 56, "align": "center", "x": 78, "y": -98, "radius": 14, "bg_color": "#16A34A", "bg_opa": 255, "children": [{"type": "label", "id": "c_e_t", "text": "QPS", "align": "center", "y": 0, "font_size": 14, "text_color": "#FFFFFF"}]}, {"type": "container", "id": "c_a", "w": 150, "h": 96, "align": "center", "x": -78, "y": -8, "radius": 18, "bg_color": t["a"], "bg_opa": 68, "border_w": 1, "border_color": t["a"], "children": [{"type": "label", "id": "c_a_t", "text": "CPU", "align": "top_mid", "y": 10, "font_size": 14, "text_color": "#DFF6FF"}, {"type": "label", "id": "c_a_v", "text": "0%", "align": "center", "y": 12, "font_size": 20, "text_color": "#FFFFFF"}]}, {"type": "container", "id": "c_b", "w": 150, "h": 96, "align": "center", "x": 78, "y": -8, "radius": 18, "bg_color": t["a2"], "bg_opa": 62, "border_w": 1, "border_color": t["a2"], "children": [{"type": "label", "id": "c_b_t", "text": "MEM", "align": "top_mid", "y": 10, "font_size": 14, "text_color": "#E9FFF2"}, {"type": "label", "id": "c_b_v", "text": "0%", "align": "center", "y": 12, "font_size": 20, "text_color": "#FFFFFF"}]}, {"type": "container", "id": "c_c", "w": 312, "h": 92, "align": "center", "y": 96, "radius": 18, "bg_opa": 0, "border_w": 1, "border_color": t["a"], "children": [{"type": "label", "id": "c_c_t", "text": "Cluster Health", "align": "top_mid", "y": 10, "font_size": 14, "text_color": t["sub"]}, {"type": "label", "id": "c_c_v", "text": "0", "align": "center", "y": 12, "font_size": 24, "text_color": t["title"]}]}, {"type": "label", "id": "c_hint", "text": "Five cards, mixed visual weight", "align": "center", "y": 176, "font_size": 14, "text_color": t["sub"]}]},
                    {"id": "page_data", "bg_opa": 0, "children": [{"type": "label", "id": "d_t", "text": "Data Interaction", "align": "top_mid", "y": 22, "font_size": 18, "text_color": t["title"]}, {"type": "label", "id": "d_h", "text": "NODE     CPU  MEM  NET  STATUS", "align": "center", "y": -72, "font_size": 14, "text_color": t["sub"]}, {"type": "label", "id": "d_r0", "text": "-", "align": "center", "y": -44, "font_size": 14, "text_color": t["sub"]}, {"type": "label", "id": "d_r1", "text": "-", "align": "center", "y": -16, "font_size": 14, "text_color": t["sub"]}, {"type": "label", "id": "d_r2", "text": "-", "align": "center", "y": 12, "font_size": 14, "text_color": t["sub"]}, {"type": "label", "id": "d_r3", "text": "-", "align": "center", "y": 40, "font_size": 14, "text_color": t["sub"]}, {"type": "label", "id": "d_a", "text": "alerts=0", "align": "center", "y": 78, "font_size": 14, "text_color": t["warn"]}, {"type": "button", "id": "d_b", "text": "Refresh Data", "w": 152, "h": 40, "align": "center", "y": 136, "bg_color": t["a"], "on_click": "server://ui/data_refresh"}]},
                    {"id": "page_alerts", "bg_opa": 0, "children": [{"type": "label", "id": "a_t", "text": "Alert Stream", "align": "top_mid", "y": 22, "font_size": 18, "text_color": t["title"]}, {"type": "label", "id": "a_h", "text": "LEVEL      MESSAGE              TIME", "align": "center", "y": -74, "font_size": 14, "text_color": t["sub"]}, {"type": "label", "id": "a_r0", "text": "-", "align": "center", "y": -46, "font_size": 14, "text_color": t["sub"]}, {"type": "label", "id": "a_r1", "text": "-", "align": "center", "y": -18, "font_size": 14, "text_color": t["sub"]}, {"type": "label", "id": "a_r2", "text": "-", "align": "center", "y": 10, "font_size": 14, "text_color": t["sub"]}, {"type": "label", "id": "a_r3", "text": "-", "align": "center", "y": 38, "font_size": 14, "text_color": t["sub"]}, {"type": "button", "id": "a_ack", "text": "Ack", "w": 110, "h": 38, "align": "center", "x": -64, "y": 132, "bg_color": t["a"], "on_click": "server://ui/alerts_ack"}, {"type": "button", "id": "a_mute", "text": "Mute", "w": 110, "h": 38, "align": "center", "x": 64, "y": 132, "bg_color": t["a2"], "on_click": "server://ui/alerts_mute"}]},
                    {"id": "page_tasks", "bg_opa": 0, "children": [{"type": "label", "id": "ts_t", "text": "Task Progress", "align": "top_mid", "y": 22, "font_size": 18, "text_color": t["title"]}, {"type": "label", "id": "ts_n0", "text": "sync-metrics", "align": "center", "y": -74, "font_size": 14, "text_color": t["sub"]}, {"type": "bar", "id": "ts_b0", "w": 250, "h": 12, "align": "center", "y": -54, "min": 0, "max": 100, "value": 0, "bg_color": "#203246", "indic_color": t["a"], "radius": 10}, {"type": "label", "id": "ts_n1", "text": "backup-shard", "align": "center", "y": -20, "font_size": 14, "text_color": t["sub"]}, {"type": "bar", "id": "ts_b1", "w": 250, "h": 12, "align": "center", "y": 0, "min": 0, "max": 100, "value": 0, "bg_color": "#203246", "indic_color": t["a2"], "radius": 10}, {"type": "label", "id": "ts_n2", "text": "rollup-index", "align": "center", "y": 34, "font_size": 14, "text_color": t["sub"]}, {"type": "bar", "id": "ts_b2", "w": 250, "h": 12, "align": "center", "y": 54, "min": 0, "max": 100, "value": 0, "bg_color": "#203246", "indic_color": t["a"], "radius": 10}, {"type": "label", "id": "ts_stat", "text": "pending", "align": "center", "y": 126, "font_size": 14, "text_color": t["sub"]}]},
                    {"id": "page_form", "bg_opa": 0, "children": [{"type": "label", "id": "f_t", "text": "Form Interaction", "align": "top_mid", "y": 28, "font_size": 18, "text_color": t["title"]}, {"type": "label", "id": "f_env", "text": "env=prod", "align": "center", "y": -6, "font_size": 20, "text_color": t["a"]}, {"type": "label", "id": "f_auto", "text": "auto=true", "align": "center", "y": 22, "font_size": 20, "text_color": t["a2"]}, {"type": "button", "id": "f_env_btn", "text": "Switch Env", "w": 132, "h": 40, "align": "center", "x": -72, "y": 72, "bg_color": t["a"], "on_click": "server://ui/form_env"}, {"type": "button", "id": "f_auto_btn", "text": "Toggle Auto", "w": 132, "h": 40, "align": "center", "x": 72, "y": 72, "bg_color": t["a2"], "on_click": "server://ui/form_auto"}, {"type": "button", "id": "f_submit", "text": "Submit", "w": 152, "h": 42, "align": "bottom_mid", "y": -14, "bg_color": t["a"], "on_click": "server://ui/form_submit"}, {"type": "label", "id": "f_stat", "text": "no submission", "align": "bottom_mid", "y": -56, "font_size": 14, "text_color": t["sub"]}]},
                    {"id": "page_theme", "bg_opa": 0, "children": [{"type": "label", "id": "t_t", "text": "Theme & Scene", "align": "top_mid", "y": 28, "font_size": 18, "text_color": t["title"]}, {"type": "label", "id": "t_l1", "text": f"theme={st['theme']}", "align": "center", "y": -12, "font_size": 20, "text_color": t["a"]}, {"type": "label", "id": "t_l2", "text": f"scene={SCENES[st['scene']]['label']}", "align": "center", "y": 16, "font_size": 20, "text_color": t["a2"]}, {"type": "label", "id": "t_h", "text": "Switch presets from control page", "align": "center", "y": 78, "font_size": 14, "text_color": t["sub"]}]},
                    {"id": "page_control", "bg_opa": 0, "children": [{"type": "label", "id": "k_t", "text": "Control & Debug", "align": "top_mid", "y": 28, "font_size": 18, "text_color": t["title"]}, {"type": "button", "id": "k_bt", "text": "Theme", "w": 130, "h": 42, "align": "center", "x": -74, "y": -28, "bg_color": t["a"], "on_click": "server://ui/switch_theme"}, {"type": "button", "id": "k_bs", "text": "Scene", "w": 130, "h": 42, "align": "center", "x": 74, "y": -28, "bg_color": t["a2"], "on_click": "server://ui/switch_scene"}, {"type": "button", "id": "k_bf", "text": "Faster", "w": 130, "h": 40, "align": "center", "x": -74, "y": 24, "bg_color": t["a"], "on_click": "server://ui/pace_faster"}, {"type": "button", "id": "k_bl", "text": "Slower", "w": 130, "h": 40, "align": "center", "x": 74, "y": 24, "bg_color": t["a2"], "on_click": "server://ui/pace_slower"}, {"type": "label", "id": "k_l1", "text": f"page={st['page']}", "align": "center", "y": 84, "font_size": 14, "text_color": t["sub"]}, {"type": "label", "id": "k_l2", "text": f"theme={st['theme']} scene={SCENES[st['scene']]['label']}", "align": "center", "y": 106, "font_size": 14, "text_color": t["sub"]}, {"type": "label", "id": "k_l3", "text": "tick=0 uptime=0", "align": "center", "y": 128, "font_size": 14, "text_color": t["sub"]}, {"type": "label", "id": "k_h", "text": "Swipe pages to inspect templates", "align": "bottom_mid", "y": -12, "font_size": 14, "text_color": t["sub"]}]},
                    {"id": "page_runtime", "bg_opa": 0, "children": [{"type": "label", "id": "r_t", "text": "Runtime State", "align": "top_mid", "y": 28, "font_size": 18, "text_color": t["title"]}, {"type": "label", "id": "r_l1", "text": "hydrated=1", "align": "center", "y": -22, "font_size": 14, "text_color": t["sub"]}, {"type": "label", "id": "r_l2", "text": "deferred=0", "align": "center", "y": 2, "font_size": 14, "text_color": t["sub"]}, {"type": "label", "id": "r_l3", "text": "rev(total)=0", "align": "center", "y": 26, "font_size": 14, "text_color": t["sub"]}, {"type": "label", "id": "r_l4", "text": "last=none", "align": "center", "y": 50, "font_size": 14, "text_color": t["sub"]}, {"type": "label", "id": "r_l5", "text": "mute=false", "align": "center", "y": 74, "font_size": 14, "text_color": t["sub"]}]},
                ],
            },
        ],
    }


async def apply_theme(st: Dict[str, Any]) -> None:
    t = THEMES[st["theme"]]
    s = SCENES[st["scene"]]
    await send(
        st["ws"],
        "ui/update",
        {
            "transaction": True,
            "ops": [
                {"op": "set", "id": "scene_aurora", "path": "opa", "value": s["aurora_opa"]},
                {"op": "set", "id": "scene_grid", "path": "opa", "value": s["grid_opa"]},
                {"op": "set", "id": "scene_orbit", "path": "opa", "value": s["orbit_opa"]},
                {"op": "set", "id": "bg", "path": "bg_color", "value": t["root"]},
                {"op": "set", "id": "g1", "path": "bg_color", "value": t["a"]},
                {"op": "set", "id": "g2", "path": "bg_color", "value": t["a2"]},
                {"op": "set", "id": "grid_h", "path": "bg_color", "value": t["a"]},
                {"op": "set", "id": "grid_v", "path": "bg_color", "value": t["a2"]},
                {"op": "set", "id": "orbit_o", "path": "border_color", "value": t["a"]},
                {"op": "set", "id": "orbit_i", "path": "border_color", "value": t["a2"]},
                {"op": "set", "id": "orbit_a", "path": "bg_color", "value": t["a"]},
                {"op": "set", "id": "orbit_b", "path": "bg_color", "value": t["a2"]},
            ],
        },
    )
    await send_ops_safe(st, "page_theme", [{"op": "set", "id": "t_l1", "path": "text", "value": f"theme={st['theme']}"}, {"op": "set", "id": "t_l1", "path": "text_color", "value": t["a"]}, {"op": "set", "id": "t_l2", "path": "text", "value": f"scene={SCENES[st['scene']]['label']}"}, {"op": "set", "id": "t_l2", "path": "text_color", "value": t["a2"]}])
    await send_ops_safe(st, "page_control", [{"op": "set", "id": "k_l2", "path": "text", "value": f"theme={st['theme']} scene={SCENES[st['scene']]['label']}"}, {"op": "set", "id": "k_bt", "path": "bg_color", "value": t["a"]}, {"op": "set", "id": "k_bs", "path": "bg_color", "value": t["a2"]}, {"op": "set", "id": "k_bf", "path": "bg_color", "value": t["a"]}, {"op": "set", "id": "k_bl", "path": "bg_color", "value": t["a2"]}])
    await send_ops_safe(st, "page_cards", [{"op": "set", "id": "c_a", "path": "bg_color", "value": t["a"]}, {"op": "set", "id": "c_a", "path": "border_color", "value": t["a"]}, {"op": "set", "id": "c_b", "path": "bg_color", "value": t["a2"]}, {"op": "set", "id": "c_b", "path": "border_color", "value": t["a2"]}])
    await send_ops_safe(st, "page_interactive", [{"op": "set", "id": "it_stat", "path": "text_color", "value": t["sub"]}, {"op": "set", "id": "it_popup_t", "path": "text_color", "value": t["title"]}, {"op": "set", "id": "it_popup_s", "path": "text_color", "value": t["sub"]}])
    await send_ops_safe(st, "page_interactive", [{"op": "set", "id": "it_add", "path": "bg_color", "value": t["a"]}, {"op": "set", "id": "it_modal", "path": "bg_color", "value": t["a2"]}, {"op": "set", "id": "it_close", "path": "bg_color", "value": t["a"]}, {"op": "set", "id": "it_popup", "path": "border_color", "value": t["a"]}])
    await send_ops_safe(st, "page_terminal_actions", [{"op": "set", "id": "ta_t", "path": "text_color", "value": t["title"]}, {"op": "set", "id": "ta_s1", "path": "text_color", "value": t["sub"]}, {"op": "set", "id": "ta_s2", "path": "text_color", "value": t["sub"]}, {"op": "set", "id": "ta_s3", "path": "text_color", "value": t["sub"]}])
    await send_ops_safe(st, "page_terminal_actions", [{"op": "set", "id": "ta_flip", "path": "bg_color", "value": t["a"]}, {"op": "set", "id": "ta_lift", "path": "bg_color", "value": t["a2"]}, {"op": "set", "id": "ta_panel", "path": "border_color", "value": t["a"]}])


async def apply_snapshot(st: Dict[str, Any], s: Dict[str, Any]) -> None:
    t = THEMES[st["theme"]]
    c = col(t, s["cs"])
    await send_ops_safe(st, "page_info", [{"op": "set", "id": "i_stat", "path": "text", "value": f"{s['cs']} alerts={s['alerts']} {now_hms()}"}, {"op": "set", "id": "i_stat", "path": "text_color", "value": c}])
    await send_ops_safe(st, "page_viz", [{"op": "set", "id": "v_cpu", "path": "value", "value": s["nodes"][0]["cpu"]}, {"op": "set", "id": "v_mem", "path": "value", "value": s["nodes"][1]["mem"]}, {"op": "set", "id": "v_net", "path": "value", "value": s["nodes"][2]["net"]}, {"op": "set", "id": "v_c", "path": "text", "value": "Server Usage"}, {"op": "set", "id": "v_cpu_t", "path": "text", "value": f"CPU {s['nodes'][0]['cpu']}%"}, {"op": "set", "id": "v_mem_t", "path": "text", "value": f"MEM {s['nodes'][1]['mem']}%"}, {"op": "set", "id": "v_net_t", "path": "text", "value": f"NET {s['nodes'][2]['net']}%"}, {"op": "set", "id": "v_sub", "path": "text", "value": f"tick={st['tick']} health={s['h']}"}])
    await send_ops_safe(st, "page_cards", [{"op": "set", "id": "c_a_v", "path": "text", "value": f"{s['nodes'][0]['cpu']}%"}, {"op": "set", "id": "c_b_v", "path": "text", "value": f"{s['nodes'][1]['mem']}%"}, {"op": "set", "id": "c_c_v", "path": "text", "value": str(s["h"])}])
    ops = [{"op": "set", "id": "d_a", "path": "text", "value": f"alerts={s['alerts']} cluster={s['cs']}"}, {"op": "set", "id": "d_a", "path": "text_color", "value": c}]
    for i, n in enumerate(s["nodes"]):
        ops.append({"op": "set", "id": f"d_r{i}", "path": "text", "value": row(n)})
        ops.append({"op": "set", "id": f"d_r{i}", "path": "text_color", "value": col(t, n["st"])})
    await send_ops_safe(st, "page_data", ops)
    alert_rows = [
        ("critical" if s["alerts"] > 1 else "warn", f"node_degraded={s['alerts']}", now_hms()),
        ("warn", f"cluster={s['cs']}", now_hms()),
        ("info", "normal telemetry", now_hms()),
        ("info", "queue stable", now_hms()),
    ]
    a_ops: List[Dict[str, Any]] = []
    for i, (lv, msg, ts) in enumerate(alert_rows):
        text = f"{lv:<10} {msg:<20} {ts}"
        a_ops.append({"op": "set", "id": f"a_r{i}", "path": "text", "value": text})
        a_ops.append({"op": "set", "id": f"a_r{i}", "path": "text_color", "value": col(t, "degraded" if lv == "critical" else ("warning" if lv == "warn" else "healthy"))})
    a_ops.append({"op": "set", "id": "a_mute", "path": "text", "value": "Unmute" if st["alerts_muted"] else "Mute"})
    await send_ops_safe(st, "page_alerts", a_ops)

    task_0 = max(0, min(100, int(45 + 40 * math.sin(st["phase"] * 0.8))))
    task_1 = max(0, min(100, int(35 + 50 * math.sin(st["phase"] * 0.6 + 1.1))))
    task_2 = max(0, min(100, int(75 + 20 * math.sin(st["phase"] * 0.5 + 2.3))))
    task_status = "done" if task_2 > 93 else "running"
    await send_ops_safe(st, "page_tasks", [
        {"op": "set", "id": "ts_b0", "path": "value", "value": task_0},
        {"op": "set", "id": "ts_b1", "path": "value", "value": task_1},
        {"op": "set", "id": "ts_b2", "path": "value", "value": task_2},
        {"op": "set", "id": "ts_stat", "path": "text", "value": f"{task_0}/{task_1}/{task_2} {task_status}"},
        {"op": "set", "id": "ts_stat", "path": "text_color", "value": col(t, task_status)},
    ])
    await send_ops_safe(st, "page_interactive", [{"op": "set", "id": "it_stat", "path": "text", "value": f"buttons={st['demo_btn_count']} popup={'on' if st['demo_modal_open'] else 'off'} tick={st['tick']}"}])
    await send_ops_safe(st, "page_terminal_actions", [{"op": "set", "id": "ta_s1", "path": "text", "value": f"last={st['term_last_action']}"}, {"op": "set", "id": "ta_s2", "path": "text", "value": f"count={st['term_action_count']}"}, {"op": "set", "id": "ta_s3", "path": "text", "value": f"updated={now_hms()}"}])

    await send_ops_safe(st, "page_runtime", [
        {"op": "set", "id": "r_l1", "path": "text", "value": f"hydrated={len(st['hydrated'])}"},
        {"op": "set", "id": "r_l2", "path": "text", "value": f"deferred={len(st['deferred'])}"},
        {"op": "set", "id": "r_l3", "path": "text", "value": f"rev(total)={sum(st['rev'].values())}"},
        {"op": "set", "id": "r_l4", "path": "text", "value": f"diag o={st['diag_counts']['overflow']} p={st['diag_counts']['overlap']} t={st['diag_counts']['text_overflow']}"},
        {"op": "set", "id": "r_l5", "path": "text", "value": f"mute={str(st['alerts_muted']).lower()} diag={st['diag_reason']}:{st['diag_page']} {st['diag_last']}"},
    ])
    await send_ops_safe(st, "page_control", [{"op": "set", "id": "k_l1", "path": "text", "value": f"page={st['page']}"}, {"op": "set", "id": "k_l3", "path": "text", "value": f"tick={st['tick']} uptime={int(time.time()-START)}"}])


async def pump(device_id: str, st: Dict[str, Any]) -> None:
    try:
        while True:
            await asyncio.sleep(st.get("pace", 0.9))
            if st.get("ws") is None:
                break
            st["tick"] += 1
            st["phase"] += 0.20
            p = st["phase"]
            if st["page"] in ACTIVE_ANIM_PAGES and (st["tick"] % 2 == 0):
                if st["scene"] == "aurora":
                    await send(st["ws"], "ui/update", {"id": "g1", "bg_opa": int(28 + 28 * (0.5 + 0.5 * math.sin(p)))})
                    await send(st["ws"], "ui/update", {"id": "g2", "bg_opa": int(28 + 28 * (0.5 + 0.5 * math.cos(p)))})
                elif st["scene"] == "grid":
                    pulse = int(16 + 26 * (0.5 + 0.5 * math.sin(p * 1.3)))
                    await send(st["ws"], "ui/update", {"id": "grid_h", "bg_opa": pulse})
                    await send(st["ws"], "ui/update", {"id": "grid_v", "bg_opa": pulse})
                else:
                    ax = int(128 * math.cos(p))
                    ay = int(128 * math.sin(p))
                    bx = int(128 * math.cos(p + math.pi))
                    by = int(128 * math.sin(p + math.pi))
                    await send(st["ws"], "ui/update", {"id": "orbit_a", "x": ax, "y": ay})
                    await send(st["ws"], "ui/update", {"id": "orbit_b", "x": bx, "y": by})
            if st["tick"] % 2 == 0:
                await apply_snapshot(st, snap(st["phase"]))
    except asyncio.CancelledError:
        pass
    finally:
        st["pump"] = None


async def handle(device_id: str, st: Dict[str, Any], topic: str, payload: Dict[str, Any]) -> None:
    if topic == "sys/ping":
        await send(st["ws"], "sys/pong", {"uptime": int(time.time() - START), "status": "online"})
        return
    if topic == "ui/capabilities":
        if isinstance(payload, dict):
            old_profile = dict(st.get("profile", {}))
            st["caps"] = payload
            st["profile"] = derive_profile_from_caps(payload)
            p = st["profile"]
            logging.info(
                "[%s][caps] screen=%dx%d shape=%s safe_pad=%d",
                device_id, p["screen_w"], p["screen_h"], p["shape"], p["safe_pad"],
            )
            if old_profile != st["profile"]:
                await send(st["ws"], "ui/layout", layout(st))
                await apply_snapshot(st, snap(st["phase"]))
                await apply_theme(st)
        return
    if topic == "ui/layout_diagnostics":
        summary = payload.get("summary") if isinstance(payload.get("summary"), dict) else {}
        overflow = int(summary.get("overflow", 0) or 0)
        overlap = int(summary.get("overlap", 0) or 0)
        text_overflow = int(summary.get("text_overflow", 0) or 0)
        st["diag_counts"]["overflow"] += max(0, overflow)
        st["diag_counts"]["overlap"] += max(0, overlap)
        st["diag_counts"]["text_overflow"] += max(0, text_overflow)
        st["diag_counts"]["reports"] += 1
        st["diag_reason"] = str(payload.get("reason") or "_")
        st["diag_page"] = str(payload.get("page_id") or "_")
        st["diag_last"] = now_hms()
        if overflow > 0 or overlap > 0 or text_overflow > 0:
            logging.warning(
                "[%s][layout_diag] reason=%s page=%s overflow=%d overlap=%d text=%d",
                device_id, st["diag_reason"], st["diag_page"], overflow, overlap, text_overflow,
            )
        await send_ops_safe(st, "page_runtime", [
            {"op": "set", "id": "r_l4", "path": "text", "value": f"diag o={st['diag_counts']['overflow']} p={st['diag_counts']['overlap']} t={st['diag_counts']['text_overflow']}"},
            {"op": "set", "id": "r_l5", "path": "text", "value": f"mute={str(st['alerts_muted']).lower()} diag={st['diag_reason']}:{st['diag_page']} {st['diag_last']}"},
        ])
        return
    if topic == "ui/page_changed":
        p = str(payload.get("page") or payload.get("page_id") or "")
        if p in PAGE_IDS:
            st["page"] = p
            st["hydrated"].add(p)
            await flush_page(st, p)
            await apply_snapshot(st, snap(st["phase"]))
        return
    if topic == "ui/switch_theme":
        st["theme"] = cycle(st["theme"], THEME_ORDER)
        await apply_theme(st)
        return
    if topic == "ui/switch_scene":
        st["scene"] = cycle(st["scene"], SCENE_ORDER)
        await apply_theme(st)
        return
    if topic == "ui/pace_faster":
        st["pace"] = max(0.50, float(st.get("pace", 0.9)) - 0.08)
        return
    if topic == "ui/pace_slower":
        st["pace"] = min(1.80, float(st.get("pace", 0.9)) + 0.08)
        return
    if topic == "ui/data_refresh":
        st["phase"] += 0.5
        st["tick"] += 1
        await apply_snapshot(st, snap(st["phase"]))
        return
    if topic == "ui/info_action":
        await send_ops_safe(st, "page_info", [{"op": "set", "id": "i_stat", "path": "text", "value": f"info action @ {now_hms()}"}])
        return
    if topic == "ui/alerts_ack":
        await send_ops_safe(st, "page_alerts", [{"op": "set", "id": "a_r0", "path": "text", "value": f"info       alerts acknowledged    {now_hms()}"}])
        return
    if topic == "ui/alerts_mute":
        st["alerts_muted"] = not st["alerts_muted"]
        await send_ops_safe(st, "page_alerts", [{"op": "set", "id": "a_mute", "path": "text", "value": "Unmute" if st["alerts_muted"] else "Mute"}])
        return
    if topic == "ui/form_env":
        st["form_env"] = "staging" if st["form_env"] == "prod" else "prod"
        await send_ops_safe(st, "page_form", [{"op": "set", "id": "f_env", "path": "text", "value": f"env={st['form_env']}"}])
        return
    if topic == "ui/form_auto":
        st["form_auto"] = not st["form_auto"]
        await send_ops_safe(st, "page_form", [{"op": "set", "id": "f_auto", "path": "text", "value": f"auto={str(st['form_auto']).lower()}"}])
        return
    if topic == "ui/form_submit":
        await send_ops_safe(st, "page_form", [{"op": "set", "id": "f_stat", "path": "text", "value": f"submitted env={st['form_env']} auto={str(st['form_auto']).lower()} @ {now_hms()}"}])
        return
    if topic == "ui/term_flip":
        st["term_last_action"] = "flip"
        st["term_action_count"] = int(st["term_action_count"]) + 1
        await send_ops_safe(st, "page_terminal_actions", [{"op": "set", "id": "ta_s1", "path": "text", "value": "last=flip"}, {"op": "set", "id": "ta_s2", "path": "text", "value": f"count={st['term_action_count']}"}, {"op": "set", "id": "ta_s3", "path": "text", "value": f"flip detected @ {now_hms()}"}])
        return
    if topic == "ui/term_lift":
        st["term_last_action"] = "lift"
        st["term_action_count"] = int(st["term_action_count"]) + 1
        await send_ops_safe(st, "page_terminal_actions", [{"op": "set", "id": "ta_s1", "path": "text", "value": "last=lift"}, {"op": "set", "id": "ta_s2", "path": "text", "value": f"count={st['term_action_count']}"}, {"op": "set", "id": "ta_s3", "path": "text", "value": f"lift detected @ {now_hms()}"}])
        return
    if topic == "ui/term_shake":
        st["term_last_action"] = "shake"
        st["term_action_count"] = int(st["term_action_count"]) + 1
        await send_ops_safe(st, "page_terminal_actions", [{"op": "set", "id": "ta_s1", "path": "text", "value": "last=shake"}, {"op": "set", "id": "ta_s2", "path": "text", "value": f"count={st['term_action_count']}"}, {"op": "set", "id": "ta_s3", "path": "text", "value": f"shake detected @ {now_hms()}"}])
        return
    if topic == "ui/demo_add_button":
        cnt = int(st.get("demo_btn_count", 0))
        if cnt < 3:
            cnt += 1
            st["demo_btn_count"] = cnt
            await send_ops_safe(st, "page_interactive", [{"op": "set", "id": f"it_dyn{cnt}", "path": "opa", "value": 255}, {"op": "set", "id": f"it_dyn{cnt}", "path": "text", "value": f"dyn#{cnt} @ {now_hms()}"}])
        await send_ops_safe(st, "page_interactive", [{"op": "set", "id": "it_stat", "path": "text", "value": f"buttons={st['demo_btn_count']} popup={'on' if st['demo_modal_open'] else 'off'}"}])
        return
    if topic == "ui/demo_open_modal":
        st["demo_modal_open"] = True
        await send_ops_safe(st, "page_interactive", [{"op": "set", "id": "it_popup", "path": "bg_opa", "value": 248}, {"op": "set", "id": "it_stat", "path": "text", "value": f"buttons={st['demo_btn_count']} popup=on"}])
        return
    if topic == "ui/demo_close_modal":
        st["demo_modal_open"] = False
        await send_ops_safe(st, "page_interactive", [{"op": "set", "id": "it_popup", "path": "bg_opa", "value": 0}, {"op": "set", "id": "it_stat", "path": "text", "value": f"buttons={st['demo_btn_count']} popup=off"}])
        return


async def on_client(ws: Any) -> None:
    remote = ws.remote_address
    did = f"anon-{abs(hash(str(remote))) % 100000}"
    st = DEVICES.get(did)
    if st is None:
        st = state(ws, remote)
        DEVICES[did] = st
    else:
        old_ws = st.get("ws")
        if old_ws is not ws:
            st["init"] = False
            st["theme"] = "ice"
            st["scene"] = "grid"
            st["page"] = DEFAULT_PAGE_ID
            st["hydrated"] = {DEFAULT_PAGE_ID}
            st["deferred"] = {}
            st["rev"] = {}
    st["ws"] = ws
    # Always bootstrap layout on each websocket connection.
    st["init"] = True
    st.setdefault("pace", 0.9)
    await send(ws, "ui/layout", layout(st))
    await send(ws, "ui/features", {"layout_diagnostics": True, "request_state": True})
    await apply_snapshot(st, snap(st["phase"]))
    await apply_theme(st)
    if st.get("pump") and not st["pump"].done():
        st["pump"].cancel()
    st["pump"] = asyncio.create_task(pump(did, st))
    try:
        async for raw in ws:
            try:
                msg = json.loads(raw)
            except Exception:
                continue
            if isinstance(msg.get("device_id"), str) and msg["device_id"] and msg["device_id"] != did:
                DEVICES[msg["device_id"]] = st
                DEVICES.pop(did, None)
                did = msg["device_id"]
            await handle(did, st, str(msg.get("topic") or ""), msg.get("payload") if isinstance(msg.get("payload"), dict) else {})
    except ConnectionClosed:
        logging.info("[%s] websocket closed", did)
    finally:
        if st.get("ws") is ws:
            st["ws"] = None
            if st.get("pump") and not st["pump"].done():
                st["pump"].cancel()


async def main() -> None:
    ping_interval = None if WS_PING_INTERVAL <= 0 else WS_PING_INTERVAL
    ping_timeout = None if WS_PING_TIMEOUT <= 0 else WS_PING_TIMEOUT
    async with websockets.serve(on_client, HOST, PORT, ping_interval=ping_interval, ping_timeout=ping_timeout):
        print(f"ui2 template scenarios server started at ws://{HOST}:{PORT}")
        await asyncio.Future()


if __name__ == "__main__":
    asyncio.run(main())
