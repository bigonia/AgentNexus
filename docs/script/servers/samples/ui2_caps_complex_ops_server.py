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

from protocol_caps_profile import derive_profile_from_caps, layout_tokens

logging.basicConfig(level=logging.INFO, format="%(asctime)s [%(levelname)s] %(message)s")
HOST = "0.0.0.0"
PORT = int(os.getenv("SDUI_SERVER_PORT", "8080"))
START = time.time()
PAGES = ["overview", "rings", "charts", "alerts", "runtime"]
DEVICES: Dict[str, Dict[str, Any]] = {}


def st_new(ws: Any) -> Dict[str, Any]:
    return {
        "ws": ws,
        "profile": {"screen_w": 466, "screen_h": 466, "shape": "round", "safe_pad": 20, "usable": 426},
        "page": "overview",
        "hydrated": {"overview"},
        "deferred": {},
        "rev": {},
        "tick": 0,
        "phase": 0.0,
        "diag": {"overflow": 0, "overlap": 0, "text_overflow": 0, "reports": 0},
    }


def rev(st: Dict[str, Any], page: str) -> int:
    st["rev"][page] = int(st["rev"].get(page, 0)) + 1
    return st["rev"][page]


async def send(ws: Any, topic: str, payload: Dict[str, Any]) -> None:
    if not ws:
        return
    try:
        await ws.send(json.dumps({"topic": topic, "payload": payload}, ensure_ascii=False))
    except ConnectionClosed:
        return


async def send_ops(st: Dict[str, Any], page: str, ops: List[Dict[str, Any]]) -> None:
    await send(st["ws"], "ui/update", {"page_id": page, "revision": rev(st, page), "transaction": True, "ops": ops})


async def send_ops_safe(st: Dict[str, Any], page: str, ops: List[Dict[str, Any]]) -> None:
    if page == st.get("page") or page in st.get("hydrated", set()):
        await send_ops(st, page, ops)
    else:
        st["deferred"][page] = ops


async def flush_page(st: Dict[str, Any], page: str) -> None:
    ops = st["deferred"].pop(page, None)
    if ops:
        await send_ops(st, page, ops)


