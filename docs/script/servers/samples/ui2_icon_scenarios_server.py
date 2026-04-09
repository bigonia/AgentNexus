#!/usr/bin/env python3
from __future__ import annotations

import asyncio
import json
import logging
import os
import time
from copy import deepcopy
from typing import Any, Dict, List

import websockets
from websockets.exceptions import ConnectionClosed

logging.basicConfig(level=logging.INFO, format="%(asctime)s [%(levelname)s] %(message)s")
logging.getLogger("websockets").setLevel(logging.WARNING)

HOST = "0.0.0.0"
PORT = int(os.getenv("SDUI_SERVER_PORT", "8080"))
WS_PING_INTERVAL = float(os.getenv("SDUI_WS_PING_INTERVAL", "0"))
WS_PING_TIMEOUT = float(os.getenv("SDUI_WS_PING_TIMEOUT", "0"))
STAGED_ENABLED = str(os.getenv("SDUI_STAGED_LAYOUT", "1")).strip().lower() not in {"0", "false", "off", "no"}
STAGED_BATCH_SIZE = max(1, int(os.getenv("SDUI_STAGED_BATCH_SIZE", "4")))
STAGED_DELAY_MS = max(20, int(os.getenv("SDUI_STAGED_DELAY_MS", "120")))

# Use terminal-native icon names only (rendered by parser on device).
ICON_NAMES = [
    "heart",
    "triangle",
    "circle",
    "wifi",
    "check",
    "warn",
    "play",
    "pause",
    "star",
    "cross",
    "plus",
    "minus",
    "search",
    "lock",
    "home",
]
ICON_COLORS = ["#4DD7FF", "#A6FFD8", "#FFD971", "#FF8B8B", "#B6C8D9"]
ICON_SCALES = ["sm", "md", "lg", "xl"]


def icon_node(node_id: str, name: str, scale: str, color: str, align: str = "center", x: int = 0, y: int = 0) -> Dict[str, Any]:
    return {
        "type": "icon",
        "id": node_id,
        "name": name,
        "align": align,
        "x": x,
        "y": y,
        "icon_scale": scale,
        "icon_color": color,
    }


