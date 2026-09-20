#!/usr/bin/env python3
"""Small dependency-free WebSocket server for LCD_085 firmware verification.

Set the device WebSocket URL to ``ws://<this-computer-ip>:8080`` (the firmware
will append ``deviceId`` when needed), then open the browser test console::

    python tools/lcd085_dev_server.py
    python tools/lcd085_dev_server.py --port 9000

The server intentionally implements only the RFC 6455 subset used by ESP-IDF's
WebSocket client.  It prints all device events and request results, which makes
it useful both as a visual test driver and as a protocol probe.
"""

from __future__ import annotations

import argparse
import asyncio
import base64
import hashlib
import json
import math
import struct
import time
from collections import deque
from collections.abc import Awaitable, Callable
from dataclasses import dataclass, field


WS_GUID = "258EAFA5-E914-47DA-95CA-C5AB0DC85B11"
ACTIONS = (
    "schemas_recheck", "rules_replace", "rules_clear", "ui_metrics", "ui_menu",
    "ui_chart", "ui_scene_switch", "ui_menu_conflict", "ui_invalid_scene",
    "image", "canvas", "audio",
)


@dataclass
class DevServerState:
    connected_devices: int = 0
    last_peer: str = "无"
    last_hello: dict[str, object] | None = None
    device_ready: bool = False
    init_phase: str = "等待终端连接"
    schema_status: dict[str, dict[str, object]] = field(default_factory=lambda: {
        "command": {"state": "未请求"}, "ui": {"state": "未请求"},
    })
    schema_documents: dict[str, object | None] = field(default_factory=lambda: {
        "command": None, "ui": None,
    })
    active_scene: str = "无"
    active_view: str = "本地页面"
    last_test: str = "无"
    events: deque[dict[str, str]] = field(default_factory=lambda: deque(maxlen=80))
    active_session: ScenarioSession | None = field(default=None, init=False)
    action_task: asyncio.Task[None] | None = field(default=None, init=False)

    def log(self, direction: str, message: str) -> None:
        self.events.appendleft({
            "time": time.strftime("%H:%M:%S"),
            "direction": direction,
            "message": message,
        })

    def as_json(self) -> bytes:
        return json.dumps({
            "connected_devices": self.connected_devices,
            "last_peer": self.last_peer,
            "last_hello": self.last_hello,
            "device_ready": self.device_ready,
            "init_phase": self.init_phase,
            "schema_status": self.schema_status,
            "schema_documents": self.schema_documents,
            "active_scene": self.active_scene,
            "active_view": self.active_view,
            "last_test": self.last_test,
            "action_running": bool(self.action_task and not self.action_task.done()),
            "events": list(self.events),
        }, ensure_ascii=False).encode("utf-8")