def layout(st: Dict[str, Any]) -> Dict[str, Any]:
    tk = layout_tokens(st["profile"])
    cw = tk["card_w"]
    ch = tk["card_h"]
    bw = tk["btn_w"]
    bh = tk["btn_h"]
    grid_x = int(cw * 0.56)
    grid_y_top = -46
    grid_y_bottom = 50
    return {
        "safe_pad": tk["safe_pad"],
        "w": "full",
        "h": "full",
        "bg_opa": 0,
        "children": [
            {
                "type": "viewport",
                "id": "vp_main",
                "direction": "horizontal",
                "initial_page": 0,
                "bg_opa": 0,
                "pages": [
                    {"id": "overview", "bg_opa": 0, "children": [
                        {"type": "label", "id": "ov_t", "text": "Ops Overview", "align": "top_mid", "y": tk["title_y"], "font_size": 18, "text_color": "#E8F5FF"},
                        {"type": "container", "id": "ov_c1", "w": cw, "h": ch, "align": "center", "x": -grid_x, "y": grid_y_top, "radius": 16, "bg_opa": 0, "border_w": 1, "border_color": "#4DD7FF", "children": [{"type": "label", "id": "ov_c1v", "text": "cpu 0%", "align": "center", "font_size": 20, "text_color": "#4DD7FF"}]},
                        {"type": "container", "id": "ov_c2", "w": cw, "h": ch, "align": "center", "x": grid_x, "y": grid_y_top, "radius": 16, "bg_opa": 0, "border_w": 1, "border_color": "#A6FFD8", "children": [{"type": "label", "id": "ov_c2v", "text": "mem 0%", "align": "center", "font_size": 20, "text_color": "#A6FFD8"}]},
                        {"type": "container", "id": "ov_c3", "w": cw, "h": ch, "align": "center", "x": -grid_x, "y": grid_y_bottom, "radius": 16, "bg_opa": 0, "border_w": 1, "border_color": "#FFD971", "children": [{"type": "label", "id": "ov_c3v", "text": "qps 0", "align": "center", "font_size": 20, "text_color": "#FFD971"}]},
                        {"type": "container", "id": "ov_c4", "w": cw, "h": ch, "align": "center", "x": grid_x, "y": grid_y_bottom, "radius": 16, "bg_opa": 0, "border_w": 1, "border_color": "#FF8B8B", "children": [{"type": "label", "id": "ov_c4v", "text": "err 0", "align": "center", "font_size": 20, "text_color": "#FF8B8B"}]},
                        {"type": "label", "id": "ov_h", "text": "swipe for rings/charts/alerts/runtime", "align": "bottom_mid", "y": -42, "font_size": 14, "text_color": "#8AAEC8", "w": "54%", "long_mode": "dot"},
                    ]},
                    {"id": "rings", "bg_opa": 0, "children": [
                        {"type": "widget", "widget_type": "dial", "id": "rg_a", "w": tk["ring_outer"], "h": tk["ring_outer"], "align": "center", "y": 0, "pointer_events": "none", "min": 0, "max": 100, "value": 65, "arc_width": 24, "ring_bg": "#243447", "ring_fg": "#FF6B6B", "start_angle": 0, "sweep_angle": 359, "value_format": " ", "layout_overflow_margin": 20},
                        {"type": "widget", "widget_type": "dial", "id": "rg_b", "w": tk["ring_mid"], "h": tk["ring_mid"], "align": "center", "y": 0, "pointer_events": "none", "min": 0, "max": 100, "value": 48, "arc_width": 20, "ring_bg": "#243447", "ring_fg": "#4FD1FF", "start_angle": 0, "sweep_angle": 359, "value_format": " "},
                        {"type": "widget", "widget_type": "dial", "id": "rg_c", "w": tk["ring_inner"], "h": tk["ring_inner"], "align": "center", "y": 0, "pointer_events": "none", "min": 0, "max": 100, "value": 72, "arc_width": 18, "ring_bg": "#243447", "ring_fg": "#7CFFB2", "start_angle": 0, "sweep_angle": 359, "value_format": " "},
                        {"type": "label", "id": "rg_cx", "text": "Server Usage", "align": "center", "y": -18, "font_size": 16, "text_color": "#E8F5FF"},
                        {"type": "label", "id": "rg_l1", "text": "CPU 65%", "align": "center", "y": 14, "font_size": 14, "text_color": "#FF6B6B"},
                        {"type": "label", "id": "rg_l2", "text": "MEM 48%", "align": "center", "y": 38, "font_size": 14, "text_color": "#4FD1FF"},
                        {"type": "label", "id": "rg_l3", "text": "NET 72%", "align": "center", "y": 62, "font_size": 14, "text_color": "#7CFFB2"},
                        {"type": "label", "id": "rg_sub", "text": "tick=0", "align": "center", "y": tk["ring_sub_y"], "font_size": 13, "text_color": "#8AAEC8"},
                        {"type": "label", "id": "rg_t", "text": "Concentric Rings", "align": "top_mid", "x": 0, "y": 24, "font_size": 17, "text_color": "#E8F5FF", "w": "42%", "text_align": "center", "long_mode": "dot"},
                    ]},
                    {"id": "charts", "bg_opa": 0, "children": [
                        {"type": "label", "id": "ch_t", "text": "Chart Mix", "align": "top_mid", "y": tk["title_y"], "font_size": 18, "text_color": "#E8F5FF"},
                        {"type": "widget", "widget_type": "spectrum", "id": "ch_sp", "w": "74%", "h": 78, "align": "center", "y": -56, "color": "#4DD7FF", "values": [38, 41, 47, 45, 52, 56, 54, 60, 58, 63, 68, 64]},
                        {"type": "label", "id": "ch_sp_l", "text": "traffic trend", "align": "center", "y": 6, "font_size": 13, "text_color": "#8AAEC8"},
                        {"type": "label", "id": "ch_cpu_l", "text": "CPU", "align": "center", "x": -108, "y": 40, "font_size": 13, "text_color": "#4DD7FF"},
                        {"type": "bar", "id": "ch_cpu_b", "w": 174, "h": 12, "align": "center", "x": 18, "y": 40, "min": 0, "max": 100, "value": 0, "bg_color": "#203246", "indic_color": "#4DD7FF", "radius": 10},
                        {"type": "label", "id": "ch_mem_l", "text": "MEM", "align": "center", "x": -108, "y": 68, "font_size": 13, "text_color": "#A6FFD8"},
                        {"type": "bar", "id": "ch_mem_b", "w": 174, "h": 12, "align": "center", "x": 18, "y": 68, "min": 0, "max": 100, "value": 0, "bg_color": "#203246", "indic_color": "#A6FFD8", "radius": 10},
                        {"type": "label", "id": "ch_net_l", "text": "NET", "align": "center", "x": -108, "y": 96, "font_size": 13, "text_color": "#FFD971"},
                        {"type": "bar", "id": "ch_net_b", "w": 174, "h": 12, "align": "center", "x": 18, "y": 96, "min": 0, "max": 100, "value": 0, "bg_color": "#203246", "indic_color": "#FFD971", "radius": 10},
                        {"type": "label", "id": "ch_sub", "text": "cpu=0 mem=0 net=0", "align": "bottom_mid", "x": 0, "y": -24, "font_size": 13, "text_color": "#8AAEC8", "w": "42%", "text_align": "center", "long_mode": "dot"},
                    ]},
                    {"id": "alerts", "bg_opa": 0, "children": [
                        {"type": "label", "id": "al_t", "text": "Alert Stress", "align": "top_mid", "y": tk["title_y"], "font_size": 18, "text_color": "#E8F5FF"},
                        {"type": "label", "id": "al_r0", "text": "warn  disk usage 78%  00:00:00", "align": "center", "y": tk["line_y_top"], "font_size": 14, "text_color": "#FFD971", "w": "78%", "long_mode": "dot"},
                        {"type": "label", "id": "al_r1", "text": "info  queue normal   00:00:00", "align": "center", "y": tk["line_y_top"] + tk["line_gap"], "font_size": 14, "text_color": "#8AAEC8", "w": "78%", "long_mode": "dot"},
                        {"type": "label", "id": "al_r2", "text": "crit  node03 timeout 00:00:00", "align": "center", "y": tk["line_y_top"] + tk["line_gap"] * 2, "font_size": 14, "text_color": "#FF8B8B", "w": "78%", "long_mode": "dot"},
                        {"type": "label", "id": "al_r3", "text": "info  retry success  00:00:00", "align": "center", "y": tk["line_y_top"] + tk["line_gap"] * 3, "font_size": 14, "text_color": "#8AAEC8", "w": "78%", "long_mode": "dot"},
                        {"type": "button", "id": "al_b1", "text": "Ack", "w": bw, "h": bh, "align": "center", "x": -int(bw * 0.55), "y": 112, "bg_color": "#4DD7FF", "on_click": "server://ui/ack"},
                        {"type": "button", "id": "al_b2", "text": "Mute", "w": bw, "h": bh, "align": "center", "x": int(bw * 0.55), "y": 112, "bg_color": "#A6FFD8", "on_click": "server://ui/mute"},
                    ]},
                    {"id": "runtime", "bg_opa": 0, "children": [
                        {"type": "label", "id": "rt_t", "text": "Runtime", "align": "top_mid", "y": tk["title_y"], "font_size": 18, "text_color": "#E8F5FF"},
                        {"type": "label", "id": "rt_l1", "text": "profile=round 466x466", "align": "center", "y": -24, "font_size": 14, "text_color": "#8AAEC8"},
                        {"type": "label", "id": "rt_l2", "text": "safe_pad=20", "align": "center", "y": 2, "font_size": 14, "text_color": "#8AAEC8"},
                        {"type": "label", "id": "rt_l3", "text": "diag o=0 p=0 t=0", "align": "center", "y": 28, "font_size": 14, "text_color": "#8AAEC8"},
                        {"type": "label", "id": "rt_l4", "text": "last=none", "align": "center", "y": 54, "font_size": 14, "text_color": "#8AAEC8"},
                    ]},
                ],
            }
        ],
    }


