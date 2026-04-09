#!/usr/bin/env python3
"""
Script summary:
- Purpose: host status dashboard with swipe pages for SDUI terminal.
- Scene: system inspection / "data + viewport" interaction demo.
- Transport: websocket server, sends ui/layout and periodic ui/update.
- Data source: local host metrics (psutil preferred, builtin fallback).
- Validation focus:
  1) swipe page changes are captured via ui/page_changed
  2) page-level partial updates are stable
  3) reconnect can recover layout and update stream
"""

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

import websockets

try:
    import psutil  # type: ignore
except Exception:  # pragma: no cover
    psutil = None


logging.basicConfig(level=logging.INFO, format="%(asctime)s [%(levelname)s] %(message)s")
logging.getLogger("websockets").setLevel(logging.WARNING)

HOST = os.getenv("SDUI_HOST", "0.0.0.0")
PORT = int(os.getenv("SDUI_PORT", "8080"))
PUSH_INTERVAL_SEC = float(os.getenv("SDUI_INFO_PUSH_INTERVAL", "2.0"))
PAGE_IDS = ["page_overview", "page_cpu", "page_memory", "page_storage"]
BOOT_TS = time.time()
DEVICES = {}


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


def format_bytes(n):
    value = float(max(0, int(n)))
    units = ["B", "KB", "MB", "GB", "TB", "PB"]
    idx = 0
    while value >= 1024.0 and idx < len(units) - 1:
        value /= 1024.0
        idx += 1
    if idx == 0:
        return f"{int(value)} {units[idx]}"
    return f"{value:.1f} {units[idx]}"


def format_duration(seconds):
    sec = int(max(0, seconds))
    d, sec = divmod(sec, 86400)
    h, sec = divmod(sec, 3600)
    m, s = divmod(sec, 60)
    if d > 0:
        return f"{d}d {h:02d}:{m:02d}:{s:02d}"
    return f"{h:02d}:{m:02d}:{s:02d}"


def get_memory_stats():
    if psutil:
        vm = psutil.virtual_memory()
        used = int(vm.total - vm.available)
        return {
            "total": int(vm.total),
            "available": int(vm.available),
            "used": used,
            "percent": int(round(vm.percent)),
        }

    if sys.platform.startswith("win"):
        ctypes = __import__("ctypes")
        stat = _MEMORYSTATUSEX()
        stat.dwLength = ctypes.sizeof(_MEMORYSTATUSEX)
        ok = ctypes.windll.kernel32.GlobalMemoryStatusEx(ctypes.byref(stat))
        if ok:
            used = int(stat.ullTotalPhys - stat.ullAvailPhys)
            pct = 0
            if stat.ullTotalPhys > 0:
                pct = int(round((used / stat.ullTotalPhys) * 100.0))
            return {
                "total": int(stat.ullTotalPhys),
                "available": int(stat.ullAvailPhys),
                "used": used,
                "percent": pct,
            }

    return {"total": 0, "available": 0, "used": 0, "percent": 0}


def get_disk_stats():
    root = Path.cwd().anchor or "/"
    usage = shutil.disk_usage(root)
    used = int(usage.used)
    total = int(usage.total)
    pct = int(round((used / total) * 100.0)) if total > 0 else 0
    return {
        "mount": root,
        "total": total,
        "used": used,
        "free": int(usage.free),
        "percent": pct,
    }


def get_cpu_stats():
    logical = os.cpu_count() or 0
    model = platform.processor() or platform.machine() or "unknown"
    cpu_pct = None
    per_cpu = []
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
            cf = psutil.cpu_freq()
            if cf and cf.current:
                freq_mhz = float(cf.current)
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


def get_network_stats():
    if psutil:
        try:
            io = psutil.net_io_counters()
            if io:
                return {
                    "sent": int(io.bytes_sent),
                    "recv": int(io.bytes_recv),
                }
        except Exception:
            pass
    return {"sent": 0, "recv": 0}


