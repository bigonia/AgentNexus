#!/usr/bin/env python3
from __future__ import annotations

import asyncio
import json
import logging
import os
import random
import time
from typing import Any, Dict, List

import websockets
from websockets.exceptions import ConnectionClosed

from protocol_caps_profile import derive_profile_from_caps, layout_tokens

logging.basicConfig(level=logging.INFO, format="%(asctime)s [%(levelname)s] %(message)s")
HOST = "0.0.0.0"
PORT = int(os.getenv("SDUI_SERVER_PORT", "8092"))
DEVICES: Dict[str, Dict[str, Any]] = {}
PAGES = ["table", "form", "runtime"]


def st_new(ws: Any) -> Dict[str, Any]:
    return {
        "ws": ws,
        "profile": {"screen_w": 466, "screen_h": 466, "shape": "round", "safe_pad": 20, "usable": 426},
        "page": "table",
        "hydrated": {"table"},
        "deferred": {},
        "rev": {},
        "diag": {"overflow": 0, "overlap": 0, "text_overflow": 0, "reports": 0},
        "env": "prod",
        "auto": True,
        "last_submit": "never",
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
    return {
        "safe_pad": tk["safe_pad"],
        "w": "full",
        "h": "full",
        "bg_opa": 0,
        "children": [
            {"type": "viewport", "id": "vp", "direction": "horizontal", "initial_page": 0, "bg_opa": 0, "pages": [
                {"id": "table", "bg_opa": 0, "children": [
                    {"type": "label", "id": "tb_t", "text": "Dense Data Table", "align": "top_mid", "y": tk["title_y"], "font_size": 18, "text_color": "#E8F5FF"},
                    {"type": "label", "id": "tb_h", "text": "NODE   CPU MEM LAT STATUS", "align": "center", "y": -84, "font_size": 14, "text_color": "#8AAEC8"},
                    {"type": "label", "id": "tb_r0", "text": "-", "align": "center", "y": -56, "font_size": 14, "text_color": "#8AAEC8"},
                    {"type": "label", "id": "tb_r1", "text": "-", "align": "center", "y": -28, "font_size": 14, "text_color": "#8AAEC8"},
                    {"type": "label", "id": "tb_r2", "text": "-", "align": "center", "y": 0, "font_size": 14, "text_color": "#8AAEC8"},
                    {"type": "label", "id": "tb_r3", "text": "-", "align": "center", "y": 28, "font_size": 14, "text_color": "#8AAEC8"},
                    {"type": "label", "id": "tb_r4", "text": "-", "align": "center", "y": 56, "font_size": 14, "text_color": "#8AAEC8"},
                    {"type": "label", "id": "tb_s", "text": "diag pending", "align": "bottom_mid", "y": -18, "font_size": 14, "text_color": "#8AAEC8"},
                ]},
                {"id": "form", "bg_opa": 0, "children": [
                    {"type": "label", "id": "fm_t", "text": "Config Form Stress", "align": "top_mid", "y": tk["title_y"], "font_size": 18, "text_color": "#E8F5FF"},
                    {"type": "label", "id": "fm_l1", "text": "env=prod", "align": "center", "y": -34, "font_size": 20, "text_color": "#4DD7FF"},
                    {"type": "label", "id": "fm_l2", "text": "auto=true", "align": "center", "y": -4, "font_size": 20, "text_color": "#A6FFD8"},
                    {"type": "button", "id": "fm_b1", "text": "Switch Env", "w": tk["btn_w"], "h": tk["btn_h"], "align": "center", "x": -int(tk["btn_w"] * 0.55), "y": 58, "bg_color": "#4DD7FF", "on_click": "server://ui/switch_env"},
                    {"type": "button", "id": "fm_b2", "text": "Toggle Auto", "w": tk["btn_w"], "h": tk["btn_h"], "align": "center", "x": int(tk["btn_w"] * 0.55), "y": 58, "bg_color": "#A6FFD8", "on_click": "server://ui/toggle_auto"},
                    {"type": "button", "id": "fm_b3", "text": "Submit", "w": tk["btn_w"] + 16, "h": tk["btn_h"], "align": "bottom_mid", "y": -18, "bg_color": "#FFD971", "on_click": "server://ui/submit"},
                    {"type": "label", "id": "fm_s", "text": "last=never", "align": "center", "y": 108, "font_size": 14, "text_color": "#8AAEC8", "long_mode": "dot", "w": "84%"},
                ]},
                {"id": "runtime", "bg_opa": 0, "children": [
                    {"type": "label", "id": "rt_t", "text": "Runtime", "align": "top_mid", "y": tk["title_y"], "font_size": 18, "text_color": "#E8F5FF"},
                    {"type": "label", "id": "rt_l1", "text": "profile=round 466x466", "align": "center", "y": -24, "font_size": 14, "text_color": "#8AAEC8"},
                    {"type": "label", "id": "rt_l2", "text": "safe_pad=20", "align": "center", "y": 2, "font_size": 14, "text_color": "#8AAEC8"},
                    {"type": "label", "id": "rt_l3", "text": "diag o=0 p=0 t=0", "align": "center", "y": 28, "font_size": 14, "text_color": "#8AAEC8"},
                    {"type": "label", "id": "rt_l4", "text": "reports=0", "align": "center", "y": 54, "font_size": 14, "text_color": "#8AAEC8"},
                ]},
            ]}
        ],
    }


def line(i: int) -> str:
    cpu = random.randint(20, 95)
    mem = random.randint(20, 95)
    lat = random.randint(8, 220)
    st = "ok" if cpu < 80 and mem < 82 else ("warn" if cpu < 90 and mem < 90 else "bad")
    return f"n{i:02d}   {cpu:>3}% {mem:>3}% {lat:>3}ms {st:<5}"


async def refresh_runtime(st: Dict[str, Any]) -> None:
    p = st["profile"]
    d = st["diag"]
    await send_ops_safe(st, "runtime", [
        {"op": "set", "id": "rt_l1", "path": "text", "value": f"profile={p['shape']} {p['screen_w']}x{p['screen_h']}"},
        {"op": "set", "id": "rt_l2", "path": "text", "value": f"safe_pad={p['safe_pad']} usable={p['usable']}"},
        {"op": "set", "id": "rt_l3", "path": "text", "value": f"diag o={d['overflow']} p={d['overlap']} t={d['text_overflow']}"},
        {"op": "set", "id": "rt_l4", "path": "text", "value": f"reports={d['reports']}"},
    ])


async def pump(st: Dict[str, Any]) -> None:
    while st.get("ws") is not None:
        await asyncio.sleep(0.8)
        await send_ops_safe(st, "table", [
            {"op": "set", "id": "tb_r0", "path": "text", "value": line(1)},
            {"op": "set", "id": "tb_r1", "path": "text", "value": line(2)},
            {"op": "set", "id": "tb_r2", "path": "text", "value": line(3)},
            {"op": "set", "id": "tb_r3", "path": "text", "value": line(4)},
            {"op": "set", "id": "tb_r4", "path": "text", "value": line(5)},
            {"op": "set", "id": "tb_s", "path": "text", "value": f"diag o={st['diag']['overflow']} p={st['diag']['overlap']} t={st['diag']['text_overflow']}"},
        ])


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
        if p in PAGES:
            st["page"] = p
            st["hydrated"].add(p)
            await flush_page(st, p)
        return
    if topic == "ui/switch_env":
        st["env"] = "staging" if st["env"] == "prod" else "prod"
        await send_ops_safe(st, "form", [{"op": "set", "id": "fm_l1", "path": "text", "value": f"env={st['env']}"}])
        return
    if topic == "ui/toggle_auto":
        st["auto"] = not st["auto"]
        await send_ops_safe(st, "form", [{"op": "set", "id": "fm_l2", "path": "text", "value": f"auto={str(st['auto']).lower()}"}])
        return
    if topic == "ui/submit":
        st["last_submit"] = f"{time.strftime('%H:%M:%S')} env={st['env']} auto={str(st['auto']).lower()}"
        await send_ops_safe(st, "form", [{"op": "set", "id": "fm_s", "path": "text", "value": f"last={st['last_submit']}"}])
        return


async def on_client(ws: Any) -> None:
    did = f"anon-{abs(hash(str(ws.remote_address))) % 100000}"
    st = DEVICES.get(did)
    if st is None:
        st = st_new(ws)
        DEVICES[did] = st
    st["ws"] = ws
    st["page"] = "table"
    st["hydrated"] = {"table"}
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
        print(f"ui2 caps dense data server at ws://{HOST}:{PORT}")
        await asyncio.Future()


if __name__ == "__main__":
    asyncio.run(main())
