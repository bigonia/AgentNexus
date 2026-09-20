#!/usr/bin/env python3
"""A dependency-free LCD_085 terminal simulator for platform WebSocket tests."""

from __future__ import annotations

import argparse
import asyncio
import base64
import hashlib
import json
import os
import ssl
import struct
from urllib.parse import parse_qsl, urlencode, urlsplit


CAPABILITY_SCHEMA = {
    "protocol_version": "lcd085.v2", "schema_version": 2,
    "triggers": ["button.pwr.down", "button.pwr.up", "button.pwr.short_press", "button.pwr.long_press",
                 "button.plus.down", "button.plus.up", "button.plus.short_press", "button.plus.long_press",
                 "platform.trigger.*"],
    "responses": ["audio.record.start", "audio.record.stop", "audio.record.toggle", "feedback.rgb.set",
                  "feedback.rgb.off", "platform.interaction.report"],
    "requests": ["business.reset", "business.scenes.replace", "business.trigger", "system.volume.set",
                 "system.brightness.set", "display.section.set", "display.image.begin", "display.image.end",
                 "display.canvas.open", "display.canvas.close", "system.provisioning.start", "system.reboot", "audio.start",
                 "audio.stop", "device.schema.get", "device.schemas.ready"],
    "sections": ["status_section", "metrics_section", "text_section", "menu_section", "chart_section"],
    "indexed_surface": {"sizes": [16, 32, 64, 128], "palette_sizes": [2, 4, 16],
                        "bits_per_index": [1, 2, 4], "max_frame_bytes": 8192},
}
CAPABILITY_HASH = hashlib.sha256(json.dumps(CAPABILITY_SCHEMA, separators=(",", ":")).encode()).hexdigest()
UI_SCHEMA = {
    "ui_schema_version": 1, "primary_view": {"single": True, "modes": ["template", "image", "canvas"]},
    "templates": ["text_section", "status_section", "metrics_section", "menu_section", "chart_section"],
    "menu_native_input": {"button.plus.short_press": "focus_next", "button.pwr.short_press": "activate_selected"},
    "indexed_surface": {"sizes": [16, 32, 64, 128], "bits_per_index": [1, 2, 4], "full_frame_only": True},
}
UI_SCHEMA_HASH = hashlib.sha256(json.dumps(UI_SCHEMA, separators=(",", ":")).encode()).hexdigest()


class WebSocket:
    def __init__(self, reader: asyncio.StreamReader, writer: asyncio.StreamWriter) -> None:
        self.reader, self.writer = reader, writer
        self.closed = False
        self._send_lock = asyncio.Lock()
        self._fragment_opcode: int | None = None
        self._fragment = bytearray()

    async def send_frame(self, opcode: int, payload: bytes) -> None:
        if self.closed:
            return
        mask = os.urandom(4)
        length = len(payload)
        if length < 126:
            header = bytes((0x80 | opcode, 0x80 | length))
        elif length <= 0xFFFF:
            header = bytes((0x80 | opcode, 0x80 | 126)) + struct.pack("!H", length)
        else:
            header = bytes((0x80 | opcode, 0x80 | 127)) + struct.pack("!Q", length)
        masked = bytes(value ^ mask[index % 4] for index, value in enumerate(payload))
        async with self._send_lock:
            self.writer.write(header + mask + masked)
            await self.writer.drain()

    async def send_json(self, value: dict[str, object]) -> None:
        encoded = json.dumps(value, ensure_ascii=False, separators=(",", ":")).encode()
        print("→", encoded.decode())
        await self.send_frame(0x1, encoded)

    async def receive(self) -> tuple[int, bytes] | None:
        while not self.closed:
            header = await self.reader.readexactly(2)
            fin, opcode = bool(header[0] & 0x80), header[0] & 0x0F
            masked, length = bool(header[1] & 0x80), header[1] & 0x7F
            if length == 126:
                length = struct.unpack("!H", await self.reader.readexactly(2))[0]
            elif length == 127:
                length = struct.unpack("!Q", await self.reader.readexactly(8))[0]
            if length > 1024 * 1024:
                raise ValueError("platform frame exceeds simulator limit")
            mask = await self.reader.readexactly(4) if masked else b""
            payload = bytearray(await self.reader.readexactly(length))
            if masked:
                for index in range(length):
                    payload[index] ^= mask[index % 4]
            if opcode == 0x8:
                self.closed = True
                return None
            if opcode == 0x9:
                await self.send_frame(0xA, bytes(payload))
                continue
            if opcode == 0xA:
                continue
            if opcode in (0x1, 0x2):
                self._fragment_opcode, self._fragment = opcode, payload
            elif opcode == 0x0 and self._fragment_opcode is not None:
                self._fragment.extend(payload)
            else:
                raise ValueError(f"unsupported websocket opcode 0x{opcode:02x}")
            if fin and self._fragment_opcode is not None:
                result = self._fragment_opcode, bytes(self._fragment)
                self._fragment_opcode, self._fragment = None, bytearray()
                return result
        return None