def collect_snapshot():
    now = time.time()
    mem = get_memory_stats()
    cpu = get_cpu_stats()
    disk = get_disk_stats()
    net = get_network_stats()

    return {
        "ts": now,
        "clock": time.strftime("%Y-%m-%d %H:%M:%S", time.localtime(now)),
        "uptime": int(now - BOOT_TS),
        "host": socket.gethostname(),
        "os": f"{platform.system()} {platform.release()}",
        "python": platform.python_version(),
        "cpu": cpu,
        "mem": mem,
        "disk": disk,
        "net": net,
    }


def make_state(websocket, remote):
    return {
        "ws": websocket,
        "addr": str(remote),
        "initialized": False,
        "current_page": PAGE_IDS[0],
        "initial_page": 0,
        "auto_refresh": True,
        "update_revisions": {},
        "pump_task": None,
    }


def get_or_create_device(device_id, websocket, remote):
    if device_id not in DEVICES:
        DEVICES[device_id] = make_state(websocket, remote)
    st = DEVICES[device_id]
    st["ws"] = websocket
    st["addr"] = str(remote)
    return st


async def send_topic(ws, topic, payload):
    if ws:
        await ws.send(json.dumps({"topic": topic, "payload": payload}, ensure_ascii=False))


def next_update_revision(state, page_id):
    revs = state.setdefault("update_revisions", {})
    revs[page_id] = int(revs.get(page_id, 0)) + 1
    return revs[page_id]


async def send_ui_ops_update(state, page_id, ops):
    payload = {
        "page_id": page_id,
        "revision": next_update_revision(state, page_id),
        "transaction": True,
        "ops": ops,
    }
    await send_topic(state.get("ws"), "ui/update", payload)


def build_overview_page(snap):
    cpu_pct = snap["cpu"]["cpu_percent"]
    mem_pct = snap["mem"]["percent"]
    disk_pct = snap["disk"]["percent"]
    return {
        "type": "container",
        "id": "page_overview",
        "bg_opa": 0,
        "children": [
            {"type": "label", "id": "ov_title", "text": "Host Overview", "align": "top_mid", "y": 14, "font_size": 20, "text_color": "#E9F6FF"},
            {"type": "label", "id": "ov_clock", "text": snap["clock"], "align": "top_mid", "y": 42, "font_size": 14, "text_color": "#8FB6CC"},
            {"type": "label", "id": "ov_cpu_text", "text": f"CPU {cpu_pct}%", "align": "center", "y": -108, "font_size": 15, "text_color": "#D8F4FF"},
            {"type": "bar", "id": "ov_cpu_bar", "w": 310, "h": 14, "align": "center", "y": -86, "min": 0, "max": 100, "value": cpu_pct, "bg_color": "#123043", "indic_color": "#4CC9FF", "radius": 7},
            {"type": "label", "id": "ov_mem_text", "text": f"Memory {mem_pct}%", "align": "center", "y": -52, "font_size": 15, "text_color": "#D8F4FF"},
            {"type": "bar", "id": "ov_mem_bar", "w": 310, "h": 14, "align": "center", "y": -30, "min": 0, "max": 100, "value": mem_pct, "bg_color": "#123043", "indic_color": "#74E09B", "radius": 7},
            {"type": "label", "id": "ov_disk_text", "text": f"Disk {disk_pct}%", "align": "center", "y": 4, "font_size": 15, "text_color": "#D8F4FF"},
            {"type": "bar", "id": "ov_disk_bar", "w": 310, "h": 14, "align": "center", "y": 26, "min": 0, "max": 100, "value": disk_pct, "bg_color": "#123043", "indic_color": "#F4BF63", "radius": 7},
            {"type": "label", "id": "ov_info_line", "text": f"{snap['host']} | up {format_duration(snap['uptime'])}", "align": "center", "y": 66, "font_size": 14, "text_color": "#9AB4C6"},
            {"type": "button", "id": "btn_refresh", "text": "Refresh", "w": 138, "h": 42, "align": "center", "x": -76, "y": 126, "bg_color": "#2D8ECC", "radius": 16, "on_click": "server://sys/refresh"},
            {"type": "button", "id": "btn_toggle_auto", "text": "Auto ON", "w": 138, "h": 42, "align": "center", "x": 76, "y": 126, "bg_color": "#1E9E64", "radius": 16, "on_click": "server://sys/toggle_auto"},
            {"type": "label", "id": "ov_hint", "text": "Swipe left/right for more pages", "align": "bottom_mid", "y": -16, "font_size": 13, "text_color": "#6B8CA3"},
        ],
    }


