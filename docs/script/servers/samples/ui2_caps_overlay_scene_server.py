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
PORT = int(os.getenv("SDUI_SERVER_PORT", "8093"))
DEVICES: Dict[str, Dict[str, Any]] = {}


def st_new(ws: Any) -> Dict[str, Any]:
    return {
        "ws": ws,
        "profile": {"screen_w": 466, "screen_h": 466, "shape": "round", "safe_pad": 20, "usable": 426},
        "page": "scene_page",
        "hydrated": {"scene_page"},
        "deferred": {},
        "rev": {},
        "phase": 0.0,
        "overlay": False,
        "diag": {"overflow": 0, "overlap": 0, "text_overflow": 0, "reports": 0},
    }


def rev(st: Dict[str, Any], page: str) -> int:
    st["rev"][page] = int(st["rev"].get(page, 0)) + 1
    return st["rev"][page]


async def send(ws: Any, topic: str, payload: Dict[str, Any]) -> None:
    if ws:
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
    bw = tk["btn_w"]
    bh = tk["btn_h"]
    return {
        "safe_pad": tk["safe_pad"],
        "w": "full",
        "h": "full",
        "bg_opa": 0,
        "children": [
            {"type": "scene", "id": "scene", "w": "full", "h": "full", "bg_opa": 255, "bg_color": "#04070D", "children": [
                {"type": "container", "id": "glow_a", "w": 280, "h": 90, "align": "center", "x": -68, "y": -76, "radius": 999, "bg_color": "#4DD7FF", "bg_opa": 44},
                {"type": "container", "id": "glow_b", "w": 260, "h": 84, "align": "center", "x": 82, "y": 62, "radius": 999, "bg_color": "#A6FFD8", "bg_opa": 44},
            ]},
            {"type": "viewport", "id": "vp", "direction": "horizontal", "initial_page": 0, "bg_opa": 0, "pages": [
                {"id": "scene_page", "bg_opa": 0, "children": [
                    {"type": "label", "id": "sc_t", "text": "Overlay + Scene Stress", "align": "top_mid", "y": tk["title_y"], "font_size": 18, "text_color": "#E8F5FF"},
                    {"type": "label", "id": "sc_s", "text": "phase=0.00", "align": "center", "y": -20, "font_size": 20, "text_color": "#4DD7FF"},
                    {"type": "widget", "widget_type": "spectrum", "id": "sc_sp", "w": int(tk["card_w"] * 1.9), "h": 98, "align": "center", "y": 36, "canvas_w": int(tk["card_w"] * 1.9), "canvas_h": 98, "color": "#A6FFD8", "values": [32, 44, 52, 48, 40, 54, 62, 59, 46, 38, 42, 55]},
                    {"type": "button", "id": "sc_b1", "text": "Toggle Overlay", "w": bw + 20, "h": bh, "align": "bottom_mid", "y": -18, "bg_color": "#4DD7FF", "on_click": "server://ui/toggle_overlay"},
                ]},
                {"id": "runtime", "bg_opa": 0, "children": [
                    {"type": "label", "id": "rt_t", "text": "Runtime", "align": "top_mid", "y": tk["title_y"], "font_size": 18, "text_color": "#E8F5FF"},
                    {"type": "label", "id": "rt_l1", "text": "overlay=false", "align": "center", "y": -24, "font_size": 14, "text_color": "#8AAEC8"},
                    {"type": "label", "id": "rt_l2", "text": "safe_pad=20", "align": "center", "y": 2, "font_size": 14, "text_color": "#8AAEC8"},
                    {"type": "label", "id": "rt_l3", "text": "diag o=0 p=0 t=0", "align": "center", "y": 28, "font_size": 14, "text_color": "#8AAEC8"},
                ]},
            ]},
            {"type": "overlay", "id": "ov_panel", "hidden": not st["overlay"], "bg_opa": 0, "children": [
                {"type": "container", "id": "ov_card", "w": "84%", "h": "36%", "align": "center", "radius": 18, "bg_color": "#0F172A", "bg_opa": 240, "border_w": 1, "border_color": "#4DD7FF", "children": [
                    {"type": "label", "id": "ov_t", "text": "Overlay Panel", "align": "top_mid", "y": 12, "font_size": 18, "text_color": "#E8F5FF"},
                    {"type": "label", "id": "ov_s", "text": "diagnostic overlay active", "align": "center", "font_size": 14, "text_color": "#8AAEC8"},
                    {"type": "button", "id": "ov_c", "text": "Close", "w": 110, "h": 36, "align": "bottom_mid", "y": -12, "bg_color": "#4DD7FF", "on_click": "server://ui/toggle_overlay"},
                ]},
            ]},
        ],
    }


