import asyncio
import websockets
import json
import logging
import base64
import wave
import os
import time
from datetime import datetime
from concurrent.futures import ThreadPoolExecutor

# AI 相关依赖
# Script summary:
# - Purpose: legacy AI voice gateway server (archived, not recommended as default sample).
# - Scene: speech input -> ASR -> LLM reply -> optional TTS playback.
# - Resource/dependency:
#   1) requires external model/service dependencies (openai/deepseek, whisper, edge_tts)
#   2) needs API key/environment setup before runtime
# - Validation focus:
#   1) device registration and reconnect handling
#   2) voice pipeline latency and stability
#   3) fallback behavior when cloud/local model fails
from openai import AsyncOpenAI
from faster_whisper import WhisperModel
import edge_tts

# ============================================================
#  SDUI Gateway Server — Mac Mini 优化版
# ============================================================
logging.basicConfig(
    level=logging.INFO,
    format='%(asctime)s [%(levelname)s] %(message)s'
)
logging.getLogger("websockets").setLevel(logging.WARNING)

# ---- 系统配置 ----
DEEPSEEK_API_KEY = os.getenv("DEEPSEEK_API_KEY", "sk-ce6b2df0dfa6455e9c862f033dbbb16b")
aclient = AsyncOpenAI(api_key=DEEPSEEK_API_KEY, base_url="https://api.deepseek.com")

START_TIME = time.time()
logging.info("[系统] 正在初始化本地 Whisper 模型 (Mac CPU 优化)...")
# 针对 Mac Mini 多核 CPU 优化：设置 cpu_threads
whisper_model = WhisperModel("base", device="cpu", compute_type="int8", cpu_threads=4)
executor = ThreadPoolExecutor(max_workers=4)
logging.info("[系统] 模型就绪")

# ---- 设备注册表 ----
devices: dict = {}

def get_or_create_device(device_id, websocket, remote):
    if device_id not in devices:
        devices[device_id] = {
            "ws": websocket,
            "addr": str(remote),
            "voice_enabled": False,  # 默认不播放语音
            "dial_value": 65,
            "audio_buffer": bytearray(),
            "messages": [],
            "stats": {"rounds": 0, "total_tokens": 0}
        }
    else:
        devices[device_id]["ws"] = websocket
    return devices[device_id]

# ============================================================
#  SDUI 布局构建器
# ============================================================
def build_chat_bubble(text, is_user=False):
    bg_color = "#2ecc71" if is_user else "#333333"
    return {
        "type": "container", "w": "full", "h": "content",
        "bg_color": bg_color, "radius": 10, "pad": 10,
        "flex": "column", "children": [
            {"type": "label", "text": text, "font_size": 16, "text_color": "#ffffff", "w": "full", "long_mode": "scroll"}
        ]
    }

def build_ai_layout(device_state):
    stats = device_state["stats"]
    voice_on = device_state["voice_enabled"]
    display_msgs = [m for m in device_state["messages"] if m["role"] != "system"]
    dial_value = device_state.get("dial_value", 65)

    bubble_children = []
    if not display_msgs:
        bubble_children.append({"type": "label", "text": "等待唤醒...", "align": "center", "text_color": "#888888"})
    else:
        for msg in display_msgs:
            bubble_children.append(build_chat_bubble(msg["content"], is_user=(msg["role"]=="user")))

    return {
        "flex": "column", "justify": "start", "align_items": "center", "gap": 10,
        "children": [
            {"type": "label", "id": "status_label", "text": "系统就绪", "font_size": 16, "text_color": "#f1c40f"},
            {
                "type": "container", "w": "95%", "h": 220,
                "children": [
                    {
                        "type": "widget", "widget_type": "dial", "id": "dial_energy",
                        "w": 220, "h": 220, "align": "center", "z": 0,
                        "theme": "cyberpunk_hud",
                        "min": 0, "max": 100, "value": dial_value,
                        "value_format": "%ld%%",
                        "throttle_ms": 160,
                        "bind": {"text_id": "dial_energy_value"},
                        "on_change_final": "server://ui/dial_final"
                    },
                    {
                        "type": "label", "id": "dial_energy_value", "text": f"{dial_value}%",
                        "align": "center", "y": -10, "z": 1,
                        "font_size": 24, "text_color": "#f7f3ff",
                        "pointer_events": "none"
                    },
                    {
                        "type": "label", "text": "Energy Dial", "align": "center", "y": 56, "z": 1,
                        "font_size": 14, "text_color": "#77ddee",
                        "pointer_events": "none"
                    }
                ]
            },
            {
                "type": "container", "flex": "row", "justify": "space_between", "w": "90%",
                "children": [
                    {"type": "label", "text": f"轮数: {stats['rounds']}", "font_size": 14, "text_color": "#aaaaaa"},
                    {"type": "label", "text": f"Voice: {'ON' if voice_on else 'OFF'}", "font_size": 14, "text_color": "#aaaaaa"}
                ]
            },
            {
                "type": "container", "id": "scroll_box", "scrollable": True, "w": "95%", "h": 240,
                "flex": "column", "gap": 10, "bg_color": "#111111", "pad": 10, "radius": 10,
                "children": bubble_children
            },
            {
                "type": "container", "flex": "row", "gap": 10, "w": "full", "justify": "center",
                "children": [
                    {
                        "type": "button", "id": "btn_voice_toggle",
                        "text": "语音: 开" if voice_on else "语音: 关",
                        "w": 90, "h": 45, "radius": 20,
                        "bg_color": "#27ae60" if voice_on else "#7f8c8d",
                        "on_click": "server://ui/toggle_voice"
                    },
                    {
                        "type": "button", "id": "btn_rec", "text": "按住说话",
                        "w": 120, "h": 45, "radius": 20, "bg_color": "#3498db",
                        "on_press": "local://audio/cmd/record_start",
                        "on_release": "local://audio/cmd/record_stop"
                    },
                    {
                        "type": "button", "id": "btn_new", "text": "重置",
                        "w": 70, "h": 45, "radius": 20, "bg_color": "#e74c3c",
                        "on_click": "server://ui/new_chat"
                    }
                ]
            }
        ]
    }

