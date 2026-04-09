#!/usr/bin/env python3
"""
Template-driven Host Info Swipe Server.

Purpose:
- Demonstrate UI generation from SimplePage + TemplatePack.
- Keep AI-facing input minimal (title/foot/rows), let engineering layer
  resolve IDs, data bindings, and action routes.
"""

from __future__ import annotations

import asyncio
import json
import logging
import os
import platform
import shutil
import socket
import sys
import time
from pathlib import Path
from typing import Any

import websockets

try:
    import psutil  # type: ignore
except Exception:
    psutil = None


logging.basicConfig(level=logging.INFO, format="%(asctime)s [%(levelname)s] %(message)s")
logging.getLogger("websockets").setLevel(logging.WARNING)

HOST = os.getenv("SDUI_HOST", "0.0.0.0")
PORT = int(os.getenv("SDUI_PORT", "8080"))
PUSH_INTERVAL_SEC = float(os.getenv("SDUI_INFO_PUSH_INTERVAL", "2.0"))
PAGE_IDS = ["overview", "cpu", "memory", "storage"]
BOOT_TS = time.time()
DEVICES: dict[str, dict[str, Any]] = {}


class _MEMORYSTATUSEX(__import__("ctypes").Structure):
    _fields_ = [
        ("dwLength", __import__("ctypes").c_ulong),
        ("dwMemoryLoad", __import__("ctypes").c_ulong),
        ("ullTotalPhys", __import__("ctypes").c_ulonglong),
        ("ullAvailPhys", __import__("ctypes").c_ulonglong),
        ("ullTotalPageFile", __import__("ctypes").c_ulonglong),
        ("ullAvailPageFile", __import__("ctypes").c_ulonglong),
        ("ullTotalVirtual", __import__("ctypes").c_ulonglong),
        ("ullAvailVirtual", __import__("ctypes").c_ulonglong),
        ("ullAvailExtendedVirtual", __import__("ctypes").c_ulonglong),
    ]


def _fmt_bytes(n: int) -> str:
    value = float(max(0, int(n)))
    units = ["B", "KB", "MB", "GB", "TB", "PB"]
    idx = 0
    while value >= 1024.0 and idx < len(units) - 1:
        value /= 1024.0
        idx += 1
    if idx == 0:
        return f"{int(value)} {units[idx]}"
    return f"{value:.1f} {units[idx]}"


def _fmt_duration(seconds: float) -> str:
    sec = int(max(0, seconds))
    d, sec = divmod(sec, 86400)
    h, sec = divmod(sec, 3600)
    m, s = divmod(sec, 60)
    if d > 0:
        return f"{d}d {h:02d}:{m:02d}:{s:02d}"
    return f"{h:02d}:{m:02d}:{s:02d}"


def _memory_stats() -> dict[str, Any]:
    if psutil:
        vm = psutil.virtual_memory()
        used = int(vm.total - vm.available)
        return {"total": int(vm.total), "available": int(vm.available), "used": used, "percent": int(round(vm.percent))}

    if sys.platform.startswith("win"):
        ctypes = __import__("ctypes")
        stat = _MEMORYSTATUSEX()
        stat.dwLength = ctypes.sizeof(_MEMORYSTATUSEX)
        if ctypes.windll.kernel32.GlobalMemoryStatusEx(ctypes.byref(stat)):
            used = int(stat.ullTotalPhys - stat.ullAvailPhys)
            pct = int(round((used / stat.ullTotalPhys) * 100.0)) if stat.ullTotalPhys > 0 else 0
            return {
                "total": int(stat.ullTotalPhys),
                "available": int(stat.ullAvailPhys),
                "used": used,
                "percent": pct,
            }
    return {"total": 0, "available": 0, "used": 0, "percent": 0}


def _disk_stats() -> dict[str, Any]:
    root = Path.cwd().anchor or "/"
    usage = shutil.disk_usage(root)
    used = int(usage.used)
    total = int(usage.total)
    pct = int(round((used / total) * 100.0)) if total > 0 else 0
    return {"mount": root, "total": total, "used": used, "free": int(usage.free), "percent": pct}


