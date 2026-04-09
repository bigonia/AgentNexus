import asyncio
# Script summary:
# - Purpose: local music player server for SDUI terminal.
# - Scene: album cover + playback controls + track switching + streaming audio.
# - Resource dependency:
#   1) reads local music library under assets/music_library (configurable by env)
#   2) may generate/reuse cache files for cover/audio conversion
# - Validation focus:
#   1) transport controls (play/pause/next/prev) work
#   2) audio chunk streaming remains smooth
#   3) reconnect keeps session state recoverable

import audioop
import base64
import json
import logging
import math
import os
import re
import struct
import subprocess
from dataclasses import dataclass
from pathlib import Path
from typing import Dict, Optional

import websockets

logging.basicConfig(level=logging.INFO, format="%(asctime)s [%(levelname)s] %(message)s")
logging.getLogger("websockets").setLevel(logging.WARNING)

HOST = "0.0.0.0"
PORT = int(os.getenv("SDUI_MUSIC_PORT", "8080"))
MUSIC_ROOT = Path(os.getenv("SDUI_MUSIC_DIR", "./assets/music_library")).resolve()
COVER_SIZE = int(os.getenv("SDUI_COVER_SIZE", "204"))
AUDIO_CHUNK_BYTES = int(os.getenv("SDUI_AUDIO_CHUNK_BYTES", "8192"))
AUDIO_PACE = float(os.getenv("SDUI_AUDIO_PACE", "1.05"))
AUDIO_PREBUFFER_CHUNKS = int(os.getenv("SDUI_AUDIO_PREBUFFER_CHUNKS", "3"))
AUDIO_RING_DOTS = int(os.getenv("SDUI_RING_DOTS", "12"))
AUDIO_CACHE_ENABLED = os.getenv("SDUI_AUDIO_CACHE", "1") != "0"
AUDIO_CACHE_VERSION = int(os.getenv("SDUI_AUDIO_CACHE_VERSION", "2"))
COVER_CACHE_VERSION = int(os.getenv("SDUI_COVER_CACHE_VERSION", "4"))
AUDIO_CODEC = os.getenv("SDUI_AUDIO_CODEC", "mulaw").strip().lower()
AUDIO_NORMALIZE = os.getenv("SDUI_AUDIO_NORMALIZE", "loudnorm").strip().lower()
AUDIO_TARGET_LUFS = float(os.getenv("SDUI_AUDIO_TARGET_LUFS", "-16"))
AUDIO_TARGET_TP = float(os.getenv("SDUI_AUDIO_TARGET_TP", "-1.5"))
AUDIO_TARGET_LRA = float(os.getenv("SDUI_AUDIO_TARGET_LRA", "11"))
AUDIO_GAIN_DB = float(os.getenv("SDUI_AUDIO_GAIN_DB", "0"))

AUDIO_EXTS = [".flac", ".wav", ".mp3", ".m4a", ".aac", ".ogg", ".opus", ".dsf"]
IMAGE_EXTS = [".jpg", ".jpeg", ".png", ".webp", ".bmp"]

THEME = {
    "bg": "#050b12",
    "card": "#101b29",
    "panel": "#0e1622",
    "title": "#dff2ff",
    "sub": "#8ea8be",
    "accent": "#49d6ff",
    "accent2": "#8dffca",
    "play": "#2f8b6f",
}


@dataclass
class Track:
    folder: Path
    title: str
    artist: str
    audio_path: Path
    cover_path: Optional[Path]
    cover_b64: str
    cover_cache_path: Optional[Path]
    pcm_cache_path: Path


DEVICES: Dict[str, dict] = {}
LIBRARY: list[Track] = []