class TerminalSimulator:
    def __init__(self, ws: WebSocket, args: argparse.Namespace) -> None:
        self.ws, self.args = ws, args
        self.bindings: dict[str, list[dict[str, object]]] = {}
        self.scenes: dict[str, dict[str, list[dict[str, object]]]] = {}
        self.active_scene: str | None = None
        self.ready = False
        self.recording = False
        self.trigger_scheduled = False

    async def result(self, request_id: object, ok: bool = True, error: str | None = None) -> None:
        value: dict[str, object] = {"id": request_id if isinstance(request_id, str) else "", "ok": ok}
        if error:
            value["error"] = error
        await self.ws.send_json(value)

    async def report(self, token: str) -> None:
        await self.ws.send_json({"name": "platform.interaction", "body": {"token": token}})

    async def send_schema(self, kind: str, schema: dict[str, object], schema_hash: str) -> None:
        raw = json.dumps(schema, separators=(",", ":")).encode()
        chunks = [raw[index:index + 768] for index in range(0, len(raw), 768)]
        await self.ws.send_json({"name": "device.schema.begin", "body": {
            "kind": kind, "hash": schema_hash, "length": len(raw), "chunks": len(chunks)}})
        for index, chunk in enumerate(chunks):
            await self.ws.send_frame(0x2, bytes((0x31, 1 if kind == "ui" else 0,
                                                 index >> 8, index & 0xff)) + chunk)
        await self.ws.send_json({"name": "device.schema.end", "body": {"kind": kind, "hash": schema_hash}})

    async def stream_upload(self) -> None:
        for _ in range(self.args.upload_frames):
            if not self.recording or self.ws.closed:
                return
            await self.ws.send_frame(0x2, b"\x11" + bytes(882))
            print("→ Binary 0x11, 883 bytes")
            await asyncio.sleep(0.02)

    async def execute_trigger(self, trigger: str) -> None:
        responses = self.bindings.get(trigger) if self.ready else None
        if not responses:
            print(f"! no configured binding for {trigger}")
            return
        print(f"* trigger {trigger}")
        for response in responses:
            name, body = response.get("name"), response.get("body")
            if name == "audio.record.start":
                self.recording = True
                asyncio.create_task(self.stream_upload())
            elif name == "audio.record.stop":
                self.recording = False
            elif name == "audio.record.toggle":
                self.recording = not self.recording
                if self.recording:
                    asyncio.create_task(self.stream_upload())
            elif name == "platform.interaction.report" and isinstance(body, dict) and isinstance(body.get("token"), str):
                await self.report(body["token"])
            elif name in ("feedback.rgb.set", "feedback.rgb.off"):
                print(f"* simulated {name}")
            else:
                print(f"! unsupported simulated response: {name}")

    async def schedule_trigger(self) -> None:
        await asyncio.sleep(self.args.trigger_after)
        if not self.ws.closed:
            await self.execute_trigger(self.args.trigger)

    async def handle(self, request: dict[str, object]) -> None:
        request_id, name = request.get("id"), request.get("name")
        body = request.get("body")
        if not isinstance(name, str):
            return
        if name == "device.schema.get":
            kind = body.get("kind") if isinstance(body, dict) else None
            if kind == "command":
                await self.send_schema("command", CAPABILITY_SCHEMA, CAPABILITY_HASH)
            elif kind == "ui":
                await self.send_schema("ui", UI_SCHEMA, UI_SCHEMA_HASH)
            else:
                await self.result(request_id, False, "invalid_request")
                return
        elif name == "device.schemas.ready":
            if not isinstance(body, dict) or body.get("command_hash") != CAPABILITY_HASH or body.get("ui_hash") != UI_SCHEMA_HASH:
                await self.result(request_id, False, "schema_mismatch")
                return
            self.ready = True
            await self.ws.send_json({"name": "device.ready", "body": {"protocol_version": "lcd085.v2"}})
        elif name == "business.reset":
            self.bindings.clear()
            self.recording = False
        elif name == "business.scenes.replace":
            scenes = body.get("scenes") if isinstance(body, dict) else None
            if not isinstance(scenes, list):
                await self.result(request_id, False, "invalid_config")
                return
            parsed: dict[str, dict[str, list[dict[str, object]]]] = {}
            for scene in scenes:
                if not isinstance(scene, dict) or not isinstance(scene.get("id"), str) or not isinstance(scene.get("bindings"), list):
                    await self.result(request_id, False, "invalid_config")
                    return
                parsed[scene["id"]] = {item["trigger"]: item.get("responses", []) for item in scene["bindings"]
                                       if isinstance(item, dict) and isinstance(item.get("trigger"), str)
                                       and isinstance(item.get("responses"), list)}
            self.scenes, self.active_scene, self.bindings = parsed, None, {}
            if self.args.trigger and not self.trigger_scheduled:
                self.trigger_scheduled = True
                asyncio.create_task(self.schedule_trigger())
        elif name in ("display.section.set", "display.image.begin", "display.canvas.open"):
            scene_id = body.get("scene_id") if isinstance(body, dict) else None
            if scene_id is not None and (not isinstance(scene_id, str) or scene_id not in self.scenes):
                await self.result(request_id, False, "scene_not_found")
                return
            if (name == "display.section.set" and isinstance(body, dict) and
                    body.get("type") == "menu_section" and isinstance(scene_id, str)):
                bindings = self.scenes[scene_id]
                if ("button.plus.short_press" in bindings or
                        "button.pwr.short_press" in bindings):
                    await self.result(request_id, False, "ui_rule_conflict")
                    return
            self.active_scene = scene_id
            self.bindings = self.scenes.get(scene_id, {}) if scene_id else {}
        elif name == "business.trigger":
            token = body.get("token") if isinstance(body, dict) else None
            if not isinstance(token, str):
                await self.result(request_id, False, "invalid_request")
                return
            await self.execute_trigger(f"platform.trigger.{token}")
        elif name == "audio.start":
            direction = body.get("direction") if isinstance(body, dict) else None
            if direction not in ("upload", "download"):
                await self.result(request_id, False, "invalid_request")
                return
        elif name not in CAPABILITY_SCHEMA["requests"]:
            await self.result(request_id, False, "unsupported_request")
            return
        await self.result(request_id)