# ============================================================
#  核心业务逻辑
# ============================================================
async def send_topic(ws, topic: str, payload):
    if ws:
        await ws.send(json.dumps({"topic": topic, "payload": payload}, ensure_ascii=False))

async def process_chat_round(ws, device_id, device_state):
    pipeline_start = time.time()
    audio_data = bytes(device_state["audio_buffer"])
    device_state["audio_buffer"].clear()

    if len(audio_data) < 5000: return

    try:
        await send_topic(ws, "ui/update", {"id": "status_label", "text": "识别中..."})

        # STT 识别
        loop = asyncio.get_running_loop()
        stt_start = time.time()
        user_text = await loop.run_in_executor(executor, lambda: "".join([s.text for s in whisper_model.transcribe(wave_bytes_to_file(audio_data))[0]]))
        logging.info(f"[{device_id}] STT 识别完成 (耗时 {time.time()-stt_start:.2f}s): '{user_text}'")

        if not user_text.strip():
            await send_topic(ws, "ui/update", {"id": "status_label", "text": "未听清"})
            logging.info(f"[{device_id}] STT 识别结果为空，已丢弃")
            return

        device_state["messages"].append({"role": "user", "content": user_text})
        await send_topic(ws, "ui/layout", build_ai_layout(device_state))

        # LLM 请求
        logging.info(f"[{device_id}] 开始请求 DeepSeek LLM...")
        llm_start = time.time()
        response = await aclient.chat.completions.create(
            model="deepseek-chat",
            messages=[{"role": "system", "content": "简短口语回答"}] + device_state["messages"],
            stream=True
        )

        full_ai_text = ""
        sentence_queue = asyncio.Queue()

        # 语音播放控制逻辑
        async def tts_worker():
            if not device_state["voice_enabled"]:
                logging.info(f"[{device_id}] TTS 已关闭，跳过语音合成")
                return
            while True:
                sentence = await sentence_queue.get()
                if sentence is None: break
                await run_tts_pipeline(ws, sentence)
                sentence_queue.task_done()

        tts_task = asyncio.create_task(tts_worker())

        first_token_time = None
        async for chunk in response:
            if chunk.choices[0].delta.content:
                if first_token_time is None:
                    first_token_time = time.time()
                    logging.info(f"[{device_id}] LLM 首字延迟 (TTFT): {first_token_time - llm_start:.2f}s")
                    
                text = chunk.choices[0].delta.content
                full_ai_text += text
                if any(p in text for p in "。！？；"):
                    sentence = full_ai_text.splitlines()[-1]
                    logging.info(f"[{device_id}] LLM 生成整句并推入 TTS 队列: {sentence}")
                    await sentence_queue.put(sentence) # 粗略切句

        await sentence_queue.put(None)
        await tts_task

        logging.info(f"[{device_id}] 一轮交互完成，AI 回复: '{full_ai_text}'")
        
        device_state["messages"].append({"role": "assistant", "content": full_ai_text})
        device_state["stats"]["rounds"] += 1
        await send_topic(ws, "ui/layout", build_ai_layout(device_state))
        await send_topic(ws, "ui/update", {"id": "status_label", "text": "就绪"})

    except Exception as e:
        logging.error(f"Pipeline Error: {e}")

# ---- 辅助函数 ----
def wave_bytes_to_file(data):
    path = f"tmp_{time.time()}.wav"
    with wave.open(path, 'wb') as f:
        f.setnchannels(1); f.setsampwidth(2); f.setframerate(16000); f.writeframes(data)
    return path