async def refresh_runtime(st: Dict[str, Any]) -> None:
    p = st["profile"]
    d = st["diag"]
    await send_ops_safe(st, "runtime", [
        {"op": "set", "id": "rt_l1", "path": "text", "value": f"overlay={str(st['overlay']).lower()}"},
        {"op": "set", "id": "rt_l2", "path": "text", "value": f"safe_pad={p['safe_pad']} shape={p['shape']}"},
        {"op": "set", "id": "rt_l3", "path": "text", "value": f"diag o={d['overflow']} p={d['overlap']} t={d['text_overflow']}"},
    ])


async def pump(st: Dict[str, Any]) -> None:
    while st.get("ws") is not None:
        await asyncio.sleep(0.6)
        st["phase"] += 0.22
        p = st["phase"]
        opa_a = int(24 + 30 * (0.5 + 0.5 * math.sin(p)))
        opa_b = int(24 + 30 * (0.5 + 0.5 * math.cos(p)))
        vals = [max(8, min(92, int(50 + 34 * math.sin(p + i * 0.4)))) for i in range(12)]
        await send(st["ws"], "ui/update", {"id": "glow_a", "bg_opa": opa_a})
        await send(st["ws"], "ui/update", {"id": "glow_b", "bg_opa": opa_b})
        await send(st["ws"], "ui/update", {"id": "sc_sp", "values": vals})
        await send_ops_safe(st, "scene_page", [{"op": "set", "id": "sc_s", "path": "text", "value": f"phase={st['phase']:.2f}"}])


async def handle(st: Dict[str, Any], topic: str, payload: Dict[str, Any]) -> None:
    if topic == "ui/capabilities" and isinstance(payload, dict):
        old = dict(st["profile"])
        st["profile"] = derive_profile_from_caps(payload)
        if old != st["profile"]:
            await send(st["ws"], "ui/layout", layout(st))
            await refresh_runtime(st)
        return
    if topic == "ui/layout_diagnostics" and isinstance(payload, dict):
        s = payload.get("summary") if isinstance(payload.get("summary"), dict) else {}
        st["diag"]["overflow"] += int(s.get("overflow", 0) or 0)
        st["diag"]["overlap"] += int(s.get("overlap", 0) or 0)
        st["diag"]["text_overflow"] += int(s.get("text_overflow", 0) or 0)
        st["diag"]["reports"] += 1
        logging.warning(
            "[diag] reason=%s page=%s o=%s p=%s t=%s findings=%d",
            payload.get("reason"),
            payload.get("page_id"),
            s.get("overflow", 0),
            s.get("overlap", 0),
            s.get("text_overflow", 0),
            len(payload.get("findings") if isinstance(payload.get("findings"), list) else []),
        )
        await refresh_runtime(st)
        return
    if topic == "ui/page_changed":
        p = str(payload.get("page") or payload.get("page_id") or "")
        if p in {"scene_page", "runtime"}:
            st["page"] = p
            st["hydrated"].add(p)
            await flush_page(st, p)
        return
    if topic == "ui/toggle_overlay":
        st["overlay"] = not st["overlay"]
        await send_ops_safe(st, "scene_page", [{"op": "set", "id": "sc_s", "path": "text", "value": f"phase={st['phase']:.2f} overlay={str(st['overlay']).lower()}"}])
        await send(st["ws"], "ui/update", {"id": "ov_panel", "hidden": not st["overlay"]})
        await refresh_runtime(st)
        return


async def on_client(ws: Any) -> None:
    did = f"anon-{abs(hash(str(ws.remote_address))) % 100000}"
    st = DEVICES.get(did)
    if st is None:
        st = st_new(ws)
        DEVICES[did] = st
    st["ws"] = ws
    st["page"] = "scene_page"
    st["hydrated"] = {"scene_page"}
    st["deferred"] = {}
    await send(ws, "ui/layout", layout(st))
    await send(ws, "ui/features", {"layout_diagnostics": True, "request_state": True})
    await refresh_runtime(st)
    task = asyncio.create_task(pump(st))
    try:
        async for raw in ws:
            try:
                msg = json.loads(raw)
            except Exception:
                continue
            await handle(st, str(msg.get("topic") or ""), msg.get("payload") if isinstance(msg.get("payload"), dict) else {})
    finally:
        st["ws"] = None
        task.cancel()


async def main() -> None:
    async with websockets.serve(on_client, HOST, PORT):
        print(f"ui2 caps overlay scene server at ws://{HOST}:{PORT}")
        await asyncio.Future()


if __name__ == "__main__":
    asyncio.run(main())