async def apply_runtime(st: Dict[str, Any]) -> None:
    p = st["profile"]
    d = st["diag"]
    await send_ops_safe(st, "runtime", [
        {"op": "set", "id": "rt_l1", "path": "text", "value": f"profile={p['shape']} {p['screen_w']}x{p['screen_h']}"},
        {"op": "set", "id": "rt_l2", "path": "text", "value": f"safe_pad={p['safe_pad']} usable={p['usable']}"},
        {"op": "set", "id": "rt_l3", "path": "text", "value": f"diag o={d['overflow']} p={d['overlap']} t={d['text_overflow']}"},
    ])


async def pump(device_id: str, st: Dict[str, Any]) -> None:
    while st.get("ws") is not None:
        await asyncio.sleep(0.7)
        st["tick"] += 1
        st["phase"] += 0.24
        p = st["phase"]
        cpu = max(0, min(100, int(58 + 30 * math.sin(p))))
        mem = max(0, min(100, int(50 + 24 * math.sin(p * 0.8 + 0.9))))
        net = max(0, min(100, int(44 + 34 * math.sin(p * 1.1 + 1.8))))
        qps = max(100, int(240 + 150 * (0.5 + 0.5 * math.sin(p))))
        err = max(0, int(3 + 3 * math.sin(p * 0.6 + 1.1)))
        await send_ops_safe(st, "overview", [
            {"op": "set", "id": "ov_c1v", "path": "text", "value": f"cpu {cpu}%"},
            {"op": "set", "id": "ov_c2v", "path": "text", "value": f"mem {mem}%"},
            {"op": "set", "id": "ov_c3v", "path": "text", "value": f"qps {qps}"},
            {"op": "set", "id": "ov_c4v", "path": "text", "value": f"err {err}"},
        ])
        await send_ops_safe(st, "rings", [
            {"op": "set", "id": "rg_a", "path": "value", "value": cpu},
            {"op": "set", "id": "rg_b", "path": "value", "value": mem},
            {"op": "set", "id": "rg_c", "path": "value", "value": net},
            {"op": "set", "id": "rg_l1", "path": "text", "value": f"CPU {cpu}%"},
            {"op": "set", "id": "rg_l2", "path": "text", "value": f"MEM {mem}%"},
            {"op": "set", "id": "rg_l3", "path": "text", "value": f"NET {net}%"},
            {"op": "set", "id": "rg_sub", "path": "text", "value": f"tick={st['tick']}"},
        ])
        await send_ops_safe(st, "charts", [
            {"op": "set", "id": "ch_cpu_b", "path": "value", "value": cpu},
            {"op": "set", "id": "ch_mem_b", "path": "value", "value": mem},
            {"op": "set", "id": "ch_net_b", "path": "value", "value": net},
            {"op": "set", "id": "ch_sub", "path": "text", "value": f"cpu={cpu} mem={mem} net={net}"},
        ])
        if st.get("page") == "charts" or "charts" in st.get("hydrated", set()):
            trend = [
                max(8, min(96, int(50 + 24 * math.sin(p * 0.8 + i * 0.5) + 10 * math.sin(p * 1.7 + i * 0.2))))
                for i in range(12)
            ]
            await send(st["ws"], "ui/update", {"id": "ch_sp", "values": trend})
        await send_ops_safe(st, "alerts", [
            {"op": "set", "id": "al_r0", "path": "text", "value": f"warn  disk usage {70 + (cpu % 20)}%  {time.strftime('%H:%M:%S')}"},
            {"op": "set", "id": "al_r2", "path": "text", "value": f"crit  node03 timeout {err}x {time.strftime('%H:%M:%S')}"},
        ])
        await send_ops_safe(st, "runtime", [{"op": "set", "id": "rt_l4", "path": "text", "value": f"last={time.strftime('%H:%M:%S')}"}])