STATUS_PAGE = """<!doctype html>
<html lang="zh-CN"><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">
<title>LCD_085 v2 测试台</title>
<style>
body{margin:0;background:#10131a;color:#e8edf5;font:15px system-ui,-apple-system,"Microsoft YaHei",sans-serif}
main{max-width:1120px;margin:28px auto;padding:0 20px}h1{margin:0 0 8px;font-size:25px}.hint{color:#9ca9bb;line-height:1.55}.ok{color:#62dba4}.wait{color:#f6c55c}.bad{color:#ff7d87}.two{display:grid;grid-template-columns:1fr 1fr;gap:12px}.schema{display:grid;gap:8px}.schema div{display:flex;justify-content:space-between;padding:8px;background:#101722;border-radius:6px}@media(max-width:720px){.two{grid-template-columns:1fr}}
.cards{display:grid;grid-template-columns:repeat(auto-fit,minmax(180px,1fr));gap:12px;margin:24px 0}
.card,li{background:#191f2b;border:1px solid #2b3547;border-radius:10px;padding:14px}.label{color:#9ca9bb;font-size:12px}.value{margin-top:7px;font-size:17px;word-break:break-all}
ul{padding:0;margin:0;display:grid;gap:8px;list-style:none}.time{color:#7f8da3;margin-right:8px}.out{color:#7dd3fc}.in{color:#86efac}
pre{margin:0;white-space:pre-wrap;font:12px ui-monospace,Consolas,monospace;color:#cbd5e1}.controls{display:flex;flex-wrap:wrap;gap:8px}.controls button{border:1px solid #3a4961;border-radius:8px;background:#253149;color:#e8edf5;padding:9px 12px;cursor:pointer}.controls button:hover{background:#334467}.controls button.warn{border-color:#934d4d;background:#522b32}.controls button:disabled{opacity:.5;cursor:wait}
</style><main><h1>LCD_085 v2 功能测试台</h1><div class="hint">设备连接后自动完成双 Schema 分块校验；只有收到 <code>device.ready</code> 才开放各项能力调试。</div>
<section class="cards"><div class="card"><div class="label">终端连接</div><div id="connected" class="value">加载中</div></div><div class="card"><div class="label">初始化阶段</div><div id="phase" class="value">-</div></div><div class="card"><div class="label">主视图 / 规则组</div><div id="view" class="value">-</div></div><div class="card"><div class="label">最近测试</div><div id="test" class="value">-</div></div></section>
<section class="two"><div class="card"><h2>初始化与 Schema</h2><div class="schema"><div><span>Command Schema</span><strong id="command-schema">未请求</strong></div><div><span>UI Schema</span><strong id="ui-schema">未请求</strong></div></div><div class="controls" style="margin-top:12px"><button data-action="schemas_recheck">重新请求并校验</button></div><p class="hint">自动验证分块顺序、数量、长度、SHA-256 和 JSON 格式。</p></div><div class="card"><h2>device.hello</h2><pre id="hello">尚未收到</pre></div></section>
<section class="two"><div class="card"><h2>规则配置</h2><div class="controls"><button data-action="rules_replace">全量写入测试规则组</button><button class="warn" data-action="rules_clear">清空全部规则组</button></div><p class="hint">内置 dashboard、assistant、surface 和 menu-conflict 四个规则组；不提供单组或增量修改。</p></div><div class="card"><h2>UI 模板</h2><div class="controls"><button data-action="ui_metrics">指标页 + dashboard</button><button data-action="ui_menu">菜单原生交互</button><button data-action="ui_chart">图表页</button><button data-action="ui_scene_switch">切换至 assistant</button></div><p class="hint">模板使用内置示例参数；菜单不携带规则组，验证 UI 原生按键优先级。</p></div></section>
<section class="two"><div class="card"><h2>Indexed Surface</h2><div class="controls"><button data-action="image">发送静态 Image</button><button data-action="canvas">播放 Canvas 动画</button></div><p class="hint">分辨率、色板、帧数据和节奏均由测试台内置，避免手工填写二进制参数。</p></div><div class="card"><h2>音频与边界校验</h2><div class="controls"><button data-action="audio">播放 440 Hz PCM</button><button data-action="ui_menu_conflict">验证菜单冲突拒绝</button><button data-action="ui_invalid_scene">验证不存在场景拒绝</button></div><p class="hint">后两个操作是预期拒绝，成功表示终端保留原有视图与规则状态。</p></div></section>
<section class="two"><details class="card"><summary>展开 Command Schema（校验通过后可用）</summary><pre id="command-schema-doc">尚未校验</pre></details><details class="card"><summary>展开 UI Schema（校验通过后可用）</summary><pre id="ui-schema-doc">尚未校验</pre></details></section>
<h2>最近协议记录</h2><ul id="events"></ul>
<script>const esc=v=>String(v).replace(/[&<>"']/g,c=>({'&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;',"'":'&#39;'}[c])),buttons=[...document.querySelectorAll('[data-action]')],result=document.getElementById('control-result');async function action(name){buttons.forEach(b=>b.disabled=true);result.textContent='正在执行 '+name+'…';try{const r=await fetch('/api/action',{method:'POST',headers:{'Content-Type':'application/json'},body:JSON.stringify({action:name})}),body=await r.json();result.textContent=body.ok?'已开始：'+name:'未执行：'+body.error}catch(e){result.textContent='控制请求失败'}finally{buttons.forEach(b=>b.disabled=false)}}buttons.forEach(b=>b.onclick=()=>action(b.dataset.action));function schema(id,v){const e=document.getElementById(id);e.textContent=v.state+(v.error?': '+v.error:'');e.className=v.state==='已校验'?'ok':v.state==='失败'?'bad':'wait'}function doc(id,value){document.getElementById(id).textContent=value?JSON.stringify(value,null,2):'尚未校验'}async function refresh(){try{const s=await fetch('/api/status',{cache:'no-store'}).then(r=>r.json());connected.textContent=s.connected_devices?(s.device_ready?'终端已就绪':'终端已连接'):'未连接';connected.className='value '+(s.device_ready?'ok':'wait');phase.textContent=s.init_phase;view.textContent=s.active_view+' / '+s.active_scene;test.textContent=s.last_test;schema('command-schema',s.schema_status.command);schema('ui-schema',s.schema_status.ui);doc('command-schema-doc',s.schema_documents.command);doc('ui-schema-doc',s.schema_documents.ui);hello.textContent=s.last_hello?JSON.stringify(s.last_hello,null,2):'尚未收到';events.innerHTML=s.events.map(e=>`<li><span class="time">${esc(e.time)}</span><span class="${e.direction==='→'?'out':'in'}">${esc(e.direction)}</span> ${esc(e.message)}</li>`).join('')||'<li>暂无消息</li>'}catch(e){connected.textContent='状态页连接失败'}}refresh();setInterval(refresh,800)</script>
</main></html>"""