def _cpu_stats() -> dict[str, Any]:
    logical = os.cpu_count() or 0
    model = platform.processor() or platform.machine() or "unknown"
    cpu_pct = None
    per_cpu: list[int] = []
    freq_mhz = None
    if psutil:
        try:
            cpu_pct = float(psutil.cpu_percent(interval=None))
        except Exception:
            cpu_pct = None
        try:
            per_cpu = [int(round(x)) for x in psutil.cpu_percent(interval=None, percpu=True)]
        except Exception:
            per_cpu = []
        try:
            f = psutil.cpu_freq()
            if f and f.current:
                freq_mhz = float(f.current)
        except Exception:
            freq_mhz = None
    load = None
    if hasattr(os, "getloadavg"):
        try:
            load = tuple(float(x) for x in os.getloadavg())
        except Exception:
            load = None
    if cpu_pct is None and load and logical > 0:
        cpu_pct = max(0.0, min(100.0, (load[0] / logical) * 100.0))
    return {
        "model": model,
        "logical": logical,
        "cpu_percent": int(round(cpu_pct)) if cpu_pct is not None else 0,
        "per_cpu": per_cpu,
        "freq_mhz": freq_mhz,
        "load": load,
    }


def _net_stats() -> dict[str, Any]:
    if psutil:
        try:
            io = psutil.net_io_counters()
            if io:
                return {"sent": int(io.bytes_sent), "recv": int(io.bytes_recv)}
        except Exception:
            pass
    return {"sent": 0, "recv": 0}


def collect_snapshot() -> dict[str, Any]:
    now = time.time()
    return {
        "ts": now,
        "clock": time.strftime("%Y-%m-%d %H:%M:%S", time.localtime(now)),
        "uptime": int(now - BOOT_TS),
        "host": socket.gethostname(),
        "cpu": _cpu_stats(),
        "mem": _memory_stats(),
        "disk": _disk_stats(),
        "net": _net_stats(),
    }


def _make_state(ws: websockets.WebSocketServerProtocol, remote: Any) -> dict[str, Any]:
    return {
        "ws": ws,
        "addr": str(remote),
        "initialized": False,
        "current_page": PAGE_IDS[0],
        "initial_page": 0,
        "auto_refresh": True,
        "pump_task": None,
    }


def _simple_pages() -> dict[str, dict[str, Any]]:
    return {
        "overview": {
            "title": "Host Overview",
            "foot": "Swipe left/right for more pages",
            "rows": [
                [{"kind": "metric_bar", "label": "CPU"}, {"kind": "metric_bar", "label": "Memory"}],
                [{"kind": "metric_bar", "label": "Disk"}, {"kind": "metric_text", "label": "HostUptime"}],
                [{"kind": "action", "label": "Refresh"}, {"kind": "action", "label": "Auto Toggle"}],
            ],
        },
        "cpu": {
            "title": "CPU Details",
            "foot": "Updated",
            "rows": [[{"kind": "metric_bar", "label": "CPU"}], [{"kind": "metric_text", "label": "CPUSummary"}]],
        },
        "memory": {
            "title": "Memory Details",
            "foot": "Updated",
            "rows": [[{"kind": "metric_bar", "label": "Memory"}], [{"kind": "metric_text", "label": "MemorySummary"}]],
        },
        "storage": {
            "title": "Storage & Network",
            "foot": "Updated",
            "rows": [[{"kind": "metric_bar", "label": "Disk"}], [{"kind": "metric_text", "label": "StorageSummary"}]],
        },
    }


def _action_route(label: str) -> str:
    norm = label.strip().lower()
    if "refresh" in norm:
        return "server://sys/refresh"
    if "auto" in norm or "toggle" in norm:
        return "server://sys/toggle_auto"
    return "server://sys/noop"