def build_audio_filter() -> str:
    parts: list[str] = []
    if AUDIO_NORMALIZE in ("loudnorm", "ebu", "ebur128"):
        parts.append(
            f"loudnorm=I={AUDIO_TARGET_LUFS}:TP={AUDIO_TARGET_TP}:LRA={AUDIO_TARGET_LRA}"
        )
    elif AUDIO_NORMALIZE in ("off", "none", "0", ""):
        pass
    else:
        # Fallback: unknown mode is treated as disabled to avoid hard failure.
        logging.warning("unknown SDUI_AUDIO_NORMALIZE=%s, fallback=off", AUDIO_NORMALIZE)
    if abs(AUDIO_GAIN_DB) > 0.001:
        parts.append(f"volume={AUDIO_GAIN_DB}dB")
    return ",".join(parts)


def build_audio_profile_tag() -> str:
    norm = AUDIO_NORMALIZE if AUDIO_NORMALIZE else "off"
    norm = re.sub(r"[^a-zA-Z0-9._-]+", "_", norm)
    lufs = str(AUDIO_TARGET_LUFS).replace(".", "p").replace("-", "m")
    tp = str(AUDIO_TARGET_TP).replace(".", "p").replace("-", "m")
    lra = str(AUDIO_TARGET_LRA).replace(".", "p").replace("-", "m")
    gain = str(AUDIO_GAIN_DB).replace(".", "p").replace("-", "m")
    return f"v{AUDIO_CACHE_VERSION}_{norm}_i{lufs}_tp{tp}_lra{lra}_g{gain}"


def sanitize_title(name: str) -> str:
    s = re.sub(r"\[[^\]]+\]", "", name).strip()
    s = re.sub(r"\.(mgg2|mgg)$", "", s, flags=re.IGNORECASE).strip()
    return s or name


def rgb888_to_565(r: int, g: int, b: int) -> int:
    return ((r & 0xF8) << 8) | ((g & 0xFC) << 3) | (b >> 3)


def hex_to_rgb565(hex_color: str) -> int:
    s = hex_color.strip().lstrip("#")
    if len(s) != 6:
        return rgb888_to_565(16, 27, 41)
    return rgb888_to_565(int(s[0:2], 16), int(s[2:4], 16), int(s[4:6], 16))


def apply_circle_mask_rgb565(raw: bytes, size: int, bg565: int) -> bytes:
    if not raw or len(raw) < size * size * 2:
        return raw
    data = bytearray(raw)
    cx = (size - 1) * 0.5
    cy = (size - 1) * 0.5
    r = size * 0.5 - 1.0
    r2 = r * r
    bg = struct.pack("<H", bg565)
    for y in range(size):
        dy = y - cy
        for x in range(size):
            dx = x - cx
            if dx * dx + dy * dy > r2:
                off = (y * size + x) * 2
                data[off:off + 2] = bg
    return bytes(data)


def make_placeholder_cover_b64(size: int = 184) -> str:
    px = hex_to_rgb565(THEME["bg"])
    raw = struct.pack("<H", px) * (size * size)
    raw = apply_circle_mask_rgb565(raw, size, px)
    return base64.b64encode(raw).decode("ascii")


def ensure_cache_dir(folder: Path) -> Path:
    c = folder / ".sdui_cache"
    c.mkdir(parents=True, exist_ok=True)
    return c


def is_cache_valid(cache_file: Path, src_file: Path) -> bool:
    if not cache_file.exists() or not src_file.exists():
        return False
    return cache_file.stat().st_mtime >= src_file.stat().st_mtime


def build_cover_cache_path(folder: Path, src_name: str, size: int) -> Path:
    cache_dir = ensure_cache_dir(folder)
    safe = re.sub(r"[^a-zA-Z0-9._-]+", "_", src_name)
    return cache_dir / f"cover_v{COVER_CACHE_VERSION}_{safe}_{size}.rgb565"


def build_pcm_cache_path(folder: Path, src_name: str) -> Path:
    cache_dir = ensure_cache_dir(folder)
    safe = re.sub(r"[^a-zA-Z0-9._-]+", "_", src_name)
    profile = build_audio_profile_tag()
    return cache_dir / f"audio_{safe}_{profile}_22050_mono_s16le.pcm"