class WebSocketConnection:
    """A minimal, server-side RFC 6455 Text/Binary WebSocket connection."""

    def __init__(self, reader: asyncio.StreamReader, writer: asyncio.StreamWriter) -> None:
        self.reader = reader
        self.writer = writer
        self.closed = False
        self._send_lock = asyncio.Lock()

    async def receive(self) -> tuple[int, bytes] | None:
        """Return one complete data frame, handling ping/pong and close frames."""
        while not self.closed:
            header = await self.reader.readexactly(2)
            opcode = header[0] & 0x0F
            masked = bool(header[1] & 0x80)
            length = header[1] & 0x7F
            if length == 126:
                length = struct.unpack("!H", await self.reader.readexactly(2))[0]
            elif length == 127:
                length = struct.unpack("!Q", await self.reader.readexactly(8))[0]
            if length > 9216:
                raise ValueError(f"device sent an oversized frame ({length} bytes)")
            mask = await self.reader.readexactly(4) if masked else b""
            payload = bytearray(await self.reader.readexactly(length))
            if masked:
                for index in range(length):
                    payload[index] ^= mask[index % 4]
            if opcode == 0x8:
                await self.close()
                return None
            if opcode == 0x9:
                await self.send_frame(0xA, bytes(payload))
                continue
            if opcode in (0x1, 0x2):
                return opcode, bytes(payload)
            if opcode == 0xA:
                continue
            raise ValueError(f"unsupported WebSocket opcode 0x{opcode:02x}")
        return None

    async def send_frame(self, opcode: int, payload: bytes) -> None:
        if self.closed:
            return
        length = len(payload)
        if length < 126:
            header = bytes((0x80 | opcode, length))
        elif length <= 0xFFFF:
            header = bytes((0x80 | opcode, 126)) + struct.pack("!H", length)
        else:
            header = bytes((0x80 | opcode, 127)) + struct.pack("!Q", length)
        async with self._send_lock:
            self.writer.write(header + payload)
            await self.writer.drain()

    async def send_json(self, message: dict[str, object]) -> None:
        encoded = json.dumps(message, ensure_ascii=False, separators=(",", ":")).encode("utf-8")
        await self.send_frame(0x1, encoded)

    async def close(self) -> None:
        if not self.closed:
            self.closed = True
            self.writer.close()
            await self.writer.wait_closed()


