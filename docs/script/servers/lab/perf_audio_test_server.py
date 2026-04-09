import asyncio
import base64
import json
import logging
import math
import struct
import threading
import time
import wave
from pathlib import Path

import websockets

# Script summary:
# - Purpose: stress/lab server for audio + animation + runtime telemetry.
# - Scene: performance benchmarking across multiple load profiles.
# - Validation focus:
#   1) frame/update rhythm under different PERF_LEVELS
#   2) audio capture/stream path behavior
#   3) heap and runtime indicator stability

logging.basicConfig(level=logging.INFO, format="%(asctime)s [%(levelname)s] %(message)s")
logging.getLogger("websockets").setLevel(logging.WARNING)

HOST = "0.0.0.0"
PORT = 8080
START_TIME = time.time()
CAPTURE_DIR = Path("audio_captures")
CAPTURE_DIR.mkdir(exist_ok=True)

SAMPLE_RATE = 22050
PCM_BYTES_PER_SAMPLE = 2  # s16le mono
MAX_RECORD_BYTES = 2 * 1024 * 1024

PERF_LEVELS = {
    "lite": {"tick": 0.35, "points": 12, "jitter": 3},
    "balanced": {"tick": 0.18, "points": 20, "jitter": 5},
    "heavy": {"tick": 0.10, "points": 28, "jitter": 7},
    "extreme": {"tick": 0.06, "points": 36, "jitter": 9},
}
PERF_ORDER = ["lite", "balanced", "heavy", "extreme"]
TONE_MIN_INTERNAL_HEAP = 9000
TONE_MIN_DMA_HEAP = 1800
THEME_ORDER = ["ice", "sunset", "forest"]
THEME_STYLE = {
    "ice": {
        "bg": "#04070d",
        "card": "#0f1624",
        "card2": "#141d2e",
        "text": "#dff2ff",
        "sub": "#93aecd",
        "accent": "#56d2ff",
        "ok": "#5de1a8",
        "warn": "#ffbf69",
        "danger": "#ff7d7d",
    },
    "sunset": {
        "bg": "#12080a",
        "card": "#2a1315",
        "card2": "#361a1f",
        "text": "#ffe9d6",
        "sub": "#d0a590",
        "accent": "#ff9f68",
        "ok": "#ffc86b",
        "warn": "#ffd98a",
        "danger": "#ff8d8d",
    },
    "forest": {
        "bg": "#07100b",
        "card": "#15261d",
        "card2": "#1b3024",
        "text": "#dfffe8",
        "sub": "#93b9a2",
        "accent": "#6fe3a4",
        "ok": "#9cffc7",
        "warn": "#ffe08a",
        "danger": "#ff9f9f",
    },
}

DEVICES = {}
SERVER_LOOP = None


def now_str():
    return time.strftime("%H:%M:%S")


def make_state(ws, remote):
    return {
        "ws": ws,
        "addr": str(remote),
        "initialized": False,
        "recording": False,
        "record_context": "",
        "record_start_ts": 0.0,
        "record_buf": bytearray(),
        "last_capture_pcm": None,
        "perf_enabled": False,
        "perf_level": "balanced",
        "perf_task": None,
        "perf_phase": 0.0,
        "theme": "ice",
        "page": "page_dashboard",
        "stats": {
            "rx_frames": 0,
            "audio_start": 0,
            "audio_stream": 0,
            "audio_stop": 0,
            "audio_drop": 0,
            "perf_updates": 0,
        },
        "last_telemetry": {},
    }


def get_or_create_device(device_id, ws, remote):
    if device_id not in DEVICES:
        DEVICES[device_id] = make_state(ws, remote)
    else:
        st = DEVICES[device_id]
        st["ws"] = ws
        st["addr"] = str(remote)
    return DEVICES[device_id]


async def send_topic(ws, topic, payload):
    if ws:
        await ws.send(json.dumps({"topic": topic, "payload": payload}, ensure_ascii=False))