async def run_tts_pipeline(ws, text):
    logging.info(f"[{ws.remote_address}] 开始 TTS: {text}")
    try:
        communicate = edge_tts.Communicate(text, "zh-CN-XiaoxiaoNeural")
        
        process = await asyncio.create_subprocess_exec(
            'ffmpeg', '-i', 'pipe:0', '-f', 's16le', '-acodec', 'pcm_s16le', '-ar', '16000', '-ac', '1', 'pipe:1',
            stdin=asyncio.subprocess.PIPE, stdout=asyncio.subprocess.PIPE, stderr=asyncio.subprocess.DEVNULL
        )

        async def feed_ffmpeg():
            try:
                async for chunk in communicate.stream():
                    if chunk["type"] == "audio":
                        process.stdin.write(chunk["data"])
                        await process.stdin.drain()
            except Exception as e:
                logging.error(f"TTS Stream Error: {e}")
            finally:
                process.stdin.close()
                await process.stdin.wait_closed()

        async def read_ffmpeg():
            while True:
                chunk = await process.stdout.read(1024)
                if not chunk:
                    break
                # 发送音频切片到设备
                b64_audio = base64.b64encode(chunk).decode('utf-8')
                await send_topic(ws, "audio/play", b64_audio)

        await asyncio.gather(feed_ffmpeg(), read_ffmpeg(), process.wait())
        logging.info(f"[{ws.remote_address}] TTS 完成")
        
    except Exception as e:
        logging.error(f"TTS Pipeline Error: {e}")
# ============================================================
#  网关路由
# ============================================================
async def sdui_handler(websocket):
    remote = websocket.remote_address
    dev_id = None
    try:
        async for message in websocket:
            data = json.loads(message)
            topic = data.get("topic")
            payload = data.get("payload", {})
            dev_id = data.get("device_id") or dev_id

            if not dev_id: continue
            state = get_or_create_device(dev_id, websocket, remote)

            # 1. 验证性接口 Ping-Pong
            if topic == "sys/ping":
                await send_topic(websocket, "sys/pong", {
                    "uptime": int(time.time() - START_TIME),
                    "status": "online",
                    "stt_ready": True,
                    "server_time": datetime.now().strftime("%Y-%m-%d %H:%M:%S")
                })
                continue

            # 2. 语音开关切换
            if topic == "ui/toggle_voice":
                state["voice_enabled"] = not state["voice_enabled"]
                logging.info(f"[{dev_id}] 语音状态切换: {state['voice_enabled']}")
                await send_topic(websocket, "ui/layout", build_ai_layout(state))
                continue

            if topic == "ui/dial_final":
                state["dial_value"] = int(payload.get("value", state.get("dial_value", 65)))
                await send_topic(websocket, "ui/update", {
                    "id": "status_label",
                    "text": f"Dial set to {state['dial_value']}%"
                })
                continue

            # 3. 基础业务逻辑
            if topic == "telemetry/heartbeat":
                if not hasattr(websocket, 'init'):
                    websocket.init = True
                    await send_topic(websocket, "ui/layout", build_ai_layout(state))

            elif topic == "audio/record":
                audio_state = payload.get("state")
                if audio_state == "start":
                    state["audio_buffer"].clear()
                    ctx = payload.get("context", "")
                    if ctx:
                        state["current_context"] = ctx
                elif audio_state == "stream":
                    try:
                        state["audio_buffer"].extend(base64.b64decode(payload.get("data", "")))
                    except Exception as e:
                        logging.warning(f"[{dev_id}] Audio Stream B64 Decode Error: {e}")
                elif audio_state == "stop":
                    # Store-and-Forward: start → stream × N → stop 严格有序到达
                    logging.info(f"[{dev_id}] Audio upload complete, {len(state['audio_buffer'])} bytes, starting STT...")
                    asyncio.create_task(process_chat_round(websocket, dev_id, state))

            elif topic == "ui/new_chat":
                state["messages"].clear()
                state["stats"] = {"rounds": 0, "total_tokens": 0}
                await send_topic(websocket, "ui/layout", build_ai_layout(state))

    except websockets.exceptions.ConnectionClosed:
        pass
    except Exception as e:
        import traceback
        logging.error(f"[{remote}] 致命错误导致 WebSocket 异常退出: {repr(e)}")
        traceback.print_exc()
    finally: logging.info(f"断开: {remote}")

async def main():
    # ★ 禁用服务器端的自动 Ping/Pong 机制！
    # Python websockets 库默认每 20 秒发送 Ping 帧并等待 Pong 回复，
    # 如果 ESP32 正在跑 WakeNet 语音唤醒模型（CPU 密集），会来不及回复 Pong，
    # 导致服务器主动关闭 TCP 连接 → 设备端报 "Error transport_poll_write(0)"！
    # 由设备端的 esp_websocket_client (.ping_interval_sec = 5) 来维持心跳即可。
    async with websockets.serve(
        sdui_handler, "0.0.0.0", 8080,
        ping_interval=None,   # 禁用服务器主动 Ping
        ping_timeout=None,    # 禁用 Pong 超时检测
        close_timeout=60,     # 延长 TCP 优雅关闭超时
        max_size=2**20,       # 1MB 最大消息体
    ):
        await asyncio.Future()

if __name__ == "__main__":
    asyncio.run(main())