def _metric_value(label: str, snap: dict[str, Any], state: dict[str, Any]) -> tuple[int, str]:
    if label == "CPU":
        v = int(snap["cpu"]["cpu_percent"])
        return v, f"CPU {v}%"
    if label == "Memory":
        v = int(snap["mem"]["percent"])
        return v, f"Memory {v}%"
    if label == "Disk":
        v = int(snap["disk"]["percent"])
        return v, f"Disk {v}%"
    if label == "HostUptime":
        return 0, f"{snap['host']} | up {_fmt_duration(snap['uptime'])}"
    if label == "CPUSummary":
        load = snap["cpu"]["load"]
        load_text = "n/a" if not load else f"{load[0]:.2f}/{load[1]:.2f}/{load[2]:.2f}"
        freq = snap["cpu"]["freq_mhz"]
        freq_text = "n/a" if not freq else f"{freq:.0f}MHz"
        text = f"Core:{snap['cpu']['logical']} Freq:{freq_text} Load:{load_text}"
        return 0, text
    if label == "MemorySummary":
        text = f"Used {_fmt_bytes(snap['mem']['used'])} / {_fmt_bytes(snap['mem']['total'])}"
        return 0, text
    if label == "StorageSummary":
        text = f"Disk {_fmt_bytes(snap['disk']['used'])}/{_fmt_bytes(snap['disk']['total'])} Net {_fmt_bytes(snap['net']['sent'])}/{_fmt_bytes(snap['net']['recv'])}"
        return 0, text
    return 0, label


def _compile_page(page_id: str, simple: dict[str, Any], snap: dict[str, Any], state: dict[str, Any]) -> dict[str, Any]:
    children: list[dict[str, Any]] = []
    title = str(simple.get("title") or page_id.title())
    foot = str(simple.get("foot") or "")
    rows = simple.get("rows", []) if isinstance(simple.get("rows"), list) else []

    children.append({"type": "label", "id": f"{page_id}_title", "text": title, "align": "top_mid", "y": 14, "font_size": 20, "text_color": "#E9F6FF"})
    if page_id == "overview":
        children.append({"type": "label", "id": f"{page_id}_clock", "text": snap["clock"], "align": "top_mid", "y": 42, "font_size": 14, "text_color": "#8FB6CC"})
    else:
        children.append({"type": "label", "id": f"{page_id}_clock", "text": f"Updated: {snap['clock']}", "align": "top_mid", "y": 42, "font_size": 14, "text_color": "#8FB6CC"})

    row_y = -88
    for ridx, row in enumerate(rows[:3]):
        if not isinstance(row, list):
            continue
        col_xs = [0] if len(row) <= 1 else ([-84, 84] if len(row) == 2 else [-112, 0, 112])
        for cidx, elem in enumerate(row[:3]):
            if not isinstance(elem, dict):
                continue
            kind = str(elem.get("kind") or "")
            label = str(elem.get("label") or "")
            x = col_xs[min(cidx, len(col_xs) - 1)]
            eid = f"{page_id}_{kind}_{ridx}_{cidx}"
            if kind == "metric_bar":
                val, txt = _metric_value(label, snap, state)
                children.append({"type": "label", "id": f"{eid}_t", "text": txt, "align": "center", "x": x, "y": row_y, "font_size": 14, "text_color": "#D8F4FF"})
                children.append({"type": "bar", "id": f"{eid}_b", "w": 144, "h": 12, "align": "center", "x": x, "y": row_y + 18, "min": 0, "max": 100, "value": val, "bg_color": "#123043", "indic_color": "#4CC9FF", "radius": 6})
            elif kind == "action":
                text = "Auto ON" if ("auto" in label.lower() and state.get("auto_refresh", True)) else label
                color = "#1E9E64" if "auto" in label.lower() else "#2D8ECC"
                children.append({"type": "button", "id": eid, "text": text, "w": 138, "h": 42, "align": "center", "x": x if len(row) > 1 else 0, "y": 126, "bg_color": color, "radius": 16, "on_click": _action_route(label)})
            else:
                _, txt = _metric_value(label, snap, state)
                children.append({"type": "label", "id": eid, "text": txt, "w": 420 if len(txt) > 36 else 340, "align": "center", "x": x, "y": row_y + 2, "font_size": 14, "text_color": "#A6C2D4", "long_mode": "scroll"})
        row_y += 54

    children.append({"type": "label", "id": f"{page_id}_foot", "text": foot, "align": "bottom_mid", "y": -16, "font_size": 13, "text_color": "#6B8CA3"})
    return {"type": "container", "id": f"page_{page_id}", "bg_opa": 0, "children": children}