def build_layout(state, device_id):
    perf_level = state["perf_level"]
    perf_on = state["perf_enabled"]
    theme = THEME_STYLE.get(state.get("theme", "ice"), THEME_STYLE["ice"])
    bg_scene = {
        "type": "scene",
        "id": "bg_scene",
        "z": 0,
        "children": [
            {
                "type": "container",
                "id": "bg_circle_left",
                "w": 210,
                "h": 210,
                "align": "top_left",
                "x": -38,
                "y": -28,
                "radius": 120,
                "bg_color": theme["accent"],
                "bg_opa": 34,
                "border_w": 0,
                "pointer_events": "none",
            },
            {
                "type": "container",
                "id": "bg_circle_right",
                "w": 230,
                "h": 230,
                "align": "bottom_right",
                "x": 42,
                "y": 34,
                "radius": 130,
                "bg_color": theme["ok"],
                "bg_opa": 26,
                "border_w": 0,
                "pointer_events": "none",
            },
        ],
    }
    return {
        "safe_pad": 26,
        "w": "full",
        "h": "full",
        "bg_color": theme["bg"],
        "children": [
            bg_scene,
            {
                "type": "viewport",
                "id": "vp_main",
                "z": 10,
                "w": "94%",
                "h": "72%",
                "align": "center",
                "y": -14,
                "direction": "horizontal",
                "initial_page": 0,
                "pages": [
                    {
                        "id": "page_dashboard",
                        "w": "100%",
                        "h": "100%",
                        "children": [
                            {"type": "container", "id": "card_dashboard", "w": "100%", "h": "100%", "bg_color": theme["card"], "bg_opa": 0, "radius": 20, "pad": 10, "children": [
                                {"type": "label", "id": "title", "text": f"Dashboard [{device_id}]", "align": "top_mid", "y": 0, "font_size": 16, "text_color": theme["text"]},
                                {"type": "bar", "id": "perf_bar", "align": "top_mid", "y": 32, "w": "88%", "h": 14, "min": 0, "max": 100, "value": 35, "bg_color": "#1e2b3a", "indic_color": theme["ok"]},
                                {"type": "container", "id": "dash_row_1", "w": "94%", "h": 44, "align": "center", "y": -8, "bg_opa": 0, "flex": "row", "justify": "center", "align_items": "center", "gap": 6, "children": [
                                    {"type": "button", "id": "btn_perf_toggle", "text": "Perf ON" if not perf_on else "Perf OFF", "w": 88, "h": 36, "bg_color": "#2f8b6f", "on_click": "server://perf/toggle"},
                                    {"type": "button", "id": "btn_perf_level", "text": f"Level:{perf_level}", "w": 104, "h": 36, "bg_color": "#365f8a", "on_click": "server://perf/level_next"},
                                    {"type": "button", "id": "btn_theme", "text": f"Theme:{state.get('theme','ice')}", "w": 110, "h": 36, "bg_color": "#5c4a7b", "on_click": "server://theme/next"},
                                ]},
                                {"type": "container", "id": "dash_row_2", "w": "94%", "h": 44, "align": "center", "y": 38, "bg_opa": 0, "flex": "row", "justify": "center", "align_items": "center", "gap": 6, "children": [
                                    {"type": "button", "id": "btn_rebuild", "text": "Rebuild", "w": 88, "h": 36, "bg_color": "#6f3a78", "on_click": "server://perf/rebuild"},
                                    {"type": "button", "id": "btn_perf_burst", "text": "Burst", "w": 88, "h": 36, "bg_color": "#7a5130", "on_click": "server://perf/burst"},
                                ]},
                                {"type": "label", "id": "hint_swipe", "text": "Swipe Left/Right ->", "align": "bottom_mid", "y": -2, "font_size": 12, "text_color": theme["sub"]},
                            ]},
                        ],
                    },
                    {
                        "id": "page_audio",
                        "w": "100%",
                        "h": "100%",
                        "children": [
                            {"type": "container", "id": "card_audio", "w": "100%", "h": "100%", "bg_color": theme["card2"], "bg_opa": 0, "radius": 20, "pad": 10, "children": [
                                {"type": "label", "id": "audio_title", "text": "Audio Loop Test", "align": "top_mid", "y": 2, "font_size": 16, "text_color": theme["text"]},
                                {"type": "container", "id": "audio_row_1", "w": "96%", "h": 48, "align": "center", "y": -14, "bg_opa": 0, "flex": "row", "justify": "center", "align_items": "center", "gap": 8, "children": [
                                    {"type": "button", "id": "btn_rec", "text": "Hold To Talk", "w": 116, "h": 38, "bg_color": "#228be6", "on_press": "local://audio/cmd/record_start?context=perf_test", "on_release": "local://audio/cmd/record_stop"},
                                    {"type": "button", "id": "btn_play_last", "text": "Play Last", "w": 92, "h": 38, "bg_color": "#2a9d8f", "on_click": "server://audio/play_last"},
                                ]},
                                {"type": "container", "id": "audio_row_2", "w": "96%", "h": 48, "align": "center", "y": 36, "bg_opa": 0, "flex": "row", "justify": "center", "align_items": "center", "gap": 8, "children": [
                                    {"type": "button", "id": "btn_tone", "text": "Play Tone", "w": 92, "h": 38, "bg_color": theme["warn"], "on_click": "server://audio/play_test_tone"},
                                    {"type": "button", "id": "btn_save", "text": "SaveRec", "w": 92, "h": 38, "bg_color": "#495057", "on_click": "server://audio/save_last"},
                                ]},
                                {"type": "label", "id": "audio_hint", "text": "Tone=提示音 | Play Last=回放最近录音", "align": "bottom_mid", "y": -2, "font_size": 12, "text_color": theme["sub"]},
                            ]},
                        ],
                    },
                    {
                        "id": "page_stress",
                        "w": "100%",
                        "h": "100%",
                        "children": [
                            {"type": "container", "id": "card_stress", "w": "100%", "h": "100%", "bg_color": theme["card"], "bg_opa": 0, "radius": 20, "pad": 10, "children": [
                                {"type": "label", "id": "stress_title", "text": "Stress Panels", "align": "top_mid", "y": 2, "font_size": 16, "text_color": theme["text"]},
                                {"type": "bar", "id": "stress_bar_1", "align": "top_mid", "y": 36, "w": "88%", "h": 12, "min": 0, "max": 100, "value": 16, "bg_color": "#212c36", "indic_color": theme["accent"]},
                                {"type": "bar", "id": "stress_bar_2", "align": "top_mid", "y": 58, "w": "88%", "h": 12, "min": 0, "max": 100, "value": 42, "bg_color": "#212c36", "indic_color": theme["ok"]},
                                {"type": "bar", "id": "stress_bar_3", "align": "top_mid", "y": 80, "w": "88%", "h": 12, "min": 0, "max": 100, "value": 66, "bg_color": "#212c36", "indic_color": theme["warn"]},
                                {"type": "bar", "id": "stress_bar_4", "align": "top_mid", "y": 102, "w": "88%", "h": 12, "min": 0, "max": 100, "value": 88, "bg_color": "#212c36", "indic_color": theme["danger"]},
                                {"type": "label", "id": "stress_label", "text": "stress stream idle", "align": "center", "y": 30, "font_size": 13, "text_color": theme["sub"]},
                                {"type": "button", "id": "btn_stress_burst", "text": "Burst 100", "align": "bottom_mid", "y": -4, "w": 102, "h": 36, "bg_color": "#7a5130", "on_click": "server://perf/burst"},
                            ]},
                        ],
                    },
                ],
            },
            {
                "type": "container",
                "id": "hud",
                "z": 30,
                "w": "94%",
                "h": "20%",
                "align": "bottom_mid",
                "y": 0,
                "bg_opa": 0,
                "flex": "column",
                "justify": "space_evenly",
                "align_items": "center",
                "children": [
                    {"type": "label", "id": "page_label", "text": f"page={state.get('page','page_dashboard')} theme={state.get('theme','ice')}", "font_size": 12, "text_color": theme["sub"]},
                    {"type": "label", "id": "status_label", "text": "Ready", "font_size": 13, "text_color": theme["text"]},
                    {"type": "label", "id": "perf_tick_label", "text": "tick=0 updates=0", "font_size": 12, "text_color": theme["sub"]},
                    {"type": "label", "id": "audio_stat_label", "text": "audio start=0 stream=0 stop=0 drop=0", "font_size": 12, "text_color": theme["sub"]},
                    {"type": "label", "id": "telemetry_label", "text": "telemetry: waiting...", "font_size": 12, "text_color": theme["sub"]},
                ],
            },
        ],
    }