def new_state(ws: Any) -> Dict[str, Any]:
    return {
        "ws": ws,
        "rev": {},
        "page": "gallery",
        "hydrated": {"gallery"},
        "deferred": {},
        "staged_task": None,
        "icon_idx": 0,
        "color_idx": 0,
        "scale_idx": 3,
        "diag": {"o": 0, "p": 0, "t": 0, "reports": 0},
        "ack": 0,
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
    await send(
        st["ws"],
        "ui/update",
        {
            "page_id": page,
            "revision": rev(st, page),
            "transaction": True,
            "ops": ops,
        },
    )


async def send_ops_safe(st: Dict[str, Any], page: str, ops: List[Dict[str, Any]]) -> None:
    if page == st.get("page") or page in st.get("hydrated", set()):
        await send_ops(st, page, ops)
    else:
        st["deferred"].setdefault(page, []).append(ops)


async def flush_page(st: Dict[str, Any], page: str) -> None:
    queue = st["deferred"].pop(page, [])
    for ops in queue:
        await send_ops(st, page, ops)
        await asyncio.sleep(0.02)


def layout() -> Dict[str, Any]:
    return {
        "safe_pad": 20,
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
                "bg_color": "#08111D",
                "children": [
                    {"type": "container", "id": "bg_h", "w": "92%", "h": 2, "align": "center", "y": -12, "bg_color": "#4DD7FF", "bg_opa": 38},
                    {"type": "container", "id": "bg_v", "w": 2, "h": "74%", "align": "center", "bg_color": "#A6FFD8", "bg_opa": 28},
                ],
            },
            {
                "type": "viewport",
                "id": "icon_vp",
                "direction": "horizontal",
                "initial_page": 0,
                "pages": [
                    {
                        "id": "gallery",
                        "bg_opa": 0,
                        "children": [
                            {"type": "label", "id": "ga_t", "text": "Native Icon Gallery", "align": "top_mid", "y": 28, "font_size": 18, "text_color": "#E8F5FF"},
                            icon_node("ga_i0", "heart", "lg", "#4DD7FF", x=-88, y=-52),
                            icon_node("ga_i1", "triangle", "lg", "#A6FFD8", x=0, y=-52),
                            icon_node("ga_i2", "star", "lg", "#FFD971", x=88, y=-52),
                            icon_node("ga_i3", "check", "md", "#63E08A", x=-88, y=0),
                            icon_node("ga_i4", "search", "md", "#B6C8D9", x=0, y=0),
                            icon_node("ga_i5", "home", "md", "#FF8B8B", x=88, y=0),
                            icon_node("ga_i6", "play", "sm", "#8DB7FF", x=-88, y=52),
                            icon_node("ga_i7", "pause", "sm", "#C8A0FF", x=0, y=52),
                            icon_node("ga_i8", "cross", "sm", "#FFE08A", x=88, y=52),
                            {"type": "label", "id": "ga_h", "text": "terminal native icon renderer", "align": "bottom_mid", "y": -40, "font_size": 12, "text_color": "#8AAEC8", "w": "52%", "long_mode": "dot", "text_align": "center"},
                        ],
                    },
                    {
                        "id": "status",
                        "bg_opa": 0,
                        "children": [
                            {"type": "label", "id": "st_t", "text": "Status Icons", "align": "top_mid", "y": 28, "font_size": 18, "text_color": "#E8F5FF"},
                            {"type": "container", "id": "st_c0", "w": "78%", "h": 48, "align": "center", "y": -50, "radius": 12, "bg_opa": 0, "border_w": 1, "border_color": "#63E08A", "children": [
                                icon_node("st_i0", "check", "md", "#63E08A", align="left_mid", x=16),
                                {"type": "label", "id": "st_l0", "text": "Healthy pipeline", "align": "center", "x": 16, "font_size": 14, "text_color": "#E8F5FF", "w": "70%", "long_mode": "dot"},
                            ]},
                            {"type": "container", "id": "st_c1", "w": "78%", "h": 48, "align": "center", "y": 6, "radius": 12, "bg_opa": 0, "border_w": 1, "border_color": "#FFD971", "children": [
                                icon_node("st_i1", "warn", "md", "#FFD971", align="left_mid", x=16),
                                {"type": "label", "id": "st_l1", "text": "Queue is warming up", "align": "center", "x": 16, "font_size": 14, "text_color": "#E8F5FF", "w": "70%", "long_mode": "dot"},
                            ]},
                            {"type": "container", "id": "st_c2", "w": "78%", "h": 48, "align": "center", "y": 62, "radius": 12, "bg_opa": 0, "border_w": 1, "border_color": "#FF8B8B", "children": [
                                icon_node("st_i2", "wifi", "md", "#FF8B8B", align="left_mid", x=16),
                                {"type": "label", "id": "st_l2", "text": "Node timeout observed", "align": "center", "x": 16, "font_size": 14, "text_color": "#E8F5FF", "w": "70%", "long_mode": "dot"},
                            ]},
                        ],
                    },
                    {
                        "id": "controls",
                        "bg_opa": 0,
                        "children": [
                            {"type": "label", "id": "ct_t", "text": "Interactive Icon", "align": "top_mid", "y": 28, "font_size": 18, "text_color": "#E8F5FF"},
                            icon_node("ct_icon", "heart", "xl", "#4DD7FF", y=-18),
                            {"type": "label", "id": "ct_info", "text": "heart | xl | #4DD7FF", "align": "center", "y": 48, "font_size": 14, "text_color": "#A6FFD8", "w": "70%", "long_mode": "dot", "text_align": "center"},
                            {"type": "button", "id": "ct_b1", "text": "Next Icon", "w": 108, "h": 36, "align": "center", "x": -62, "y": 100, "on_click": "server://ui/click?action=next_icon"},
                            {"type": "button", "id": "ct_b2", "text": "Next Color", "w": 108, "h": 36, "align": "center", "x": 62, "y": 100, "on_click": "server://ui/click?action=next_color"},
                            {"type": "button", "id": "ct_b3", "text": "Next Size", "w": 108, "h": 36, "align": "center", "x": 0, "y": 142, "on_click": "server://ui/click?action=next_scale"},
                        ],
                    },
                    {
                        "id": "runtime",
                        "bg_opa": 0,
                        "children": [
                            {"type": "label", "id": "rt_t", "text": "Runtime", "align": "top_mid", "y": 28, "font_size": 18, "text_color": "#E8F5FF"},
                            {"type": "label", "id": "rt_l1", "text": "page=gallery", "align": "center", "y": -24, "font_size": 14, "text_color": "#8AAEC8"},
                            {"type": "label", "id": "rt_l2", "text": "diag o=0 p=0 t=0", "align": "center", "y": 2, "font_size": 14, "text_color": "#8AAEC8"},
                            {"type": "label", "id": "rt_l3", "text": "ack=0", "align": "center", "y": 28, "font_size": 14, "text_color": "#8AAEC8"},
                            {"type": "label", "id": "rt_l4", "text": "last=none", "align": "center", "y": 54, "font_size": 14, "text_color": "#8AAEC8"},
                        ],
                    },
                ],
            },
        ],
    }


