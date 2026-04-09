#!/usr/bin/env python3
"""
Theme/Scene 样例验证脚本（保留版）

业务说明：
1. 该脚本用于“主题 + 背景场景”的联调验证，不承载业务逻辑。
2. 终端只需通过 Theme/Scene 两个按钮切换，即可验证视觉一致性。
3. 当前只保留 3 个基线场景（aurora/grid/bubbles），用于产品基线回归。
4. 样例来源统一读取 configs/ui/ui2_theme_scene_styles.json，确保可复现。
"""

import asyncio
import json
import logging
import os
from pathlib import Path

import websockets

# Script summary:
# - Purpose: theme/scene alignment lab script (retained baseline).
# - Scene: verify visual consistency by switching theme + scene only.
# - Constraint: keeps 3 baseline scenes (aurora/grid/bubbles) for stable regression.
# - Config source: prefers docs/configs/ui/ui2_theme_scene_styles.json.
# - Validation focus:
#   1) theme/scene switching correctness
#   2) style-file loading fallback behavior
#   3) deterministic output for visual regression


logging.basicConfig(level=logging.INFO, format="%(asctime)s [%(levelname)s] %(message)s")
logging.getLogger("websockets").setLevel(logging.WARNING)

PAGE_IDS = ["page_preview", "page_control"]
devices = {}

_REPO_ROOT = Path(__file__).resolve().parents[3]
_STYLE_CANDIDATES = [
    _REPO_ROOT / "docs" / "configs" / "ui" / "ui2_theme_scene_styles.json",
    _REPO_ROOT / "configs" / "ui" / "ui2_theme_scene_styles.json",
]
_STYLE_PATH_ENV = os.environ.get("UI2_THEME_SCENE_STYLE_PATH", "").strip()
if _STYLE_PATH_ENV:
    STYLE_PATH = Path(_STYLE_PATH_ENV)
else:
    STYLE_PATH = next((p for p in _STYLE_CANDIDATES if p.exists()), _STYLE_CANDIDATES[0])

DEFAULT_THEMES = {
    "ice": {
        "root_bg": "#04070d",
        "heading_text": "#dff2ff",
        "title_text": "#d9e8f7",
        "sub_text": "#7f96aa",
        "accent": "#39d5ff",
        "accent_2": "#8affc1",
        "glow_a": "#2f8cff",
        "glow_b": "#5ef0ff",
    }
}

DEFAULT_SCENES = {
    "aurora": {"label": "Aurora", "aurora_opa": 255, "grid_opa": 0, "bubbles_opa": 0},
    "grid": {"label": "Grid", "aurora_opa": 0, "grid_opa": 255, "bubbles_opa": 0},
    "bubbles": {"label": "Nebula Bubbles", "aurora_opa": 0, "grid_opa": 0, "bubbles_opa": 255},
}

THEMES = DEFAULT_THEMES.copy()
SCENES = DEFAULT_SCENES.copy()
THEME_ORDER = list(THEMES.keys())
SCENE_ORDER = ["aurora", "grid", "bubbles"]


def cycle(value, order):
    if value not in order:
        return order[0]
    return order[(order.index(value) + 1) % len(order)]


def load_styles():
    global THEMES, SCENES, THEME_ORDER, SCENE_ORDER
    if not STYLE_PATH.exists():
        logging.warning("style file not found: %s, using defaults", STYLE_PATH)
        return
    try:
        cfg = json.loads(STYLE_PATH.read_text(encoding="utf-8"))
        themes = cfg.get("themes")
        scenes = cfg.get("scenes")
        if isinstance(themes, dict) and themes:
            THEMES = themes
        if isinstance(scenes, dict) and scenes:
            SCENES = scenes
        # 业务约束：样例脚本只跑固定三场景
        SCENE_ORDER = [x for x in ("aurora", "grid", "bubbles") if x in SCENES]
        if not SCENE_ORDER:
            SCENE_ORDER = ["aurora", "grid", "bubbles"]
        order = cfg.get("theme_order")
        if isinstance(order, list):
            valid = [x for x in order if isinstance(x, str) and x in THEMES]
            if valid:
                THEME_ORDER = valid
        if not THEME_ORDER:
            THEME_ORDER = list(THEMES.keys())
    except Exception as exc:
        logging.warning("load styles failed: %r", exc)