async def connect(url: str, device_id: str, insecure: bool) -> WebSocket:
    parsed = urlsplit(url)
    if parsed.scheme not in ("ws", "wss") or not parsed.hostname:
        raise ValueError("URL must use ws:// or wss:// and include a host")
    secure = parsed.scheme == "wss"
    port = parsed.port or (443 if secure else 80)
    context = ssl._create_unverified_context() if secure and insecure else (ssl.create_default_context() if secure else None)
    reader, writer = await asyncio.open_connection(parsed.hostname, port, ssl=context,
                                                  server_hostname=parsed.hostname if secure and not insecure else None)
    query = dict(parse_qsl(parsed.query, keep_blank_values=True))
    query.setdefault("deviceId", device_id)
    path = (parsed.path or "/") + "?" + urlencode(query)
    key = base64.b64encode(os.urandom(16)).decode()
    request = (f"GET {path} HTTP/1.1\r\nHost: {parsed.netloc}\r\nUpgrade: websocket\r\n"
               f"Connection: Upgrade\r\nSec-WebSocket-Key: {key}\r\nSec-WebSocket-Version: 13\r\n\r\n")
    writer.write(request.encode())
    await writer.drain()
    response = await reader.readuntil(b"\r\n\r\n")
    if not response.startswith(b"HTTP/1.1 101"):
        raise ConnectionError(response.decode("iso-8859-1", errors="replace").split("\r\n", 1)[0])
    return WebSocket(reader, writer)


async def main() -> None:
    parser = argparse.ArgumentParser(description="LCD_085 platform WebSocket terminal simulator")
    parser.add_argument("url", help="platform WebSocket URL, e.g. wss://host/ws")
    parser.add_argument("--device-id", default="LCD085-SIM-001")
    parser.add_argument("--trigger", help="fire this local trigger after the first rule replacement")
    parser.add_argument("--trigger-after", type=float, default=1.0, help="seconds before simulated trigger")
    parser.add_argument("--upload-frames", type=int, default=10, help="PCM frames after a record-start response")
    parser.add_argument("--insecure", action="store_true", help="disable TLS verification for test certificates")
    args = parser.parse_args()
    ws = await connect(args.url, args.device_id, args.insecure)
    simulator = TerminalSimulator(ws, args)
    await ws.send_json({"name": "device.hello", "body": {"protocol_version": "lcd085.v2",
                       "command_schema_hash": CAPABILITY_HASH, "ui_schema_hash": UI_SCHEMA_HASH}})
    try:
        while message := await ws.receive():
            opcode, payload = message
            if opcode == 0x2:
                print(f"← Binary 0x{payload[0]:02x}, {len(payload)} bytes" if payload else "← empty Binary")
                continue
            decoded = json.loads(payload)
            print("←", json.dumps(decoded, ensure_ascii=False))
            if isinstance(decoded, dict):
                await simulator.handle(decoded)
    except (asyncio.IncompleteReadError, ConnectionError, ValueError, json.JSONDecodeError) as error:
        print(f"connection ended: {error}")
    finally:
        ws.closed = True
        ws.writer.close()
        await ws.writer.wait_closed()


if __name__ == "__main__":
    asyncio.run(main())