def perf_values(phase, points, jitter):
    vals = []
    for i in range(points):
        v = 48 + 40 * math.sin(phase + i * 0.35) + 10 * math.sin(phase * 0.7 + i * 0.17)
        v += (math.sin(phase * 1.9 + i) * jitter)
        iv = int(max(4, min(96, v)))
        vals.append(iv)
    return vals


def make_tone_pcm(freq_hz=880, duration_ms=260, amp=0.25):
    count = int(SAMPLE_RATE * (duration_ms / 1000.0))
    out = bytearray()
    peak = int(32767 * amp)
    for n in range(count):
        s = int(peak * math.sin(2.0 * math.pi * freq_hz * n / SAMPLE_RATE))
        out.extend(struct.pack("<h", s))
    return bytes(out)


async def send_audio_play_pcm(state, pcm_bytes):
    b64 = base64.b64encode(pcm_bytes).decode("ascii")
    await send_topic(state["ws"], "audio/play", b64)


def save_pcm_and_wav(device_id, pcm_bytes, suffix="capture"):
    ts = time.strftime("%Y%m%d_%H%M%S")
    stem = f"{device_id}_{suffix}_{ts}"
    pcm_path = CAPTURE_DIR / f"{stem}.pcm"
    wav_path = CAPTURE_DIR / f"{stem}.wav"

    pcm_path.write_bytes(pcm_bytes)
    with wave.open(str(wav_path), "wb") as wf:
        wf.setnchannels(1)
        wf.setsampwidth(PCM_BYTES_PER_SAMPLE)
        wf.setframerate(SAMPLE_RATE)
        wf.writeframes(pcm_bytes)
    return pcm_path, wav_path