def make_state(websocket, remote):
    return {
        "ws": websocket,
        "addr": str(remote),
        "initialized": False,
        "theme": THEME_ORDER[0],
        "scene": SCENE_ORDER[0],
        "server_query_merge": True,
        "initial_page": 0,
        "current_page": PAGE_IDS[0],
        "update_revisions": {},
    }


def get_or_create_device(device_id, websocket, remote):
    if device_id not in devices:
        devices[device_id] = make_state(websocket, remote)
    st = devices[device_id]
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


async def send_ui_ops_update(state, page_id, ops, *, transaction=True):
    await send_topic(
        state["ws"],
        "ui/update",
        {
            "page_id": page_id,
            "revision": next_update_revision(state, page_id),
            "transaction": bool(transaction),
            "ops": ops,
        },
    )


def build_scene(theme, scene_name):
    s = SCENES.get(scene_name, DEFAULT_SCENES["aurora"])
    return [
        {
            "type": "container",
            "id": "layer_aurora",
            "w": "full",
            "h": "full",
            "opa": s.get("aurora_opa", 0),
            "hidden": s.get("aurora_opa", 0) <= 0,
            "children": [
                {"type": "container", "id": "aurora_l", "w": 300, "h": 80, "align": "center", "x": -60, "y": -50, "bg_color": theme["glow_a"], "bg_opa": 44, "radius": 999},
                {"type": "container", "id": "aurora_r", "w": 300, "h": 80, "align": "center", "x": 60, "y": 50, "bg_color": theme["glow_b"], "bg_opa": 44, "radius": 999},
            ],
        },
        {
            "type": "container",
            "id": "layer_grid",
            "w": "full",
            "h": "full",
            "opa": s.get("grid_opa", 0),
            "hidden": s.get("grid_opa", 0) <= 0,
            "children": [
                {"type": "container", "id": "grid_h", "w": "92%", "h": 2, "align": "center", "y": -20, "bg_color": theme["accent"], "bg_opa": 26},
                {"type": "container", "id": "grid_v", "w": 2, "h": "76%", "align": "center", "x": 0, "bg_color": theme["accent_2"], "bg_opa": 26},
            ],
        },
        {
            "type": "container",
            "id": "layer_bubbles",
            "w": "full",
            "h": "full",
            "opa": s.get("bubbles_opa", 0),
            "hidden": s.get("bubbles_opa", 0) <= 0,
            "children": [
                {"type": "container", "id": "b1", "w": 150, "h": 150, "align": "center", "x": -110, "y": -60, "radius": 999, "bg_color": theme["accent"], "bg_opa": 72},
                {"type": "container", "id": "b2", "w": 120, "h": 120, "align": "center", "x": 120, "y": 70, "radius": 999, "bg_color": theme["accent_2"], "bg_opa": 76},
            ],
        },
    ]