def build_staged_plan(full_layout: Dict[str, Any]) -> Dict[str, Any]:
    bootstrap = deepcopy(full_layout)
    batches: Dict[str, List[List[Dict[str, Any]]]] = {}

    children = bootstrap.get("children")
    if not isinstance(children, list):
        return {"bootstrap": bootstrap, "batches": batches}

    viewport = None
    for n in children:
        if isinstance(n, dict) and str(n.get("type") or "") == "viewport":
            viewport = n
            break
    if not isinstance(viewport, dict):
        return {"bootstrap": bootstrap, "batches": batches}

    pages = viewport.get("pages")
    if not isinstance(pages, list):
        return {"bootstrap": bootstrap, "batches": batches}

    for page in pages:
        if not isinstance(page, dict):
            continue
        page_id = str(page.get("id") or "")
        page_children = page.get("children")
        if not page_id or not isinstance(page_children, list):
            continue

        if page_id == "gallery":
            keep_count = 5  # title + 3 icons + hint
        else:
            keep_count = 1  # keep title only

        keep = page_children[:keep_count]
        rest = page_children[keep_count:]
        page["children"] = keep
        if not rest:
            continue

        inserts = []
        for idx, node in enumerate(rest):
            inserts.append(
                {
                    "op": "insert",
                    "parent_id": page_id,
                    "index": keep_count + idx,
                    "node": node,
                }
            )

        page_batches: List[List[Dict[str, Any]]] = []
        for i in range(0, len(inserts), STAGED_BATCH_SIZE):
            page_batches.append(inserts[i : i + STAGED_BATCH_SIZE])
        batches[page_id] = page_batches

    return {"bootstrap": bootstrap, "batches": batches}


async def run_staged_inserts(st: Dict[str, Any], staged: Dict[str, Any]) -> None:
    batches = staged.get("batches")
    if not isinstance(batches, dict):
        return
    for page_id in ("gallery", "status", "controls", "runtime"):
        page_batches = batches.get(page_id, [])
        if not page_batches:
            continue
        for ops in page_batches:
            await send_ops_safe(st, page_id, ops)
            await asyncio.sleep(STAGED_DELAY_MS / 1000.0)


async def apply_runtime(st: Dict[str, Any]) -> None:
    d = st["diag"]
    await send_ops_safe(
        st,
        "runtime",
        [
            {"op": "set", "id": "rt_l1", "path": "text", "value": f"page={st['page']}"},
            {"op": "set", "id": "rt_l2", "path": "text", "value": f"diag o={d['o']} p={d['p']} t={d['t']}"},
            {"op": "set", "id": "rt_l3", "path": "text", "value": f"ack={st['ack']}"},
            {"op": "set", "id": "rt_l4", "path": "text", "value": f"last={time.strftime('%H:%M:%S')}"},
        ],
    )