def build_cpu_page(snap):
    cpu = snap["cpu"]
    load = cpu["load"]
    load_text = "n/a"
    if load:
        load_text = f"{load[0]:.2f} / {load[1]:.2f} / {load[2]:.2f}"
    freq_text = f"{cpu['freq_mhz']:.0f} MHz" if cpu["freq_mhz"] else "n/a"
    core_preview = ", ".join(str(v) for v in cpu["per_cpu"][:8]) if cpu["per_cpu"] else "n/a"
    return {
        "type": "container",
        "id": "page_cpu",
        "bg_opa": 0,
        "children": [
            {"type": "label", "id": "cpu_title", "text": "CPU Details", "align": "top_mid", "y": 14, "font_size": 20, "text_color": "#E9F6FF"},
            {"type": "label", "id": "cpu_model", "text": f"Model: {cpu['model']}", "w": 420, "align": "top_mid", "y": 48, "font_size": 14, "text_color": "#B5D1E2", "long_mode": "scroll"},
            {"type": "label", "id": "cpu_usage_text", "text": f"CPU Usage: {cpu['cpu_percent']}%", "align": "center", "y": -96, "font_size": 16, "text_color": "#D8F4FF"},
            {"type": "bar", "id": "cpu_usage_bar", "w": 320, "h": 16, "align": "center", "y": -72, "min": 0, "max": 100, "value": cpu["cpu_percent"], "bg_color": "#123043", "indic_color": "#4CC9FF", "radius": 8},
            {"type": "label", "id": "cpu_cores", "text": f"Logical Cores: {cpu['logical']}", "align": "center", "y": -32, "font_size": 15, "text_color": "#B5D1E2"},
            {"type": "label", "id": "cpu_freq", "text": f"Frequency: {freq_text}", "align": "center", "y": -6, "font_size": 15, "text_color": "#B5D1E2"},
            {"type": "label", "id": "cpu_load", "text": f"Load(1/5/15): {load_text}", "align": "center", "y": 20, "font_size": 14, "text_color": "#9DBBCC"},
            {"type": "label", "id": "cpu_per_core", "text": f"Per-core(% first 8): {core_preview}", "w": 420, "align": "center", "y": 52, "font_size": 13, "text_color": "#8CAABD", "long_mode": "scroll"},
            {"type": "label", "id": "cpu_updated", "text": f"Updated: {snap['clock']}", "align": "bottom_mid", "y": -18, "font_size": 13, "text_color": "#6B8CA3"},
        ],
    }


def build_memory_page(snap):
    mem = snap["mem"]
    return {
        "type": "container",
        "id": "page_memory",
        "bg_opa": 0,
        "children": [
            {"type": "label", "id": "mem_title", "text": "Memory Details", "align": "top_mid", "y": 14, "font_size": 20, "text_color": "#E9F6FF"},
            {"type": "label", "id": "mem_usage_text", "text": f"Usage: {mem['percent']}%", "align": "center", "y": -100, "font_size": 16, "text_color": "#D8F4FF"},
            {"type": "bar", "id": "mem_usage_bar", "w": 320, "h": 16, "align": "center", "y": -76, "min": 0, "max": 100, "value": mem["percent"], "bg_color": "#123043", "indic_color": "#74E09B", "radius": 8},
            {"type": "label", "id": "mem_total", "text": f"Total: {format_bytes(mem['total'])}", "align": "center", "y": -34, "font_size": 15, "text_color": "#B5D1E2"},
            {"type": "label", "id": "mem_used", "text": f"Used: {format_bytes(mem['used'])}", "align": "center", "y": -8, "font_size": 15, "text_color": "#B5D1E2"},
            {"type": "label", "id": "mem_avail", "text": f"Available: {format_bytes(mem['available'])}", "align": "center", "y": 18, "font_size": 15, "text_color": "#B5D1E2"},
            {"type": "label", "id": "mem_ratio", "text": f"Used/Total: {format_bytes(mem['used'])} / {format_bytes(mem['total'])}", "align": "center", "y": 50, "font_size": 14, "text_color": "#9DBBCC"},
            {"type": "label", "id": "mem_updated", "text": f"Updated: {snap['clock']}", "align": "bottom_mid", "y": -18, "font_size": 13, "text_color": "#6B8CA3"},
        ],
    }