def build_layout(state):
    t = THEMES.get(state["theme"], next(iter(THEMES.values())))
    scene_label = SCENES.get(state["scene"], {}).get("label", state["scene"])
    merge_state = "on" if state.get("server_query_merge", True) else "off"
    return {
        "w": "full",
        "h": "full",
        "bg_color": t["root_bg"],
        "children": [
            {
                "type": "container",
                "id": "bg_fill",
                "w": "full",
                "h": "full",
                "align": "center",
                "bg_color": t["root_bg"],
                "bg_opa": 255,
                "border_w": 0,
                "pointer_events": "none",
            },
            {"type": "scene", "id": "global_scene", "w": "full", "h": "full", "children": build_scene(t, state["scene"])},
            {
                "type": "viewport",
                "id": "main_swiper",
                "w": "full",
                "h": "full",
                "initial_page": state["initial_page"],
                "direction": "horizontal",
                "pages": [
                    {
                        "type": "container",
                        "id": "page_preview",
                        "bg_opa": 0,
                        "children": [
                            {"type": "label", "id": "title_preview", "text": "Theme Scene Preview", "align": "top_mid", "y": 12, "text_color": t["title_text"], "font_size": 18},
                            {"type": "label", "id": "sub_preview", "text": f"{state['theme']} / {scene_label}", "align": "top_mid", "y": 40, "text_color": t["sub_text"], "font_size": 14},
                        ],
                    },
                    {
                        "type": "container",
                        "id": "page_control",
                        "bg_opa": 0,
                        "children": [
                            {"type": "label", "id": "title_ctrl", "text": "Theme / Scene Verify", "align": "top_mid", "y": 12, "text_color": t["title_text"], "font_size": 18},
                            {
                                "type": "container",
                                "id": "ctrl_row",
                                "w": 296,
                                "h": 46,
                                "align": "center",
                                "y": 30,
                                "bg_opa": 0,
                                "flex": "row",
                                "justify": "space_between",
                                "align_items": "center",
                                "children": [
                                    {"type": "button", "id": "btn_theme", "text": "Theme", "w": 142, "h": 42, "bg_color": t["accent"], "on_click": "server://ui/switch_theme"},
                                    {"type": "button", "id": "btn_scene", "text": "Scene", "w": 142, "h": 42, "bg_color": t["accent_2"], "on_click": "server://ui/switch_scene"},
                                ],
                            },
                            {
                                "type": "container",
                                "id": "verify_row",
                                "w": 296,
                                "h": 46,
                                "align": "center",
                                "y": 82,
                                "bg_opa": 0,
                                "flex": "row",
                                "justify": "space_between",
                                "align_items": "center",
                                "children": [
                                    {"type": "button", "id": "btn_ops_tx", "text": "OpsTx", "w": 142, "h": 42, "bg_color": t["accent"], "on_click": "server://ui/demo_ops_tx?source=lab"},
                                    {"type": "button", "id": "btn_ops_fail", "text": "OpsFail", "w": 142, "h": 42, "bg_color": t["accent_2"], "on_click": "server://ui/demo_ops_fail?source=lab"},
                                ],
                            },
                            {
                                "type": "container",
                                "id": "feature_row",
                                "w": 296,
                                "h": 40,
                                "align": "center",
                                "y": 130,
                                "bg_opa": 0,
                                "children": [
                                    {"type": "button", "id": "btn_qmerge", "text": "QMerge", "w": 296, "h": 36, "bg_color": t["accent"], "on_click": "server://ui/toggle_query_merge"},
                                ],
                            },
                            {
                                "type": "container",
                                "id": "struct_row",
                                "w": 296,
                                "h": 40,
                                "align": "center",
                                "y": 172,
                                "bg_opa": 0,
                                "flex": "row",
                                "justify": "space_between",
                                "align_items": "center",
                                "children": [
                                    {"type": "button", "id": "btn_struct_ins", "text": "Ins", "w": 96, "h": 36, "bg_color": t["accent"], "on_click": "server://ui/demo_struct_insert"},
                                    {"type": "button", "id": "btn_struct_rep", "text": "Rep", "w": 96, "h": 36, "bg_color": t["accent_2"], "on_click": "server://ui/demo_struct_replace"},
                                    {"type": "button", "id": "btn_struct_rm", "text": "Rm", "w": 96, "h": 36, "bg_color": t["accent"], "on_click": "server://ui/demo_struct_remove"},
                                ],
                            },
                            {"type": "label", "id": "status", "text": f"theme={state['theme']} scene={scene_label} qmerge={merge_state}", "align": "center", "y": 214, "text_color": t["sub_text"], "font_size": 13},
                        ],
                    },
                ],
            },
        ],
    }