def build_layout(state: dict[str, Any], snap: dict[str, Any]) -> dict[str, Any]:
    simple_pages = _simple_pages()
    pages = [_compile_page(pid, simple_pages[pid], snap, state) for pid in PAGE_IDS]
    return {
        "safe_pad": 12,
        "w": "full",
        "h": "full",
        "bg_color": "#07111B",
        "children": [
            {"type": "container", "id": "bg_layer", "w": "full", "h": "full", "align": "center", "bg_color": "#07111B", "bg_opa": 255, "pointer_events": "none"},
            {"type": "viewport", "id": "host_viewport", "w": "full", "h": "full", "initial_page": int(state.get("initial_page", 0)), "direction": "horizontal", "pages": pages},
            {
                "type": "label",
                "id": "global_page_tag",
                "text": f"{state.get('current_page', PAGE_IDS[0])} | auto={'on' if state.get('auto_refresh', True) else 'off'}",
                "align": "top_left",
                "x": 10,
                "y": 8,
                "font_size": 12,
                "text_color": "#7FA1B6",
                "z": 50,
                "pointer_events": "none",
            },
        ],
    }


async def _send(ws: websockets.WebSocketServerProtocol | None, topic: str, payload: dict[str, Any]) -> None:
    if ws:
        await ws.send(json.dumps({"topic": topic, "payload": payload}, ensure_ascii=False))


async def rebuild_layout(state: dict[str, Any], reason: str) -> None:
    snap = collect_snapshot()
    await _send(state.get("ws"), "ui/layout", build_layout(state, snap))
    logging.info("layout pushed: reason=%s page=%s", reason, state.get("current_page"))


async def periodic_pump(state: dict[str, Any]) -> None:
    try:
        while True:
            await asyncio.sleep(PUSH_INTERVAL_SEC)
            if not state.get("initialized"):
                continue
            if not state.get("auto_refresh", True):
                continue
            await rebuild_layout(state, "timer")
    except asyncio.CancelledError:
        return


async def handle_topic(state: dict[str, Any], topic: str, payload: dict[str, Any]) -> None:
    if topic == "ui/page_changed":
        page = payload.get("page")
        idx = payload.get("index")
        if page in PAGE_IDS:
            state["current_page"] = page
        if isinstance(idx, int) and 0 <= idx < len(PAGE_IDS):
            state["initial_page"] = idx
        await rebuild_layout(state, "page_changed")
        return
    if topic == "sys/refresh":
        await rebuild_layout(state, "manual_refresh")
        return
    if topic == "sys/toggle_auto":
        state["auto_refresh"] = not state.get("auto_refresh", True)
        await rebuild_layout(state, "toggle_auto")


async def sdui_handler(ws: websockets.WebSocketServerProtocol) -> None:
    remote = ws.remote_address
    device_id = None
    state: dict[str, Any] | None = None
    try:
        async for message in ws:
            data = json.loads(message)
            topic = str(data.get("topic") or "")
            payload = data.get("payload", {})
            if not isinstance(payload, dict):
                payload = {}
            device_id = str(data.get("device_id") or device_id or "").strip()
            if not device_id:
                continue
            if device_id not in DEVICES:
                DEVICES[device_id] = _make_state(ws, remote)
            state = DEVICES[device_id]
            state["ws"] = ws
            if not state.get("initialized"):
                state["initialized"] = True
                await rebuild_layout(state, "init")
                if not state.get("pump_task") or state["pump_task"].done():
                    state["pump_task"] = asyncio.create_task(periodic_pump(state))
            await handle_topic(state, topic, payload)
    except websockets.exceptions.ConnectionClosed:
        pass
    except Exception as exc:
        logging.exception("handler error: %r", exc)
    finally:
        if state and state.get("pump_task"):
            state["pump_task"].cancel()
            state["pump_task"] = None
            state["initialized"] = False
        logging.info("disconnected: %s", remote)


async def main() -> None:
    async with websockets.serve(
        sdui_handler,
        HOST,
        PORT,
        ping_interval=None,
        ping_timeout=None,
        close_timeout=60,
        max_size=2**20,
    ):
        logging.info("template host swipe server started on ws://%s:%d", HOST, PORT)
        await asyncio.Future()


if __name__ == "__main__":
    asyncio.run(main())