async def apply_control_icon(st: Dict[str, Any]) -> None:
    name = ICON_NAMES[st["icon_idx"] % len(ICON_NAMES)]
    color = ICON_COLORS[st["color_idx"] % len(ICON_COLORS)]
    scale = ICON_SCALES[st["scale_idx"] % len(ICON_SCALES)]
    await send_ops_safe(
        st,
        "controls",
        [
            {
                "op": "replace",
                "id": "ct_icon",
                "node": icon_node("ct_icon", name, scale, color, y=-18),
            },
            {"op": "set", "id": "ct_info", "path": "text", "value": f"{name} | {scale} | {color}"},
        ],
    )


async def handle_message(st: Dict[str, Any], topic: str, payload: Dict[str, Any]) -> None:
    if topic == "ui/page_changed":
        p = str(payload.get("page") or payload.get("page_id") or "")
        if p:
            st["page"] = p
            st["hydrated"].add(p)
            await flush_page(st, p)
            await apply_runtime(st)
        return

    if topic == "ui/click":
        action = str(payload.get("action") or payload.get("id") or "")
        if action == "next_icon":
            st["icon_idx"] = (st["icon_idx"] + 1) % len(ICON_NAMES)
            await apply_control_icon(st)
        elif action == "next_color":
            st["color_idx"] = (st["color_idx"] + 1) % len(ICON_COLORS)
            await apply_control_icon(st)
        elif action == "next_scale":
            st["scale_idx"] = (st["scale_idx"] + 1) % len(ICON_SCALES)
            await apply_control_icon(st)
        await apply_runtime(st)
        return

    if topic == "ui/layout_diagnostics":
        s = payload.get("summary") if isinstance(payload.get("summary"), dict) else {}
        st["diag"]["o"] += int(s.get("overflow", 0) or 0)
        st["diag"]["p"] += int(s.get("overlap", 0) or 0)
        st["diag"]["t"] += int(s.get("text_overflow", 0) or 0)
        st["diag"]["reports"] += 1
        await apply_runtime(st)
        return

    if topic == "ui/downlink_ack":
        st["ack"] += 1
        await apply_runtime(st)
        return


async def connection_handler(ws: Any) -> None:
    remote = getattr(ws, "remote_address", None)
    st = new_state(ws)
    logging.info("client connected: %s", remote)
    full_layout = layout()
    if STAGED_ENABLED:
        staged = build_staged_plan(full_layout)
        await send(ws, "ui/layout", staged["bootstrap"])
        st["staged_task"] = asyncio.create_task(run_staged_inserts(st, staged))
        logging.info("staged layout enabled: batch_size=%d delay=%dms", STAGED_BATCH_SIZE, STAGED_DELAY_MS)
    else:
        await send(ws, "ui/layout", full_layout)
    await send(ws, "ui/features", {"effects": "lite", "layout_diagnostics": True, "request_state": True})
    await apply_control_icon(st)
    await apply_runtime(st)

    try:
        async for raw in ws:
            try:
                msg = json.loads(raw)
            except Exception:
                logging.warning("invalid json received")
                continue
            topic = str(msg.get("topic") or "")
            payload = msg.get("payload") if isinstance(msg.get("payload"), dict) else {}
            await handle_message(st, topic, payload)
    except ConnectionClosed:
        pass
    finally:
        task = st.get("staged_task")
        if task and not task.done():
            task.cancel()
        logging.info("client disconnected: %s", remote)


async def main() -> None:
    ping_interval = None if WS_PING_INTERVAL <= 0 else WS_PING_INTERVAL
    ping_timeout = None if WS_PING_TIMEOUT <= 0 else WS_PING_TIMEOUT
    async with websockets.serve(connection_handler, HOST, PORT, ping_interval=ping_interval, ping_timeout=ping_timeout):
        logging.info("ui2 icon scenarios server started on ws://%s:%s", HOST, PORT)
        await asyncio.Future()


if __name__ == "__main__":
    asyncio.run(main())