async def rebuild_layout(device_id, state, reason):
    await send_topic(state["ws"], "ui/layout", build_layout(state))
    logging.info("[%s] rebuild reason=%s theme=%s scene=%s", device_id, reason, state["theme"], state["scene"])


async def apply_theme_runtime(device_id, state):
    t = THEMES.get(state["theme"], next(iter(THEMES.values())))
    s = SCENES.get(state["scene"], DEFAULT_SCENES["aurora"])
    scene_label = s.get("label", state["scene"])
    merge_state = "on" if state.get("server_query_merge", True) else "off"
    ops = [
        {"op": "set", "id": "bg_fill", "path": "bg_color", "value": t["root_bg"]},
        {"op": "set", "id": "title_preview", "path": "text_color", "value": t["title_text"]},
        {"op": "set", "id": "sub_preview", "path": "text", "value": f"{state['theme']} / {scene_label}"},
        {"op": "set", "id": "sub_preview", "path": "text_color", "value": t["sub_text"]},
        {"op": "set", "id": "title_ctrl", "path": "text_color", "value": t["title_text"]},
        {"op": "set", "id": "status", "path": "text", "value": f"theme={state['theme']} scene={scene_label} qmerge={merge_state}"},
        {"op": "set", "id": "status", "path": "text_color", "value": t["sub_text"]},
        {"op": "set", "id": "btn_theme", "path": "bg_color", "value": t["accent"]},
        {"op": "set", "id": "btn_scene", "path": "bg_color", "value": t["accent_2"]},
        {"op": "set", "id": "btn_ops_tx", "path": "bg_color", "value": t["accent"]},
        {"op": "set", "id": "btn_ops_fail", "path": "bg_color", "value": t["accent_2"]},
        {"op": "set", "id": "btn_qmerge", "path": "bg_color", "value": t["accent"]},
        {"op": "set", "id": "btn_struct_ins", "path": "bg_color", "value": t["accent"]},
        {"op": "set", "id": "btn_struct_rep", "path": "bg_color", "value": t["accent_2"]},
        {"op": "set", "id": "btn_struct_rm", "path": "bg_color", "value": t["accent"]},
        {"op": "set", "id": "aurora_l", "path": "bg_color", "value": t["glow_a"]},
        {"op": "set", "id": "aurora_r", "path": "bg_color", "value": t["glow_b"]},
        {"op": "set", "id": "grid_h", "path": "bg_color", "value": t["accent"]},
        {"op": "set", "id": "grid_v", "path": "bg_color", "value": t["accent_2"]},
        {"op": "set", "id": "b1", "path": "bg_color", "value": t["accent"]},
        {"op": "set", "id": "b2", "path": "bg_color", "value": t["accent_2"]},
    ]
    await send_ui_ops_update(state, "global", ops, transaction=True)
    logging.info("[%s] runtime theme switch=%s", device_id, state["theme"])