def convert_cover_to_rgb565_raw(img_path: Path, size: int) -> bytes:
    cmd = [
        "ffmpeg",
        "-v",
        "error",
        "-i",
        str(img_path),
        "-vf",
        f"scale={size}:{size}:force_original_aspect_ratio=increase,crop={size}:{size}",
        "-frames:v",
        "1",
        "-f",
        "rawvideo",
        "-pix_fmt",
        "rgb565le",
        "pipe:1",
    ]
    proc = subprocess.run(cmd, check=True, capture_output=True)
    return proc.stdout


def load_cover_b64_with_cache(folder: Path, cover_path: Optional[Path], size: int) -> tuple[str, Optional[Path]]:
    if not cover_path:
        return make_placeholder_cover_b64(size), None

    cache_path = build_cover_cache_path(folder, cover_path.name, size)
    bg565 = hex_to_rgb565(THEME["bg"])
    try:
        if is_cache_valid(cache_path, cover_path):
            raw = cache_path.read_bytes()
            if raw:
                return base64.b64encode(raw).decode("ascii"), cache_path

        raw = convert_cover_to_rgb565_raw(cover_path, size)
        if not raw:
            return make_placeholder_cover_b64(size), None
        raw = apply_circle_mask_rgb565(raw, size, bg565)
        cache_path.write_bytes(raw)
        return base64.b64encode(raw).decode("ascii"), cache_path
    except Exception as exc:
        logging.warning("cover convert/cache failed %s: %r", cover_path, exc)
        return make_placeholder_cover_b64(size), None


def ensure_audio_cache_sync(track: Track) -> Optional[Path]:
    cache_path = track.pcm_cache_path
    src = track.audio_path
    af = build_audio_filter()
    try:
        if is_cache_valid(cache_path, src):
            return cache_path

        tmp = cache_path.with_suffix(cache_path.suffix + ".tmp")
        cmd = [
            "ffmpeg",
            "-v",
            "error",
            "-y",
            "-i",
            str(src),
            "-f",
            "s16le",
            "-acodec",
            "pcm_s16le",
            "-ar",
            "22050",
            "-ac",
            "1",
        ]
        if af:
            cmd.extend(["-af", af])
        cmd.extend([
            str(tmp),
        ])
        subprocess.run(cmd, check=True)
        if tmp.exists() and tmp.stat().st_size > 0:
            tmp.replace(cache_path)
            return cache_path
        if tmp.exists():
            tmp.unlink(missing_ok=True)
    except Exception as exc:
        logging.warning("audio cache build failed %s: %r", src, exc)
    return cache_path if cache_path.exists() else None


def pick_first_file(folder: Path, exts: list[str], preferred_stems: Optional[list[str]] = None) -> Optional[Path]:
    preferred_stems = preferred_stems or []
    files = [p for p in folder.iterdir() if p.is_file() and p.suffix.lower() in exts]
    if not files:
        return None
    for stem in preferred_stems:
        for f in files:
            if f.stem.lower() == stem.lower():
                return f
    files.sort(key=lambda p: p.name.lower())
    return files[0]


def list_audio_files(folder: Path) -> list[Path]:
    files = [p for p in folder.iterdir() if p.is_file() and p.suffix.lower() in AUDIO_EXTS]
    files.sort(key=lambda p: p.name.lower())
    return files