class ScenarioSession:
    def __init__(self, connection: WebSocketConnection, state: DevServerState) -> None:
        self.connection = connection
        self.state = state
        self.request_sequence = 0
        self.pending_results: dict[str, asyncio.Future[dict[str, object]]] = {}
        self.schema_streams: dict[str, tuple[str, int, int, int, bytearray]] = {}
        self.schema_valid: set[str] = set()

    def begin_schema(self, body: object) -> None:
        if not isinstance(body, dict):
            return
        kind, schema_hash, length, chunks = (body.get("kind"), body.get("hash"),
                                             body.get("length"), body.get("chunks"))
        if (isinstance(kind, str) and kind in ("command", "ui") and isinstance(schema_hash, str)
                and isinstance(length, int) and length >= 0 and isinstance(chunks, int) and chunks > 0):
            self.schema_streams[kind] = (schema_hash, length, chunks, 0, bytearray())
            self.state.schema_status[kind] = {
                "state": "接收中", "hash": schema_hash, "length": length, "chunks": chunks,
            }
            self.state.init_phase = f"正在接收 {kind} Schema"

    def append_schema_chunk(self, payload: bytes) -> None:
        if len(payload) < 5 or payload[0] != 0x31:
            return
        kind = "ui" if payload[1] == 1 else "command"
        stream = self.schema_streams.get(kind)
        index = (payload[2] << 8) | payload[3]
        if not stream:
            return
        schema_hash, length, chunks, next_index, collected = stream
        if index != next_index:
            self.schema_streams.pop(kind, None)
            self.state.schema_status[kind] = {"state": "失败", "error": "分块序号不连续"}
            return
        collected.extend(payload[4:])
        self.schema_streams[kind] = (schema_hash, length, chunks, next_index + 1, collected)

    def finish_schema(self, body: object) -> bool:
        if not isinstance(body, dict) or not isinstance(body.get("kind"), str):
            return False
        kind = body["kind"]
        stream = self.schema_streams.pop(kind, None)
        if not stream:
            return False
        schema_hash, length, chunks, next_index, collected = stream
        error = None
        schema: object | None = None
        if body.get("hash") != schema_hash or len(collected) != length or next_index != chunks:
            error = "结束信息或长度不匹配"
        elif hashlib.sha256(collected).hexdigest() != schema_hash:
            error = "SHA-256 不匹配"
        else:
            try:
                schema = json.loads(collected)
                if not isinstance(schema, dict):
                    error = "Schema 不是对象"
            except json.JSONDecodeError:
                error = "Schema JSON 无法解析"
        if error:
            self.state.schema_status[kind] = {"state": "失败", "error": error}
            return False
        self.schema_valid.add(kind)
        self.state.schema_documents[kind] = schema
        self.state.schema_status[kind] = {
            "state": "已校验", "hash": schema_hash, "length": length,
        }
        return True

    async def request(self, name: str, body: dict[str, object] | None = None) -> None:
        self.request_sequence += 1
        request_id = f"dev-{int(time.time())}-{self.request_sequence}"
        message: dict[str, object] = {
            "id": request_id,
            "name": name,
        }
        if body is not None:
            message["body"] = body
        print(f"-> {json.dumps(message, ensure_ascii=False)}")
        self.state.log("→", f"{name} {json.dumps(body, ensure_ascii=False) if body else ''}")
        result = asyncio.get_running_loop().create_future()
        self.pending_results[request_id] = result
        await self.connection.send_json(message)
        try:
            reply = await asyncio.wait_for(asyncio.shield(result), timeout=5.0)
        except TimeoutError as error:
            raise RuntimeError(f"终端未在 5 秒内回应 {name}") from error
        finally:
            self.pending_results.pop(request_id, None)
        if reply.get("ok") is not True:
            raise RuntimeError(f"终端拒绝 {name}: {reply.get('error', 'unknown_error')}")

    async def request_expect_error(self, name: str, body: dict[str, object], expected: str) -> None:
        self.request_sequence += 1
        request_id = f"dev-{int(time.time())}-{self.request_sequence}"
        result = asyncio.get_running_loop().create_future()
        self.pending_results[request_id] = result
        await self.connection.send_json({"id": request_id, "name": name, "body": body})
        self.state.log("→", f"{name}（预期拒绝：{expected}）")
        try:
            reply = await asyncio.wait_for(asyncio.shield(result), timeout=5.0)
        finally:
            self.pending_results.pop(request_id, None)
        if reply.get("ok") is not False or reply.get("error") != expected:
            raise RuntimeError(f"{name} 未按预期拒绝：{reply}")

    def resolve_result(self, message: dict[str, object]) -> None:
        request_id = message.get("id")
        if not isinstance(request_id, str):
            return
        result = self.pending_results.get(request_id)
        if result and not result.done():
            result.set_result(message)

    async def send_binary(self, kind: int, payload: bytes) -> None:
        print(f"-> binary 0x{kind:02X}, {len(payload)} byte(s)")
        self.state.log("→", f"Binary 0x{kind:02X}，{len(payload)} bytes")
        await self.connection.send_frame(0x2, bytes((kind,)) + payload)