async def apply_scene_runtime(device_id, state):
    s = SCENES.get(state["scene"], DEFAULT_SCENES["aurora"])
    scene_label = s.get("label", state["scene"])
    merge_state = "on" if state.get("server_query_merge", True) else "off"
    ops = [
        {"op": "set", "id": "layer_aurora", "path": "opa", "value": s.get("aurora_opa", 0)},
        {"op": "set", "id": "layer_aurora", "path": "hidden", "value": s.get("aurora_opa", 0) <= 0},
        {"op": "set", "id": "layer_grid", "path": "opa", "value": s.get("grid_opa", 0)},
        {"op": "set", "id": "layer_grid", "path": "hidden", "value": s.get("grid_opa", 0) <= 0},
        {"op": "set", "id": "layer_bubbles", "path": "opa", "value": s.get("bubbles_opa", 0)},
        {"op": "set", "id": "layer_bubbles", "path": "hidden", "value": s.get("bubbles_opa", 0) <= 0},
        {"op": "set", "id": "sub_preview", "path": "text", "value": f"{state['theme']} / {scene_label}"},
        {"op": "set", "id": "status", "path": "text", "value": f"theme={state['theme']} scene={scene_label} qmerge={merge_state}"},
    ]
    await send_ui_ops_update(state, "global", ops, transaction=True)
    logging.info("[%s] runtime scene switch=%s", device_id, state["scene"])


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
        return
    if topic == "ui/switch_theme":
        state["theme"] = cycle(state["theme"], THEME_ORDER)
        await apply_theme_runtime(device_id, state)
        return
    if topic == "ui/switch_scene":
        state["scene"] = cycle(state["scene"], SCENE_ORDER)
        await apply_scene_runtime(device_id, state)
        return
    if topic == "ui/demo_ops_tx":
        src = payload.get("source", "unknown")
        await send_ui_ops_update(
            state,
            "page_control",
            [
                {"op": "set", "id": "status", "path": "text", "value": f"ops tx ok [{src}]"},
                {"op": "patch", "id": "status", "path": "text_color", "value": "#7EE787"},
            ],
            transaction=True,
        )
        return
    if topic == "ui/toggle_query_merge":
        state["server_query_merge"] = not state.get("server_query_merge", True)
        await send_topic(
            state["ws"],
            "ui/features",
            {
                "server_query_merge": state["server_query_merge"],
                "request_state": True,
            },
        )
        merge_state = "on" if state["server_query_merge"] else "off"
        await send_topic(
            state["ws"],
            "ui/update",
            {"id": "status", "text": f"theme={state['theme']} scene={state['scene']} qmerge={merge_state}"},
        )
        logging.info("[%s] server_query_merge=%s", device_id, merge_state)
        return
    if topic == "ui/demo_ops_fail":
        src = payload.get("source", "unknown")
        await send_ui_ops_update(
            state,
            "page_control",
            [
                {"op": "set", "id": "status", "path": "text", "value": "this should rollback"},
                {"op": "set", "id": "missing_widget", "path": "text", "value": "boom"},
            ],
            transaction=True,
        )
        await send_topic(state["ws"], "ui/update", {"id": "status", "text": f"ops fail injected [{src}]"})
        return
    if topic == "ui/demo_struct_insert":
        await send_ui_ops_update(
            state,
            "page_control",
            [
                {
                    "op": "insert",
                    "parent_id": "struct_row",
                    "index": 3,
                    "node": {
                        "type": "button",
                        "id": "dyn_struct",
                        "text": "Dyn",
                        "w": 96,
                        "h": 36,
                        "bg_color": "#4CAF50",
                        "on_click": "server://ui/demo_ops_tx?source=dyn_inserted",
                    },
                }
            ],
            transaction=True,
        )
        return
    if topic == "ui/demo_struct_replace":
        await send_ui_ops_update(
            state,
            "page_control",
            [
                {
                    "op": "replace",
                    "id": "dyn_struct",
                    "node": {
                        "type": "button",
                        "id": "dyn_struct",
                        "text": "DynR",
                        "w": 96,
                        "h": 36,
                        "bg_color": "#f39c12",
                        "on_click": "server://ui/demo_ops_tx?source=struct_replace",
                    },
                }
            ],
            transaction=True,
        )
        return
    if topic == "ui/demo_struct_remove":
        await send_ui_ops_update(
            state,
            "page_control",
            [{"op": "remove", "id": "dyn_struct"}],
            transaction=True,
        )
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
            await handle_topic(device_id, state, topic, payload)
    except websockets.exceptions.ConnectionClosed:
        pass
    except Exception as exc:
        logging.exception("[%s] handler error: %r", remote, exc)
    finally:
        logging.info("disconnected: %s", remote)


async def main():
    load_styles()
    async with websockets.serve(
        sdui_handler,
        "0.0.0.0",
        8080,
        ping_interval=None,
        ping_timeout=None,
        close_timeout=60,
        max_size=2**20,
    ):
        logging.info("theme-scene lab (baseline-3) started on ws://0.0.0.0:8080")
        await asyncio.Future()


if __name__ == "__main__":
    asyncio.run(main())