def build_storage_page(snap):
    disk = snap["disk"]
    net = snap["net"]
    return {
        "type": "container",
        "id": "page_storage",
        "bg_opa": 0,
        "children": [
            {"type": "label", "id": "st_title", "text": "Storage & Network", "align": "top_mid", "y": 14, "font_size": 20, "text_color": "#E9F6FF"},
            {"type": "label", "id": "st_mount", "text": f"Disk Mount: {disk['mount']}", "align": "top_mid", "y": 46, "font_size": 14, "text_color": "#B5D1E2"},
            {"type": "label", "id": "st_disk_text", "text": f"Disk Usage: {disk['percent']}%", "align": "center", "y": -88, "font_size": 16, "text_color": "#D8F4FF"},
            {"type": "bar", "id": "st_disk_bar", "w": 320, "h": 16, "align": "center", "y": -64, "min": 0, "max": 100, "value": disk["percent"], "bg_color": "#123043", "indic_color": "#F4BF63", "radius": 8},
            {"type": "label", "id": "st_disk_total", "text": f"Disk Total: {format_bytes(disk['total'])}", "align": "center", "y": -20, "font_size": 15, "text_color": "#B5D1E2"},
            {"type": "label", "id": "st_disk_used", "text": f"Disk Used: {format_bytes(disk['used'])}", "align": "center", "y": 6, "font_size": 15, "text_color": "#B5D1E2"},
            {"type": "label", "id": "st_disk_free", "text": f"Disk Free: {format_bytes(disk['free'])}", "align": "center", "y": 32, "font_size": 15, "text_color": "#B5D1E2"},
            {"type": "label", "id": "st_net", "text": f"Net TX/RX: {format_bytes(net['sent'])} / {format_bytes(net['recv'])}", "w": 420, "align": "center", "y": 60, "font_size": 13, "text_color": "#9DBBCC", "long_mode": "scroll"},
            {"type": "label", "id": "st_updated", "text": f"Updated: {snap['clock']}", "align": "bottom_mid", "y": -18, "font_size": 13, "text_color": "#6B8CA3"},
        ],
    }


def build_layout(state, snap):
    return {
        "safe_pad": 12,
        "w": "full",
        "h": "full",
        "bg_color": "#07111B",
        "children": [
            {
                "type": "container",
                "id": "bg_layer",
                "w": "full",
                "h": "full",
                "align": "center",
                "bg_color": "#07111B",
                "bg_opa": 255,
                "border_w": 0,
                "pointer_events": "none",
            },
            {
                "type": "viewport",
                "id": "host_viewport",
                "w": "full",
                "h": "full",
                "initial_page": state.get("initial_page", 0),
                "direction": "horizontal",
                "pages": [
                    build_overview_page(snap),
                    build_cpu_page(snap),
                    build_memory_page(snap),
                    build_storage_page(snap),
                ],
            },
            {
                "type": "label",
                "id": "global_page_tag",
                "text": f"{state.get('current_page', PAGE_IDS[0])} | auto={'on' if state.get('auto_refresh', True) else 'off'}",
                "align": "top_left",
                "x": 10,
                "y": 8,
                "font_size": 12,
                "text_color": "#7FA1B6",
                "pointer_events": "none",
                "z": 50,
            },
        ],
    }