def format_audio_stats(state):
    s = state["stats"]
    return f"audio start={s['audio_start']} stream={s['audio_stream']} stop={s['audio_stop']} drop={s['audio_drop']}"


async def refresh_status(state, text=None):
    if text is not None:
        await send_topic(state["ws"], "ui/update", {"id": "status_label", "text": text})
    await send_topic(state["ws"], "ui/update", {"id": "audio_stat_label", "text": format_audio_stats(state)})


async def perf_pump(device_id, state):
    while state["perf_enabled"]:
        cfg = PERF_LEVELS[state["perf_level"]]
        await asyncio.sleep(cfg["tick"])
        state["perf_phase"] += 0.27
        vals = perf_values(state["perf_phase"], cfg["points"], cfg["jitter"])
        avg = int(sum(vals) / len(vals))
        state["stats"]["perf_updates"] += 1
        await send_topic(state["ws"], "ui/update", {"id": "perf_bar", "value": avg})
        await send_topic(state["ws"], "ui/update", {"id": "stress_bar_1", "value": vals[0]})
        await send_topic(state["ws"], "ui/update", {"id": "stress_bar_2", "value": vals[len(vals) // 3]})
        await send_topic(state["ws"], "ui/update", {"id": "stress_bar_3", "value": vals[(len(vals) * 2) // 3]})
        await send_topic(state["ws"], "ui/update", {"id": "stress_bar_4", "value": vals[-1]})
        await send_topic(
            state["ws"],
            "ui/update",
            {"id": "stress_label", "text": f"stress avg={avg} peak={max(vals)} min={min(vals)}"},
        )
        await send_topic(
            state["ws"],
            "ui/update",
            {
                "id": "perf_tick_label",
                "text": f"tick={cfg['tick']:.2f}s updates={state['stats']['perf_updates']} t={now_str()}",
            },
        )


async def set_perf_enabled(device_id, state, enabled):
    state["perf_enabled"] = enabled
    t = state.get("perf_task")
    if t and not t.done():
        t.cancel()
    state["perf_task"] = None

    if enabled:
        state["perf_task"] = asyncio.create_task(perf_pump(device_id, state))
        await refresh_status(state, f"Perf started ({state['perf_level']})")
    else:
        await refresh_status(state, "Perf stopped")


def next_perf_level(curr):
    i = PERF_ORDER.index(curr)
    return PERF_ORDER[(i + 1) % len(PERF_ORDER)]


def next_theme(curr):
    if curr not in THEME_ORDER:
        return THEME_ORDER[0]
    i = THEME_ORDER.index(curr)
    return THEME_ORDER[(i + 1) % len(THEME_ORDER)]


async def rebuild_layout(state, device_id, reason):
    await send_topic(state["ws"], "ui/layout", build_layout(state, device_id))
    await refresh_status(state, f"Layout rebuilt: {reason}")


async def handle_audio_record(device_id, state, payload):
    st = payload.get("state")
    if st == "start":
        state["recording"] = True
        state["record_context"] = payload.get("context", "")
        state["record_start_ts"] = time.time()
        state["record_buf"].clear()
        state["stats"]["audio_start"] += 1
        await refresh_status(state, f"recording start context={state['record_context'] or '-'}")
        return

    if st == "stream":
        data_b64 = payload.get("data", "")
        if not data_b64:
            return
        try:
            chunk = base64.b64decode(data_b64)
        except Exception:
            state["stats"]["audio_drop"] += 1
            await refresh_status(state, "audio stream decode failed")
            return

        if len(state["record_buf"]) + len(chunk) > MAX_RECORD_BYTES:
            state["stats"]["audio_drop"] += 1
        else:
            state["record_buf"].extend(chunk)
            state["stats"]["audio_stream"] += 1

        if (state["stats"]["audio_stream"] % 6) == 0:
            await send_topic(
                state["ws"],
                "ui/update",
                {
                    "id": "status_label",
                    "text": f"recording... {len(state['record_buf']) // 1024}KB",
                },
            )
        await send_topic(state["ws"], "ui/update", {"id": "audio_stat_label", "text": format_audio_stats(state)})
        return

    if st == "stop":
        state["recording"] = False
        state["stats"]["audio_stop"] += 1
        pcm = bytes(state["record_buf"])
        state["last_capture_pcm"] = pcm if pcm else None

        if pcm:
            pcm_path, wav_path = save_pcm_and_wav(device_id, pcm)
            sec = len(pcm) / (SAMPLE_RATE * PCM_BYTES_PER_SAMPLE)
            await refresh_status(state, f"record stop: {sec:.2f}s saved={wav_path.name}")
            logging.info("[%s] audio saved pcm=%s wav=%s bytes=%d", device_id, pcm_path.name, wav_path.name, len(pcm))
        else:
            await refresh_status(state, "record stop: empty capture")
        return


async def handle_topic(device_id, state, topic, payload):
    if topic == "sys/ping":
        await send_topic(
            state["ws"],
            "sys/pong",
            {
                "uptime": int(time.time() - START_TIME),
                "status": "online",
                "server_time": time.strftime("%Y-%m-%d %H:%M:%S"),
            },
        )
        return

    if topic == "telemetry/heartbeat":
        state["last_telemetry"] = payload or {}
        short = {
            "int": payload.get("free_heap_internal"),
            "dma": payload.get("free_heap_dma"),
            "ps": payload.get("free_heap_psram"),
            "rssi": payload.get("wifi_rssi"),
            "t": now_str(),
        }
        await send_topic(state["ws"], "ui/update", {"id": "telemetry_label", "text": f"telemetry: {short}"})
        if not state["initialized"]:
            state["initialized"] = True
            await rebuild_layout(state, device_id, "first_telemetry")
        return

    if topic == "audio/record":
        await handle_audio_record(device_id, state, payload or {})
        return

    if topic == "ui/page_changed":
        page = (payload or {}).get("page", "")
        idx = (payload or {}).get("index")
        if page:
            state["page"] = page
        await send_topic(
            state["ws"],
            "ui/update",
            {"id": "page_label", "text": f"page={state.get('page','-')} idx={idx} theme={state.get('theme','ice')}"},
        )
        return

    if topic == "perf/toggle":
        await set_perf_enabled(device_id, state, not state["perf_enabled"])
        await send_topic(state["ws"], "ui/update", {"id": "btn_perf_toggle", "text": "Perf OFF" if state["perf_enabled"] else "Perf ON"})
        return

    if topic == "perf/level_next":
        state["perf_level"] = next_perf_level(state["perf_level"])
        await send_topic(state["ws"], "ui/update", {"id": "btn_perf_level", "text": f"Level:{state['perf_level']}"})
        await refresh_status(state, f"perf level -> {state['perf_level']}")
        return

    if topic == "perf/rebuild":
        await rebuild_layout(state, device_id, "button_rebuild")
        return

    if topic == "theme/next":
        state["theme"] = next_theme(state.get("theme", "ice"))
        await rebuild_layout(state, device_id, f"theme={state['theme']}")
        return

    if topic == "perf/burst":
        for i in range(100):
            v = (i * 17) % 100
            await send_topic(state["ws"], "ui/update", {"id": "stress_bar_1", "value": v})
            await send_topic(state["ws"], "ui/update", {"id": "stress_bar_2", "value": (v * 3) % 100})
            await send_topic(state["ws"], "ui/update", {"id": "stress_bar_3", "value": (v * 5) % 100})
            await send_topic(state["ws"], "ui/update", {"id": "stress_bar_4", "value": (v * 7) % 100})
        await refresh_status(state, "burst done: 100x4 updates")
        return

    if topic == "audio/play_test_tone":
        tel = state.get("last_telemetry") or {}
        int_heap = int(tel.get("free_heap_internal") or 0)
        dma_heap = int(tel.get("free_heap_dma") or 0)
        if int_heap and dma_heap and (int_heap < TONE_MIN_INTERNAL_HEAP or dma_heap < TONE_MIN_DMA_HEAP):
            await refresh_status(state, f"skip tone: low heap int={int_heap} dma={dma_heap}")
            return
        pcm = make_tone_pcm(880, 260, 0.24)
        await send_audio_play_pcm(state, pcm)
        await refresh_status(state, "sent test tone (880Hz, 260ms)")
        return

    if topic == "audio/play_last":
        pcm = state.get("last_capture_pcm")
        if not pcm:
            await refresh_status(state, "no capture to play")
            return

        tel = state.get("last_telemetry") or {}
        int_heap = int(tel.get("free_heap_internal") or 0)
        dma_heap = int(tel.get("free_heap_dma") or 0)
        if int_heap and dma_heap and (int_heap < TONE_MIN_INTERNAL_HEAP or dma_heap < TONE_MIN_DMA_HEAP):
            await refresh_status(state, f"skip play_last: low heap int={int_heap} dma={dma_heap}")
            return

        await send_audio_play_pcm(state, pcm)
        sec = len(pcm) / (SAMPLE_RATE * PCM_BYTES_PER_SAMPLE)
        await refresh_status(state, f"sent last capture: {sec:.2f}s")
        return

    if topic == "audio/save_last":
        pcm = state.get("last_capture_pcm")
        if pcm:
            _, wav_path = save_pcm_and_wav(device_id, pcm, suffix="manual_save")
            await refresh_status(state, f"saved last capture: {wav_path.name}")
        else:
            await refresh_status(state, "no capture to save")
        return


async def ws_handler(websocket):
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
                logging.warning("[%s] missing device_id", remote)
                continue

            state = get_or_create_device(device_id, websocket, remote)
            state["stats"]["rx_frames"] += 1

            if not state["initialized"]:
                state["initialized"] = True
                await rebuild_layout(state, device_id, "first_message")

            await handle_topic(device_id, state, topic, payload)

    except websockets.exceptions.ConnectionClosed:
        pass
    except Exception as exc:
        logging.exception("[%s] handler crashed: %r", remote, exc)
    finally:
        if state and state.get("ws") is websocket:
            t = state.get("perf_task")
            if t and not t.done():
                t.cancel()
                state["perf_task"] = None
        logging.info("disconnected: %s", remote)


def parse_audio_file(path: Path):
    suffix = path.suffix.lower()
    if suffix == ".pcm":
        return path.read_bytes()

    if suffix == ".wav":
        with wave.open(str(path), "rb") as wf:
            ch = wf.getnchannels()
            sw = wf.getsampwidth()
            sr = wf.getframerate()
            if ch != 1 or sw != 2 or sr != SAMPLE_RATE:
                raise ValueError(f"WAV must be mono/s16/{SAMPLE_RATE}Hz, got ch={ch}, sw={sw}, sr={sr}")
            return wf.readframes(wf.getnframes())

    raise ValueError("Only .pcm/.wav supported")


async def _cli_send_file(device_id, file_path):
    state = DEVICES.get(device_id)
    if not state or not state.get("ws"):
        logging.warning("device not online: %s", device_id)
        return
    p = Path(file_path)
    pcm = parse_audio_file(p)
    await send_audio_play_pcm(state, pcm)
    await refresh_status(state, f"sent audio file: {p.name}")
    logging.info("[%s] sent audio file %s (%d bytes)", device_id, p.name, len(pcm))


async def _cli_tone(device_id, freq, ms):
    state = DEVICES.get(device_id)
    if not state or not state.get("ws"):
        logging.warning("device not online: %s", device_id)
        return
    pcm = make_tone_pcm(freq, ms)
    await send_audio_play_pcm(state, pcm)
    await refresh_status(state, f"sent tone {freq}Hz/{ms}ms")


async def _cli_perf(device_id, on):
    state = DEVICES.get(device_id)
    if not state or not state.get("ws"):
        logging.warning("device not online: %s", device_id)
        return
    await set_perf_enabled(device_id, state, on)


def cli_thread(loop):
    help_text = (
        "\ncommands:\n"
        "  list\n"
        "  tone <device_id> [freq] [ms]\n"
        "  send <device_id> <pcm_or_wav_path>\n"
        "  perf <device_id> on|off\n"
        "  level <device_id> lite|balanced|heavy|extreme\n"
        "  rebuild <device_id>\n"
        "  quit\n"
    )
    print(help_text)

    while True:
        try:
            line = input("cmd> ").strip()
        except EOFError:
            return
        if not line:
            continue

        parts = line.split()
        cmd = parts[0].lower()

        if cmd == "list":
            if not DEVICES:
                print("no devices")
                continue
            for did, st in DEVICES.items():
                print(
                    f"- {did} online={st.get('ws') is not None} perf={st['perf_enabled']}/{st['perf_level']} "
                    f"rx={st['stats']['rx_frames']} rec_bytes={len(st['record_buf'])}"
                )
            continue

        if cmd == "quit":
            print("bye")
            return

        try:
            if cmd == "tone" and len(parts) >= 2:
                did = parts[1]
                freq = int(parts[2]) if len(parts) >= 3 else 880
                ms = int(parts[3]) if len(parts) >= 4 else 260
                asyncio.run_coroutine_threadsafe(_cli_tone(did, freq, ms), loop)
            elif cmd == "send" and len(parts) >= 3:
                did = parts[1]
                path = " ".join(parts[2:])
                asyncio.run_coroutine_threadsafe(_cli_send_file(did, path), loop)
            elif cmd == "perf" and len(parts) >= 3:
                did = parts[1]
                on = parts[2].lower() == "on"
                asyncio.run_coroutine_threadsafe(_cli_perf(did, on), loop)
            elif cmd == "level" and len(parts) >= 3:
                did = parts[1]
                lv = parts[2].lower()
                if lv not in PERF_LEVELS:
                    print(f"invalid level: {lv}")
                    continue
                st = DEVICES.get(did)
                if st:
                    st["perf_level"] = lv
                    asyncio.run_coroutine_threadsafe(
                        send_topic(st["ws"], "ui/update", {"id": "btn_perf_level", "text": f"Level:{lv}"}), loop
                    )
            elif cmd == "rebuild" and len(parts) >= 2:
                did = parts[1]
                st = DEVICES.get(did)
                if st:
                    asyncio.run_coroutine_threadsafe(rebuild_layout(st, did, "cli_rebuild"), loop)
            else:
                print(help_text)
        except Exception as exc:
            logging.exception("cli command failed: %r", exc)


async def main():
    global SERVER_LOOP
    SERVER_LOOP = asyncio.get_running_loop()

    th = threading.Thread(target=cli_thread, args=(SERVER_LOOP,), daemon=True)
    th.start()

    async with websockets.serve(
        ws_handler,
        HOST,
        PORT,
        ping_interval=None,
        ping_timeout=None,
        close_timeout=60,
        max_size=2**20,
    ):
        logging.info("perf+audio test server started at ws://%s:%d", HOST, PORT)
        await asyncio.Future()


if __name__ == "__main__":
    asyncio.run(main())