async def handle(device_id: str, st: Dict[str, Any], topic: str, payload: Dict[str, Any]) -> None:
    if topic == "sys/ping":
        await send(st["ws"], "sys/pong", {"uptime": int(time.time() - START), "status": "online"})
        return
    if topic == "ui/capabilities" and isinstance(payload, dict):
        old = dict(st["profile"])
        st["profile"] = derive_profile_from_caps(payload)
        if old != st["profile"]:
            logging.info("[%s] profile changed => %s", device_id, st["profile"])
            await send(st["ws"], "ui/layout", layout(st))
            await apply_runtime(st)
        return
    if topic == "ui/page_changed":
        p = str(payload.get("page") or payload.get("page_id") or "")
        if p in PAGES:
            st["page"] = p
            st["hydrated"].add(p)
            await flush_page(st, p)
        return
    if topic == "ui/layout_diagnostics" and isinstance(payload, dict):
        s = payload.get("summary") if isinstance(payload.get("summary"), dict) else {}
        findings = payload.get("findings") if isinstance(payload.get("findings"), list) else []
        top_ids = []
        for item in findings[:4]:
            if isinstance(item, dict):
                top_ids.append(str(item.get("id") or "?"))
        st["diag"]["overflow"] += int(s.get("overflow", 0) or 0)
        st["diag"]["overlap"] += int(s.get("overlap", 0) or 0)
        st["diag"]["text_overflow"] += int(s.get("text_overflow", 0) or 0)
        st["diag"]["reports"] += 1
        logging.warning(
            "[%s][diag] reason=%s page=%s o=%s p=%s t=%s findings=%d ids=%s",
            device_id,
            payload.get("reason"),
            payload.get("page_id"),
            s.get("overflow", 0),
            s.get("overlap", 0),
            s.get("text_overflow", 0),
            len(findings),
            ",".join(top_ids) if top_ids else "-",
        )
        await apply_runtime(st)
        return
    if topic == "ui/ack":
        await send_ops_safe(st, "alerts", [{"op": "set", "id": "al_r3", "path": "text", "value": f"info  ack done       {time.strftime('%H:%M:%S')}"}])
        return
    if topic == "ui/mute":
        await send_ops_safe(st, "alerts", [{"op": "set", "id": "al_r1", "path": "text", "value": f"info  muted toggle   {time.strftime('%H:%M:%S')}"}])
        return


async def on_client(ws: Any) -> None:
    did = f"anon-{abs(hash(str(ws.remote_address))) % 100000}"
    st = DEVICES.get(did)
    if st is None:
        st = st_new(ws)
        DEVICES[did] = st
    else:
        st["ws"] = ws
    st["page"] = "overview"
    st["hydrated"] = {"overview"}
    st["deferred"] = {}
    await send(ws, "ui/layout", layout(st))
    await send(ws, "ui/features", {"layout_diagnostics": True, "request_state": True})
    await apply_runtime(st)
    task = asyncio.create_task(pump(did, st))
    try:
        async for raw in ws:
            try:
                msg = json.loads(raw)
            except Exception:
                continue
            await handle(did, st, str(msg.get("topic") or ""), msg.get("payload") if isinstance(msg.get("payload"), dict) else {})
    finally:
        st["ws"] = None
        task.cancel()


async def main() -> None:
    async with websockets.serve(on_client, HOST, PORT):
        print(f"ui2 caps complex ops server at ws://{HOST}:{PORT}")
        await asyncio.Future()


if __name__ == "__main__":
    asyncio.run(main())