def build_page_ops(state, snap):
    cpu = snap["cpu"]
    mem = snap["mem"]
    disk = snap["disk"]
    net = snap["net"]
    load = cpu["load"]
    load_text = "n/a"
    if load:
        load_text = f"{load[0]:.2f} / {load[1]:.2f} / {load[2]:.2f}"
    freq_text = f"{cpu['freq_mhz']:.0f} MHz" if cpu["freq_mhz"] else "n/a"
    core_preview = ", ".join(str(v) for v in cpu["per_cpu"][:8]) if cpu["per_cpu"] else "n/a"
    auto_text = "Auto ON" if state.get("auto_refresh", True) else "Auto OFF"
    auto_btn_color = "#1E9E64" if state.get("auto_refresh", True) else "#7A8A96"

    page_ops = {
        "page_overview": [
            {"op": "set", "id": "ov_clock", "path": "text", "value": snap["clock"]},
            {"op": "set", "id": "ov_cpu_text", "path": "text", "value": f"CPU {cpu['cpu_percent']}%"},
            {"op": "set", "id": "ov_cpu_bar", "path": "value", "value": cpu["cpu_percent"]},
            {"op": "set", "id": "ov_mem_text", "path": "text", "value": f"Memory {mem['percent']}%"},
            {"op": "set", "id": "ov_mem_bar", "path": "value", "value": mem["percent"]},
            {"op": "set", "id": "ov_disk_text", "path": "text", "value": f"Disk {disk['percent']}%"},
            {"op": "set", "id": "ov_disk_bar", "path": "value", "value": disk["percent"]},
            {"op": "set", "id": "ov_info_line", "path": "text", "value": f"{snap['host']} | up {format_duration(snap['uptime'])}"},
            {"op": "set", "id": "btn_toggle_auto", "path": "text", "value": auto_text},
            {"op": "set", "id": "btn_toggle_auto", "path": "bg_color", "value": auto_btn_color},
        ],
        "page_cpu": [
            {"op": "set", "id": "cpu_usage_text", "path": "text", "value": f"CPU Usage: {cpu['cpu_percent']}%"},
            {"op": "set", "id": "cpu_usage_bar", "path": "value", "value": cpu["cpu_percent"]},
            {"op": "set", "id": "cpu_model", "path": "text", "value": f"Model: {cpu['model']}"},
            {"op": "set", "id": "cpu_cores", "path": "text", "value": f"Logical Cores: {cpu['logical']}"},
            {"op": "set", "id": "cpu_freq", "path": "text", "value": f"Frequency: {freq_text}"},
            {"op": "set", "id": "cpu_load", "path": "text", "value": f"Load(1/5/15): {load_text}"},
            {"op": "set", "id": "cpu_per_core", "path": "text", "value": f"Per-core(% first 8): {core_preview}"},
            {"op": "set", "id": "cpu_updated", "path": "text", "value": f"Updated: {snap['clock']}"},
        ],
        "page_memory": [
            {"op": "set", "id": "mem_usage_text", "path": "text", "value": f"Usage: {mem['percent']}%"},
            {"op": "set", "id": "mem_usage_bar", "path": "value", "value": mem["percent"]},
            {"op": "set", "id": "mem_total", "path": "text", "value": f"Total: {format_bytes(mem['total'])}"},
            {"op": "set", "id": "mem_used", "path": "text", "value": f"Used: {format_bytes(mem['used'])}"},
            {"op": "set", "id": "mem_avail", "path": "text", "value": f"Available: {format_bytes(mem['available'])}"},
            {"op": "set", "id": "mem_ratio", "path": "text", "value": f"Used/Total: {format_bytes(mem['used'])} / {format_bytes(mem['total'])}"},
            {"op": "set", "id": "mem_updated", "path": "text", "value": f"Updated: {snap['clock']}"},
        ],
        "page_storage": [
            {"op": "set", "id": "st_mount", "path": "text", "value": f"Disk Mount: {disk['mount']}"},
            {"op": "set", "id": "st_disk_text", "path": "text", "value": f"Disk Usage: {disk['percent']}%"},
            {"op": "set", "id": "st_disk_bar", "path": "value", "value": disk["percent"]},
            {"op": "set", "id": "st_disk_total", "path": "text", "value": f"Disk Total: {format_bytes(disk['total'])}"},
            {"op": "set", "id": "st_disk_used", "path": "text", "value": f"Disk Used: {format_bytes(disk['used'])}"},
            {"op": "set", "id": "st_disk_free", "path": "text", "value": f"Disk Free: {format_bytes(disk['free'])}"},
            {"op": "set", "id": "st_net", "path": "text", "value": f"Net TX/RX: {format_bytes(net['sent'])} / {format_bytes(net['recv'])}"},
            {"op": "set", "id": "st_updated", "path": "text", "value": f"Updated: {snap['clock']}"},
        ],
    }

    global_ops = [
        {
            "op": "set",
            "id": "global_page_tag",
            "path": "text",
            "value": f"{state.get('current_page', PAGE_IDS[0])} | auto={'on' if state.get('auto_refresh', True) else 'off'}",
        }
    ]

    return page_ops, global_ops