def is_audio_decodable(path: Path) -> bool:
    # Quick probe: decode a tiny segment to null sink.
    cmd = [
        "ffmpeg",
        "-v",
        "error",
        "-t",
        "0.15",
        "-i",
        str(path),
        "-f",
        "null",
        "-",
    ]
    try:
        subprocess.run(cmd, check=True, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
        return True
    except Exception:
        return False


def pick_first_decodable_audio(folder: Path) -> Optional[Path]:
    files = list_audio_files(folder)
    for f in files:
        if is_audio_decodable(f):
            return f
    return None


def parse_meta(folder: Path) -> tuple[str, str]:
    meta_path = folder / "meta.json"
    if meta_path.exists():
        try:
            data = json.loads(meta_path.read_text(encoding="utf-8"))
            title = str(data.get("title") or "").strip()
            artist = str(data.get("artist") or "").strip()
            if title or artist:
                return title or folder.name, artist or "Unknown"
        except Exception as exc:
            logging.warning("meta.json parse failed %s: %r", meta_path, exc)
    return folder.name, "Unknown"


def load_library(root: Path, cover_size: int) -> list[Track]:
    tracks: list[Track] = []
    if not root.exists():
        logging.warning("music dir not found: %s", root)
        return tracks

    folders = [p for p in root.iterdir() if p.is_dir()]
    folders.sort(key=lambda p: p.name.lower())

    for folder in folders:
        audio = pick_first_decodable_audio(folder)
        if not audio:
            raw_candidates = [p.name for p in list_audio_files(folder)]
            if raw_candidates:
                logging.warning("skip folder (no decodable audio): %s candidates=%s", folder, raw_candidates)
            continue

        cover = pick_first_file(folder, IMAGE_EXTS, preferred_stems=["cover", "folder", "front"])
        meta_title, meta_artist = parse_meta(folder)
        raw_title = meta_title if meta_title != folder.name else sanitize_title(audio.stem)
        title = sanitize_title(raw_title)
        artist = meta_artist

        cover_b64, cover_cache = load_cover_b64_with_cache(folder, cover, cover_size)
        pcm_cache = build_pcm_cache_path(folder, audio.name)

        tracks.append(
            Track(
                folder=folder,
                title=title,
                artist=artist,
                audio_path=audio,
                cover_path=cover,
                cover_b64=cover_b64,
                cover_cache_path=cover_cache,
                pcm_cache_path=pcm_cache,
            )
        )

    logging.info("loaded %d tracks from %s", len(tracks), root)
    return tracks


def print_library_summary(tracks: list[Track], root: Path):
    print("\n[Detected Tracks]")
    print(f"root: {root}")
    if not tracks:
        print("  (empty)")
        print()
        return

    ext_counter: Dict[str, int] = {}
    for i, t in enumerate(tracks, start=1):
        ext = t.audio_path.suffix.lower() or "unknown"
        ext_counter[ext] = ext_counter.get(ext, 0) + 1
        cover_name = t.cover_path.name if t.cover_path else "none(placeholder)"
        cover_cache = t.cover_cache_path.name if t.cover_cache_path else "none"
        pcm_cache = t.pcm_cache_path.name if t.pcm_cache_path.exists() else "pending"
        print(f"  {i:02d}. {t.title} | artist={t.artist}")
        print(f"      audio={t.audio_path.name} | cover={cover_name}")
        print(f"      cache: cover={cover_cache} pcm={pcm_cache}")

    fmt = ", ".join(f"{k}:{v}" for k, v in sorted(ext_counter.items()))
    print(f"formats: {fmt}")
    print()


def get_or_create_state(device_id: str, ws, remote) -> dict:
    if device_id not in DEVICES:
        DEVICES[device_id] = {
            "ws": ws,
            "addr": str(remote),
            "device_id": device_id,
            "initialized": False,
            "current_index": 0,
            "is_playing": False,
            "play_task": None,
            "play_token": 0,
            "suppress_page_change_once": False,
        }
    else:
        st = DEVICES[device_id]
        prev_ws = st.get("ws")
        st["ws"] = ws
        st["addr"] = str(remote)
        if prev_ws is not ws:
            st["initialized"] = False
            st["suppress_page_change_once"] = False
            st["is_playing"] = False
            st["play_token"] = int(st.get("play_token", 0)) + 1
            st["play_task"] = None
            logging.info("[%s] reconnect detected, force re-init layout", device_id)
    return DEVICES[device_id]


async def send_topic(ws, topic: str, payload):
    if ws:
        await ws.send(json.dumps({"topic": topic, "payload": payload}, ensure_ascii=False))


async def send_audio_chunk(state: dict, pcm_chunk: bytes):
    if AUDIO_CODEC == "mulaw":
        try:
            ulaw = audioop.lin2ulaw(pcm_chunk, 2)
            b64 = base64.b64encode(ulaw).decode("ascii")
            payload = {"codec": "mulaw", "sample_rate": 22050, "channels": 1, "data": b64}
            await send_topic(state["ws"], "audio/play", payload)
            return
        except Exception:
            logging.exception("mulaw encode failed, fallback pcm")
    b64 = base64.b64encode(pcm_chunk).decode("ascii")
    await send_topic(state["ws"], "audio/play", b64)


def build_empty_layout(msg: str):
    return {
        "safe_pad": 26,
        "w": "full",
        "h": "full",
        "bg_color": THEME["bg"],
        "children": [
            {
                "type": "container",
                "w": "100%",
                "h": "100%",
                "bg_opa": 0,
                "flex": "column",
                "justify": "center",
                "align_items": "center",
                "children": [
                    {"type": "label", "text": "Music Player", "font_size": 20, "text_color": THEME["title"]},
                    {"type": "label", "text": msg, "w": "94%", "font_size": 13, "text_color": THEME["sub"], "text_align": "center"},
                ],
            }
        ],
    }


def track_at(idx: int) -> Track:
    return LIBRARY[idx % len(LIBRARY)]


def ring_color_for_state(is_playing: bool) -> str:
    return THEME["play"] if is_playing else THEME["accent"]


def build_dotted_ring_children(prefix: str, radius: int, dot_color: str, dot_count: int = 12) -> list[dict]:
    nodes = []
    if dot_count <= 0:
        return nodes
    for i in range(dot_count):
        if i % 3 == 2:
            continue
        ang = (2.0 * math.pi * i) / dot_count
        x = int(round(math.cos(ang) * radius))
        y = int(round(math.sin(ang) * radius))
        nodes.append(
            {
                "type": "container",
                "id": f"{prefix}_dot_{i}",
                "w": 6 if i % 2 == 0 else 4,
                "h": 6 if i % 2 == 0 else 4,
                "radius": 999,
                "align": "center",
                "x": x,
                "y": y,
                "bg_color": dot_color,
                "bg_opa": 210 if i % 2 == 0 else 150,
                "border_w": 0,
                "pointer_events": "none",
            }
        )
    return nodes


def build_slot_page(slot_id: str, track: Track, is_playing: bool = False):
    ring_radius = int(COVER_SIZE * 0.58)
    ring_size = ring_radius * 2 + 16
    title_offset_y = int(ring_size * 0.5 + 8)
    is_center_slot = (slot_id == "cur")
    ring_color = ring_color_for_state(is_playing) if is_center_slot else THEME["accent"]
    return {
        "type": "container",
        "id": f"page_{slot_id}",
        "w": "100%",
        "h": "100%",
        "bg_opa": 0,
        "children": [
            {
                "type": "container",
                "w": "94%",
                "h": "100%",
                "align": "center",
                "y": 0,
                "bg_opa": 0,
                "pad": 6,
                "flex": "column",
                "justify": "center",
                "align_items": "center",
                "gap": 0,
                "children": [
                    {
                        "type": "container",
                        "w": ring_size,
                        "h": ring_size,
                        "align": "center",
                        "y": 0,
                        "bg_opa": 0,
                        "children": [
                            {
                                "type": "button" if is_center_slot else "container",
                                "id": f"cover_touch_{slot_id}" if is_center_slot else f"cover_clip_{slot_id}",
                                "w": COVER_SIZE,
                                "h": COVER_SIZE,
                                "radius": 999,
                                "clip_corner": True,
                                "bg_opa": 0,
                                "bg_color": THEME["bg"],
                                "pointer_events": "auto" if is_center_slot else "none",
                                "clickable": True if is_center_slot else False,
                                "align": "center",
                                **({"on_click": "server://player/toggle"} if is_center_slot else {}),
                                "children": [
                                    {
                                        "type": "image",
                                        "id": f"cover_{slot_id}",
                                        "src": track.cover_b64,
                                        "img_w": COVER_SIZE,
                                        "img_h": COVER_SIZE,
                                        "w": COVER_SIZE,
                                        "h": COVER_SIZE,
                                        "align": "center",
                                        "pointer_events": "none",
                                    }
                                ],
                            },
                            {
                                "type": "container",
                                "id": f"ring_{slot_id}",
                                "w": COVER_SIZE + 18,
                                "h": COVER_SIZE + 18,
                                "radius": 999,
                                "align": "center",
                                "bg_opa": 0,
                                "border_w": 2,
                                "border_color": ring_color,
                                "children": build_dotted_ring_children(f"ring_{slot_id}", ring_radius, ring_color, AUDIO_RING_DOTS) if is_center_slot else [],
                            },
                        ],
                    },
                    {
                        "type": "label",
                        "id": f"title_{slot_id}",
                        "text": track.title,
                        "w": "86%",
                        "align": "center",
                        "y": title_offset_y,
                        "font_size": 17 if is_center_slot else 15,
                        "text_color": THEME["title"],
                        "text_align": "center",
                        "long_mode": "scroll",
                    },
                ],
            }
        ],
    }


def build_player_layout(state: dict):
    idx = state["current_index"] % len(LIBRARY)
    prev_track = track_at(idx - 1)
    cur_track = track_at(idx)
    next_track = track_at(idx + 1)

    return {
        "safe_pad": 26,
        "w": "full",
        "h": "full",
        "bg_color": THEME["bg"],
        "children": [
            {
                "type": "container",
                "id": "bg_orb_l",
                "w": 260,
                "h": 260,
                "align": "top_left",
                "x": -78,
                "y": -58,
                "radius": 130,
                "bg_color": THEME["accent"],
                "bg_opa": 32,
                "border_w": 0,
                "pointer_events": "none",
            },
            {
                "type": "container",
                "id": "bg_orb_r",
                "w": 260,
                "h": 260,
                "align": "bottom_right",
                "x": 72,
                "y": 52,
                "radius": 130,
                "bg_color": THEME["accent2"],
                "bg_opa": 26,
                "border_w": 0,
                "pointer_events": "none",
            },
            {
                "type": "viewport",
                "id": "vp_music",
                "w": "100%",
                "h": "100%",
                "direction": "horizontal",
                "initial_page": 1,
                "pages": [
                    build_slot_page("prev", prev_track, False),
                    build_slot_page("cur", cur_track, bool(state.get("is_playing"))),
                    build_slot_page("next", next_track, False),
                ],
            },
            {
                "type": "button",
                "id": "cover_hit_center",
                "w": COVER_SIZE + 22,
                "h": COVER_SIZE + 22,
                "radius": 999,
                "align": "center",
                "y": 0,
                "bg_opa": 0,
                "border_w": 0,
                "text": "",
                "on_click": "server://player/toggle",
            },
        ],
    }


def build_play_state_updates(is_playing: bool) -> list[dict]:
    color = ring_color_for_state(is_playing)
    updates: list[dict] = [{"id": "ring_cur", "border_color": color}]
    for i in range(AUDIO_RING_DOTS):
        if i % 3 == 2:
            continue
        updates.append({"id": f"ring_cur_dot_{i}", "bg_color": color})
    return updates


async def refresh_play_state_visual(state: dict):
    ws = state.get("ws")
    if not ws:
        return
    for upd in build_play_state_updates(bool(state.get("is_playing"))):
        try:
            await send_topic(ws, "ui/update", upd)
        except Exception:
            # Ignore per-node update failures to keep playback control responsive.
            pass


async def stop_playback(state: dict):
    state["play_token"] += 1
    task = state.get("play_task")
    if task and not task.done():
        task.cancel()
        try:
            await task
        except asyncio.CancelledError:
            pass
        except Exception:
            logging.exception("play task stop error")
    state["play_task"] = None
    state["is_playing"] = False


async def stream_track(state: dict, index: int, token: int):
    if index < 0 or index >= len(LIBRARY):
        return

    track = LIBRARY[index]
    logging.info("play start: %s", track.audio_path)
    sent_total_chunks = 0

    pcm_cache = None
    if AUDIO_CACHE_ENABLED:
        pcm_cache = await asyncio.to_thread(ensure_audio_cache_sync, track)

    try:
        if pcm_cache and pcm_cache.exists():
            logging.info("play using pcm cache: %s", pcm_cache)
            sent_chunks = 0
            with pcm_cache.open("rb") as f:
                while True:
                    if token != state.get("play_token"):
                        break
                    chunk = f.read(AUDIO_CHUNK_BYTES)
                    if not chunk:
                        break
                    await send_audio_chunk(state, chunk)
                    sent_total_chunks += 1
                    sent_chunks += 1
                    if sent_chunks <= AUDIO_PREBUFFER_CHUNKS:
                        await asyncio.sleep(0.002)
                    else:
                        real_sec = len(chunk) / (22050.0 * 2.0)
                        await asyncio.sleep(max(0.01, real_sec * AUDIO_PACE))
        else:
            af = build_audio_filter()
            cmd = [
                "ffmpeg",
                "-v",
                "error",
                "-i",
                str(track.audio_path),
                "-f",
                "s16le",
                "-acodec",
                "pcm_s16le",
                "-ar",
                "22050",
                "-ac",
                "1",
            ]
            if af:
                cmd.extend(["-af", af])
            cmd.append("pipe:1")
            proc = await asyncio.create_subprocess_exec(
                *cmd,
                stdout=asyncio.subprocess.PIPE,
                stderr=asyncio.subprocess.PIPE,
            )
            try:
                sent_chunks = 0
                while True:
                    if token != state.get("play_token"):
                        break
                    chunk = await proc.stdout.read(AUDIO_CHUNK_BYTES)
                    if not chunk:
                        break
                    await send_audio_chunk(state, chunk)
                    sent_total_chunks += 1
                    sent_chunks += 1
                    if sent_chunks <= AUDIO_PREBUFFER_CHUNKS:
                        await asyncio.sleep(0.002)
                    else:
                        real_sec = len(chunk) / (22050.0 * 2.0)
                        await asyncio.sleep(max(0.01, real_sec * AUDIO_PACE))
            finally:
                if proc.returncode is None:
                    proc.kill()
                    await proc.wait()
    except asyncio.CancelledError:
        pass
    finally:
        if sent_total_chunks == 0:
            logging.warning("track decode produced no audio: %s", track.audio_path)
            # Auto-skip to next track if available, keep user in playback flow.
            if len(LIBRARY) > 1 and token == state.get("play_token"):
                try:
                    state["current_index"] = (state["current_index"] + 1) % len(LIBRARY)
                    await rebuild_for_current(state)
                    await start_playback(state)
                    return
                except Exception:
                    logging.exception("auto-skip failed")
        if token == state.get("play_token"):
            state["is_playing"] = False
            try:
                await refresh_play_state_visual(state)
            except Exception:
                pass
        logging.info("play stop: %s", track.audio_path)


async def start_playback(state: dict):
    if not LIBRARY:
        return
    await stop_playback(state)
    idx = state["current_index"]
    state["is_playing"] = True
    token = state["play_token"]
    state["play_task"] = asyncio.create_task(stream_track(state, idx, token))
    await refresh_play_state_visual(state)


async def pause_playback(state: dict):
    await stop_playback(state)
    await refresh_play_state_visual(state)


async def rebuild_for_current(state: dict):
    if not LIBRARY:
        return
    state["suppress_page_change_once"] = True
    await send_topic(state["ws"], "ui/layout", build_player_layout(state))


async def change_track(state: dict, new_index: int, autoplay: bool = True, force_rebuild: bool = True):
    if not LIBRARY:
        return
    state["current_index"] = new_index % len(LIBRARY)

    if force_rebuild:
        await rebuild_for_current(state)

    if autoplay:
        await start_playback(state)


async def handle_topic(state: dict, topic: str, payload: dict):
    if topic == "player/toggle":
        if state.get("is_playing"):
            await pause_playback(state)
        else:
            await start_playback(state)
        return

    if topic == "player/prev":
        await change_track(state, state["current_index"] - 1, autoplay=bool(state.get("is_playing")), force_rebuild=True)
        return

    if topic == "player/next":
        await change_track(state, state["current_index"] + 1, autoplay=bool(state.get("is_playing")), force_rebuild=True)
        return

    if topic == "ui/page_changed":
        if state.get("suppress_page_change_once"):
            state["suppress_page_change_once"] = False
            return

        idx = payload.get("index")
        if idx == 0:
            await change_track(state, state["current_index"] - 1, autoplay=bool(state.get("is_playing")), force_rebuild=True)
        elif idx == 2:
            await change_track(state, state["current_index"] + 1, autoplay=bool(state.get("is_playing")), force_rebuild=True)
        return


async def ensure_init(state: dict):
    if state.get("initialized"):
        return
    state["initialized"] = True

    if not LIBRARY:
        msg = (
            f"资源目录为空: {MUSIC_ROOT}\n"
            "请按“一个文件夹=一首歌”放入音频与封面。"
        )
        await send_topic(state["ws"], "ui/layout", build_empty_layout(msg))
        return

    await rebuild_for_current(state)


async def sdui_handler(websocket):
    remote = websocket.remote_address
    device_id = None
    try:
        async for message in websocket:
            data = json.loads(message)
            topic = data.get("topic")
            payload = data.get("payload", {})
            device_id = data.get("device_id") or device_id
            if not device_id:
                continue

            if not isinstance(payload, dict):
                payload = {}

            state = get_or_create_state(device_id, websocket, remote)
            await ensure_init(state)

            if topic:
                await handle_topic(state, topic, payload)
    except websockets.exceptions.ConnectionClosed:
        pass
    except Exception:
        logging.exception("handler crashed for %s", remote)
    finally:
        if device_id and device_id in DEVICES:
            try:
                await stop_playback(DEVICES[device_id])
            except Exception:
                pass
        logging.info("Disconnected: %s", remote)


def print_resource_hint(root: Path):
    print("\n[Music Resource Layout]")
    print(f"root: {root}")
    print("example:")
    print("  music_library/")
    print("    坏女孩/")
    print("      坏女孩 [mqms2].mgg2.flac")
    print("      坏女孩.wav")
    print("      cover.jpg")
    print("      meta.json   # optional: {\"title\":\"坏女孩\",\"artist\":\"XX\"}")
    print("      .sdui_cache/ # 自动生成：封面与音频缓存")
    print()


async def main():
    global LIBRARY
    print_resource_hint(MUSIC_ROOT)
    LIBRARY = load_library(MUSIC_ROOT, COVER_SIZE)
    print_library_summary(LIBRARY, MUSIC_ROOT)

    async with websockets.serve(
        sdui_handler,
        HOST,
        PORT,
        ping_interval=None,
        ping_timeout=None,
        close_timeout=60,
        max_size=2**22,
    ):
        logging.info("music player server listening on ws://%s:%d", HOST, PORT)
        logging.info(
            "audio pacing: chunk=%d bytes pace=%.2f prebuf=%d codec=%s cache=%s normalize=%s af=%s",
            AUDIO_CHUNK_BYTES,
            AUDIO_PACE,
            AUDIO_PREBUFFER_CHUNKS,
            AUDIO_CODEC,
            "on" if AUDIO_CACHE_ENABLED else "off",
            AUDIO_NORMALIZE,
            build_audio_filter() or "(none)",
        )
        await asyncio.Future()


if __name__ == "__main__":
    asyncio.run(main())