def checkerboard_4bpp(size: int = 32, phase: int = 0) -> bytes:
    """Build a 32x32 packed-index image compatible with the 0x21 data plane."""
    indexes = []
    for y in range(size):
        for x in range(size):
            indexes.append(((x // 4) + (y // 4) + phase) % 16)
    return bytes((indexes[index] << 4) | indexes[index + 1] for index in range(0, len(indexes), 2))


def pcm_sine_chunk(frequency: float, sample_count: int = 441) -> bytes:
    """Generate 20 ms of mono 22.05 kHz signed-16-bit PCM at a safe volume."""
    samples = [int(5500 * math.sin(2 * math.pi * frequency * n / 22050)) for n in range(sample_count)]
    return struct.pack("<" + "h" * len(samples), *samples)


async def install_test_scenes(session: ScenarioSession) -> None:
    """Install the entire scene list in one request, as required by v2."""
    await session.request("business.scenes.replace", {
        "scenes": [
            {"id": "dashboard", "bindings": [{
                "trigger": "button.pwr.short_press",
                "responses": [{"name": "platform.interaction.report", "body": {"token": "dashboard-refresh"}}],
            }, {
                "trigger": "button.plus.short_press",
                "responses": [{"name": "platform.interaction.report", "body": {"token": "dashboard-reset"}}],
            }]},
            {"id": "assistant", "bindings": [{
                "trigger": "button.pwr.short_press",
                "responses": [{"name": "audio.record.toggle"}, {"name": "platform.interaction.report", "body": {"token": "assistant-record"}}],
            }, {
                "trigger": "button.plus.short_press",
                "responses": [{"name": "platform.interaction.report", "body": {"token": "assistant-play"}}],
            }]},
            {"id": "surface", "bindings": [{
                "trigger": "button.plus.long_press",
                "responses": [{"name": "platform.interaction.report", "body": {"token": "surface-next"}}],
            }]},
            {"id": "menu-conflict", "bindings": [{
                "trigger": "button.plus.short_press",
                "responses": [{"name": "platform.interaction.report", "body": {"token": "must-not-run"}}],
            }]},
        ],
    })


def record_view(state: DevServerState, view: str, scene_id: str | None) -> None:
    state.active_view = view
    state.active_scene = scene_id or "无"


async def scenario_dashboard(session: ScenarioSession) -> None:
    await install_test_scenes(session)
    await session.request("display.section.set", {
        "type": "metrics_section", "scene_id": "dashboard", "title": "开发联调",
        "items": [
            {"label": "温度", "value": "24", "unit": "°C"},
            {"label": "湿度", "value": "58", "unit": "%"},
            {"label": "网络", "value": "OK"},
            {"label": "模式", "value": "LOCAL"},
        ],
    })
    record_view(session.state, "metrics_section", "dashboard")


async def scenario_scene_switch(session: ScenarioSession) -> None:
    await install_test_scenes(session)
    await session.request("display.section.set", {
        "type": "text_section", "scene_id": "dashboard", "title": "场景 A",
        "text": "规则组 dashboard 已随视图生效",
    })
    await asyncio.sleep(0.35)
    await session.request("display.section.set", {
        "type": "text_section", "scene_id": "assistant", "title": "场景 B",
        "text": "规则组 assistant 与视图同时切换",
    })
    record_view(session.state, "text_section", "assistant")


async def scenario_menu(session: ScenarioSession) -> None:
    await install_test_scenes(session)
    await session.request("display.section.set", {
        "type": "menu_section", "title": "验证菜单",
        "items": [
            {"label": "开始", "token": "menu-start"},
            {"label": "状态", "token": "menu-status"},
            {"label": "设置", "token": "menu-settings"},
        ],
    })
    record_view(session.state, "menu_section（原生按键）", None)


async def scenario_menu_conflict(session: ScenarioSession) -> None:
    await install_test_scenes(session)
    await session.request_expect_error("display.section.set", {
        "type": "menu_section", "scene_id": "menu-conflict", "title": "不应显示",
        "items": [{"label": "冲突", "token": "conflict"}],
    }, "ui_rule_conflict")


async def scenario_invalid_scene(session: ScenarioSession) -> None:
    await install_test_scenes(session)
    await session.request_expect_error("display.section.set", {
        "type": "status_section", "scene_id": "does-not-exist", "title": "不应显示",
        "value": "Invalid",
    }, "scene_not_found")


async def open_surface(session: ScenarioSession, mode: str) -> None:
    await install_test_scenes(session)
    request_name = "display.image.begin" if mode == "image" else "display.canvas.open"
    await session.request(request_name, {
        "scene_id": "surface",
        "width": 32, "height": 32, "bits_per_index": 4,
        "palette": [0x0000, 0xFFFF, 0xF800, 0x07E0, 0x001F, 0xFFE0, 0xF81F, 0x07FF,
                    0x8410, 0x4208, 0xFD20, 0xAFE5, 0x780F, 0x03EF, 0x7BEF, 0xC618],
    })
    if mode == "image":
        await session.send_binary(0x21, checkerboard_4bpp())
        await session.request("display.image.end")
        record_view(session.state, "image", "surface")
        return
    for phase in range(8):
        await session.send_binary(0x22, checkerboard_4bpp(phase=phase))
        await asyncio.sleep(0.12)
    await session.request("display.canvas.close")
    record_view(session.state, "canvas", "surface")


async def action_rules_clear(session: ScenarioSession) -> None:
    await session.request("business.scenes.replace", {"scenes": []})
    record_view(session.state, session.state.active_view, None)


async def action_ui_chart(session: ScenarioSession) -> None:
    await session.request("display.section.set", {
        "type": "chart_section", "title": "一日温度",
        "points": [18, 19, 19, 21, 24, 26, 27, 26, 24, 22, 21, 20],
    })
    record_view(session.state, "chart_section", None)


async def action_image(session: ScenarioSession) -> None:
    await open_surface(session, "image")


async def action_canvas(session: ScenarioSession) -> None:
    await open_surface(session, "canvas")


async def scenario_audio(session: ScenarioSession) -> None:
    await install_test_scenes(session)
    await session.request("display.section.set", {
        "type": "text_section", "title": "音频测试", "text": "播放 440 Hz 提示音",
    })
    await play_tone(session)
    record_view(session.state, "text_section", None)


async def scenario_schema_recheck(session: ScenarioSession) -> None:
    session.schema_valid.clear()
    session.state.schema_status = {
        "command": {"state": "重新请求中"}, "ui": {"state": "重新请求中"},
    }
    session.state.schema_documents = {"command": None, "ui": None}
    session.state.init_phase = "重新校验 Schema"
    await session.request("device.schema.get", {"kind": "command"})
    await session.request("device.schema.get", {"kind": "ui"})
    if session.schema_valid != {"command", "ui"}:
        raise RuntimeError("Schema 重新校验失败")
    hello = session.state.last_hello or {}
    body = hello.get("body") if isinstance(hello, dict) else None
    if not isinstance(body, dict):
        raise RuntimeError("缺少 device.hello hash")
    await session.request("device.schemas.ready", {
        "command_hash": body.get("command_schema_hash"), "ui_hash": body.get("ui_schema_hash"),
    })


async def play_tone(session: ScenarioSession, cloud_response: bool = False) -> None:
    """Platform-side reaction to the local play-tone interaction event."""
    if cloud_response:
        await session.request("display.section.set", {
            "type": "text_section", "title": "Cloud response", "text": "Request accepted\nPlaying notification tone",
        })
    await session.request("audio.start", {
        "direction": "download", "format": "pcm_s16le", "sample_rate": 22050, "channels": 1,
    })
    chunk = pcm_sine_chunk(440)
    for _ in range(20):
        await session.send_binary(0x12, chunk)
        await asyncio.sleep(0.02)
    await session.request("audio.stop", {"direction": "download"})


ACTION_HANDLERS: dict[str, Callable[[ScenarioSession], Awaitable[None]]] = {
    "schemas_recheck": scenario_schema_recheck,
    "rules_replace": install_test_scenes,
    "rules_clear": action_rules_clear,
    "ui_metrics": scenario_dashboard,
    "ui_menu": scenario_menu,
    "ui_chart": action_ui_chart,
    "ui_scene_switch": scenario_scene_switch,
    "ui_menu_conflict": scenario_menu_conflict,
    "ui_invalid_scene": scenario_invalid_scene,
    "image": action_image,
    "canvas": action_canvas,
    "audio": scenario_audio,
}


async def run_action(state: DevServerState, action: str) -> None:
    session = state.active_session
    if not session:
        return
    state.log("→", f"页面控制：{action}")
    try:
        await ACTION_HANDLERS[action](session)
        state.last_test = f"{action}：通过"
    except (ConnectionError, RuntimeError, ValueError) as error:
        state.last_test = f"{action}：失败（{error}）"
        state.log("→", f"控制中断：{error}")
        print(f"控制中断：{error}")
    else:
        state.log("→", f"页面控制完成：{action}")


def start_action(state: DevServerState, action: str) -> tuple[bool, str | None]:
    if action not in ACTIONS:
        return False, "unsupported_action"
    if not state.active_session or state.active_session.connection.closed:
        return False, "device_not_connected"
    if not state.device_ready:
        return False, "device_not_ready"
    if state.action_task and not state.action_task.done():
        return False, "action_in_progress"
    state.action_task = asyncio.create_task(run_action(state, action))
    return True, None


async def http_response(writer: asyncio.StreamWriter, status: str, content_type: str, body: bytes) -> None:
    writer.write((f"HTTP/1.1 {status}\r\nContent-Type: {content_type}\r\n"
                  f"Content-Length: {len(body)}\r\nCache-Control: no-store\r\nConnection: close\r\n\r\n").encode("ascii") + body)
    await writer.drain()
    writer.close()
    await writer.wait_closed()


async def websocket_handshake(reader: asyncio.StreamReader, writer: asyncio.StreamWriter,
                              state: DevServerState) -> bool:
    request = await reader.readuntil(b"\r\n\r\n")
    lines = request.decode("iso-8859-1").split("\r\n")
    headers: dict[str, str] = {}
    for line in lines[1:]:
        if ":" in line:
            key, value = line.split(":", 1)
            headers[key.lower()] = value.strip()
    key = headers.get("sec-websocket-key")
    request_parts = lines[0].split(" ")
    method = request_parts[0] if request_parts else ""
    path = request_parts[1] if len(request_parts) >= 2 else "/"
    if headers.get("upgrade", "").lower() != "websocket":
        if path.split("?", 1)[0] == "/api/status":
            await http_response(writer, "200 OK", "application/json; charset=utf-8", state.as_json())
        elif path.split("?", 1)[0] == "/api/action" and method == "POST":
            try:
                content_length = int(headers.get("content-length", "0"))
                if content_length <= 0 or content_length > 256:
                    raise ValueError("invalid body length")
                payload = json.loads((await reader.readexactly(content_length)).decode("utf-8"))
                action = payload.get("action") if isinstance(payload, dict) else None
                if not isinstance(action, str):
                    raise ValueError("missing action")
                accepted, error = start_action(state, action)
                body = json.dumps({"ok": accepted, "error": error}, ensure_ascii=False).encode("utf-8")
                await http_response(writer, "202 Accepted" if accepted else "409 Conflict",
                                    "application/json; charset=utf-8", body)
            except (ValueError, json.JSONDecodeError, asyncio.IncompleteReadError):
                await http_response(writer, "400 Bad Request", "application/json; charset=utf-8",
                                    b'{"ok":false,"error":"invalid_request"}')
        elif path.split("?", 1)[0] == "/":
            await http_response(writer, "200 OK", "text/html; charset=utf-8", STATUS_PAGE.encode("utf-8"))
        else:
            await http_response(writer, "404 Not Found", "text/plain; charset=utf-8", b"Not found\n")
        return False
    if not lines[0].startswith("GET ") or not key or headers.get("upgrade", "").lower() != "websocket":
        writer.write(b"HTTP/1.1 400 Bad Request\r\nConnection: close\r\n\r\n")
        await writer.drain()
        writer.close()
        await writer.wait_closed()
        return False
    accept = base64.b64encode(hashlib.sha1((key + WS_GUID).encode("ascii")).digest()).decode("ascii")
    writer.write(("HTTP/1.1 101 Switching Protocols\r\n"
                  "Upgrade: websocket\r\nConnection: Upgrade\r\n"
                  f"Sec-WebSocket-Accept: {accept}\r\n\r\n").encode("ascii"))
    await writer.drain()
    return True


async def handle_client(reader: asyncio.StreamReader, writer: asyncio.StreamWriter, state: DevServerState) -> None:
    peer = writer.get_extra_info("peername")
    session: ScenarioSession | None = None
    is_device = False
    try:
        if not await websocket_handshake(reader, writer, state):
            return
        is_device = True
        state.connected_devices += 1
        state.last_peer = str(peer)
        state.device_ready = False
        state.init_phase = "等待 device.hello"
        state.schema_status = {"command": {"state": "未请求"}, "ui": {"state": "未请求"}}
        state.schema_documents = {"command": None, "ui": None}
        state.active_scene = "无"
        state.active_view = "终端本地初始化页"
        state.log("←", f"终端已连接：{peer}")
        print(f"设备已连接：{peer}；等待浏览器选择功能测试")
        session = ScenarioSession(WebSocketConnection(reader, writer), state)
        state.active_session = session
        while message := await session.connection.receive():
            opcode, payload = message
            if opcode == 0x1:
                try:
                    decoded = json.loads(payload)
                    if isinstance(decoded, dict) and "id" in decoded and "ok" in decoded:
                        session.resolve_result(decoded)
                    if isinstance(decoded, dict) and decoded.get("name") == "device.hello":
                        state.last_hello = decoded
                        state.init_phase = "已收到 device.hello，开始请求 Schema"
                        body = decoded.get("body")
                        if isinstance(body, dict):
                            command_hash = body.get("command_schema_hash")
                            ui_hash = body.get("ui_schema_hash")
                            if isinstance(command_hash, str) and isinstance(ui_hash, str):
                                async def finish_handshake() -> None:
                                    await session.request("device.schema.get", {"kind": "command"})
                                    await session.request("device.schema.get", {"kind": "ui"})
                                    if session.schema_valid != {"command", "ui"}:
                                        raise RuntimeError("Schema chunk validation failed")
                                    state.init_phase = "Schema 校验完成，确认终端 READY"
                                    await session.request("device.schemas.ready", {
                                        "command_hash": command_hash, "ui_hash": ui_hash,
                                    })
                                asyncio.create_task(finish_handshake())
                    if isinstance(decoded, dict) and decoded.get("name") == "device.schema.begin":
                        session.begin_schema(decoded.get("body"))
                    if isinstance(decoded, dict) and decoded.get("name") == "device.schema.end":
                        if not session.finish_schema(decoded.get("body")):
                            state.init_phase = "Schema 校验失败"
                    if isinstance(decoded, dict) and decoded.get("name") == "device.ready":
                        state.device_ready = True
                        state.init_phase = "READY：可执行业务测试"
                        state.log("←", "终端已完成 Schema 校验并进入 READY")
                    if isinstance(decoded, dict) and decoded.get("name") == "platform.interaction":
                        body = decoded.get("body")
                        token = body.get("token") if isinstance(body, dict) else None
                        if token == "play-tone":
                            if state.action_task and not state.action_task.done():
                                state.log("→", "忽略 play-tone：上一项控制尚未完成")
                            else:
                                state.log("→", "收到 play-tone，开始下行提示音")
                                state.action_task = asyncio.create_task(play_tone(session, cloud_response=True))
                    formatted = json.dumps(decoded, ensure_ascii=False)
                    state.log("←", formatted)
                    print(f"<- {formatted}")
                except json.JSONDecodeError:
                    text = payload.decode("utf-8", errors="replace")
                    state.log("←", text)
                    print(f"<- text {text}")
            else:
                session.append_schema_chunk(payload)
                detail = f"binary {len(payload)} byte(s), type=0x{payload[0]:02X}" if payload else "empty binary"
                state.log("←", detail)
                print(f"<- {detail}")
    except (asyncio.IncompleteReadError, ConnectionError, ValueError) as error:
        print(f"设备断开：{peer} ({error})")
    finally:
        owns_active_session = bool(session and state.active_session is session)
        if owns_active_session:
            state.active_session = None
            state.device_ready = False
        if owns_active_session and state.action_task and not state.action_task.done():
            state.action_task.cancel()
            try:
                await state.action_task
            except asyncio.CancelledError:
                pass
        if not writer.is_closing():
            writer.close()
            await writer.wait_closed()
        if is_device and state.connected_devices:
            state.connected_devices -= 1
            state.log("←", f"终端已断开：{peer}")


async def main() -> None:
    parser = argparse.ArgumentParser(description="LCD_085 v2 本地 WebSocket 功能测试台")
    parser.add_argument("--host", default="0.0.0.0", help="监听地址（默认：全部网卡）")
    parser.add_argument("--port", type=int, default=8080, help="监听端口（默认：8080）")
    args = parser.parse_args()
    state = DevServerState()
    server = await asyncio.start_server(
        lambda reader, writer: handle_client(reader, writer, state), args.host, args.port,
    )
    print(f"LCD_085 功能测试台：ws://{args.host}:{args.port}")
    print(f"状态页：http://127.0.0.1:{args.port}/；将设备 WebSocket URL 设为本机局域网 IP 后，按 Ctrl+C 停止。")
    async with server:
        await server.serve_forever()


if __name__ == "__main__":
    try:
        asyncio.run(main())
    except KeyboardInterrupt:
        pass