async def push_snapshot(state):
    ws = state.get("ws")
    if not ws:
        return
    snap = collect_snapshot()
    page_ops, global_ops = build_page_ops(state, snap)

    for page_id in PAGE_IDS:
        ops = page_ops.get(page_id)
        if ops:
            await send_ui_ops_update(state, page_id, ops)

    await send_ui_ops_update(state, "global", global_ops)


async def rebuild_layout(device_id, state, reason):
    snap = collect_snapshot()
    await send_topic(state["ws"], "ui/layout", build_layout(state, snap))
    logging.info("[%s] layout built: reason=%s page=%s", device_id, reason, state.get("current_page"))


async def periodic_pump(device_id, state):
    try:
        while True:
            await asyncio.sleep(PUSH_INTERVAL_SEC)
            if not state.get("initialized"):
                continue
            if not state.get("auto_refresh", True):
                continue
            await push_snapshot(state)
    except asyncio.CancelledError:
        return
    except Exception as exc:
        logging.exception("[%s] pump error: %r", device_id, exc)


async def handle_topic(device_id, state, topic, payload):
    if not isinstance(payload, dict):
        payload = {}

    if topic == "ui/page_changed":
        page = payload.get("page")
        idx = payload.get("index")
        if page in PAGE_IDS:
            state["current_page"] = page
        if isinstance(idx, int) and 0 <= idx < len(PAGE_IDS):
            state["initial_page"] = idx
        await send_ui_ops_update(
            state,
            "global",
            [
                {
                    "op": "set",
                    "id": "global_page_tag",
                    "path": "text",
                    "value": f"{state.get('current_page', PAGE_IDS[0])} | auto={'on' if state.get('auto_refresh', True) else 'off'}",
                }
            ],
        )
        return

    if topic == "sys/refresh":
        await push_snapshot(state)
        return

    if topic == "sys/toggle_auto":
        state["auto_refresh"] = not state.get("auto_refresh", True)
        await push_snapshot(state)
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
                continue

            state = get_or_create_device(device_id, websocket, remote)

            if not state.get("initialized"):
                state["initialized"] = True
                await rebuild_layout(device_id, state, "first_message_init")
                await push_snapshot(state)
                if not state.get("pump_task") or state["pump_task"].done():
                    state["pump_task"] = asyncio.create_task(periodic_pump(device_id, state))

            await handle_topic(device_id, state, topic, payload)

    except websockets.exceptions.ConnectionClosed:
        pass
    except Exception as exc:
        logging.exception("[%s] handler error: %r", remote, exc)
    finally:
        if state and state.get("pump_task"):
            state["pump_task"].cancel()
            state["pump_task"] = None
            state["initialized"] = False
        logging.info("disconnected: %s", remote)


async def main():
    async with websockets.serve(
        sdui_handler,
        HOST,
        PORT,
        ping_interval=None,
        ping_timeout=None,
        close_timeout=60,
        max_size=2**20,
    ):
        logging.info("host info swipe server started on ws://%s:%d", HOST, PORT)
        await asyncio.Future()


if __name__ == "__main__":
    asyncio.run(main())
