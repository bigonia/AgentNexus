# 音频采集调试 — 前端接入说明

## 设计说明

音频录音遵循 Debug 模块的通用协议原语模型：

| 原语 | 含义 | 音频对应 |
|------|------|---------|
| **Command** | 发起操作 | `audio.record.start` / `audio.record.stop` |
| **Event** (SSE) | 实时观测 | 6 个 `audio.*` 事件 |
| **Session** | 进行中的活动 | `GET /sessions/audio-record` |
| **Artifact** | 已完成的产物 | `GET /artifacts/audio-record-latest` |

Session 和 Artifact 是通用端点，后续视频采集、屏幕录制等能力复用同一套，仅 `sessionId` / `artifactId` 不同。

---

## 端点总览

| 方法 | 路径 | 用途 |
|------|------|------|
| POST | `/api/v1/sdui/debug/{deviceId}/command` | 触发开始/停止录音 |
| GET | `/api/v1/sdui/debug/{deviceId}/events/stream` | SSE 实时事件流 |
| GET | `/api/v1/sdui/debug/{deviceId}/sessions` | 列出设备所有活跃会话 |
| GET | `/api/v1/sdui/debug/{deviceId}/sessions/audio-record` | 查询录音会话状态 |
| GET | `/api/v1/sdui/debug/{deviceId}/artifacts/audio-record-latest` | 获取录音产物元数据 |
| GET | `/api/v1/sdui/debug/{deviceId}/artifacts/audio-record-latest/blob` | 下载 WAV 播放 |

---

## 1. 触发录音

复用通用命令接口，和屏幕亮度、RGB 灯光同一个入口：

```bash
POST /api/v1/sdui/debug/{deviceId}/command
Content-Type: application/json
```

**开始录音：**
```json
{ "command": "audio.record.start", "params": {} }
```

**停止录音：**
```json
{ "command": "audio.record.stop", "params": {} }
```

响应为标准设备命令格式：
```json
{
  "code": 20000,
  "data": {
    "sent": true,
    "deviceId": "esp32-A1B2C3",
    "command": "audio.record.start",
    "cmdId": "cmd-xxx",
    "ackStatus": "PENDING"
  }
}
```

> `ackStatus: ACKED` 仅表示终端接受了命令，录音的真正起点是 SSE `audio.record.started` 事件。

---

## 2. SSE 实时事件流

```
GET /api/v1/sdui/debug/{deviceId}/events/stream
```

每设备仅支持一个连接，新建会顶替旧连接。

### 2.1 事件序列

完整的录音生命周期事件，按发生顺序：

| SSE event 名 | 时机 | payload 字段 |
|---|---|---|
| `connected` | 连接建立 | `deviceId`, `message` |
| `audio.record.started` | 终端确认开始采集 | `startedAt` (epoch ms) |
| `audio.record.chunk` |  PCM 分片上传进度（1Hz 节流） | `bytesReceived`, `chunkCount` |
| `audio.record.data` | 录音 PCM 上传完成 | `pcmSize`, `sampleRate` (22050), `channels` (1), `bitsPerSample` (16), `format` ("pcm_s16le") |
| `audio.stt.processing` | 开始语音识别 | `wavSize` |
| `audio.stt.result` | STT 转写完成 | `text` |

### 2.2 前端监听

```javascript
const es = new EventSource(`/api/v1/sdui/debug/${deviceId}/events/stream`);

es.addEventListener('audio.record.started', (e) => {
  console.log('录音开始', JSON.parse(e.data));
  setStatus('recording');
});

es.addEventListener('audio.record.chunk', (e) => {
  const { bytesReceived, chunkCount } = JSON.parse(e.data);
  updateProgress(bytesReceived, chunkCount);
});

es.addEventListener('audio.record.data', (e) => {
  const { pcmSize } = JSON.parse(e.data);
  setStatus('processing');
});

es.addEventListener('audio.stt.processing', () => {
  setStatus('transcribing');
});

es.addEventListener('audio.stt.result', (e) => {
  const { text } = JSON.parse(e.data);
  setTranscription(text);
});
```

---

## 3. 通用会话端点

### 3.1 录音会话状态

```
GET /api/v1/sdui/debug/{deviceId}/sessions/audio-record
```

**录音中：**
```json
{
  "code": 20000,
  "data": {
    "sessionId": "audio-record",
    "deviceId": "esp32-A1B2C3",
    "type": "audio.record",
    "status": "active",
    "startedAt": 1718612345678,
    "startedAtIso": "2026-06-21T10:00:00Z",
    "elapsedMs": 3500,
    "pcmBytes": 40960,
    "chunkCount": 20
  }
}
```

**无活跃会话：** `code: 40400`，`message: "session not found: audio-record"`

> 适用于前端刷新页面后恢复录音状态：如果 404 则说明当前未在录音。

### 3.2 列出所有活跃会话

```
GET /api/v1/sdui/debug/{deviceId}/sessions
```

```json
{
  "code": 20000,
  "data": {
    "deviceId": "esp32-A1B2C3",
    "online": true,
    "sessions": [
      {
        "sessionId": "audio-record",
        "deviceId": "esp32-A1B2C3",
        "type": "audio.record",
        "status": "active",
        "startedAt": 1718612345678,
        "startedAtIso": "2026-06-21T10:00:00Z",
        "elapsedMs": 3500,
        "pcmBytes": 40960,
        "chunkCount": 20
      }
    ]
  }
}
```

> 当前只有 `audio-record`，后续增加视频采集等能力时同设备可能出现多个 session。

---

## 4. 通用产物端点

### 4.1 录音产物元数据

```
GET /api/v1/sdui/debug/{deviceId}/artifacts/audio-record-latest
```

```json
{
  "code": 20000,
  "data": {
    "deviceId": "esp32-A1B2C3",
    "artifactId": "audio-record-latest",
    "type": "audio/recording",
    "mimeType": "audio/wav",
    "blobBytes": 88244,
    "createdAt": 1718612349678,
    "createdAtIso": "2026-06-21T10:00:05.678Z",
    "sttText": "今天天气不错",
    "pcmSize": 88200,
    "sampleRate": 22050,
    "channels": 1,
    "bitsPerSample": 16,
    "durationMs": 2000
  }
}
```

无产物时返回 `code: 40400`。只保留最近一次录音结果，新录音覆盖旧结果。

> `metadata` 中的字段（`sttText`, `pcmSize`, `durationMs` 等）随 capability 类型不同而变化，通用字段（`artifactId`, `type`, `mimeType`, `blobBytes`, `createdAt`）始终存在。

### 4.2 WAV 下载与播放

```
GET /api/v1/sdui/debug/{deviceId}/artifacts/audio-record-latest/blob
```

返回 `Content-Type: audio/wav`，可直接用于 `<audio>` 元素：

```html
<audio controls
  src="/api/v1/sdui/debug/esp32-A1B2C3/artifacts/audio-record-latest/blob">
</audio>
```

也可通过 `fetch` 下载后做波形可视化：

```javascript
const resp = await fetch(
  `/api/v1/sdui/debug/${deviceId}/artifacts/audio-record-latest/blob`
);
const wavBlob = await resp.blob();
const audioUrl = URL.createObjectURL(wavBlob);
```

---

## 5. 前端接入流程

```
页面初始化
├── GET /capabilities/{deviceId}              ← 确认设备支持 audio.record
├── EventSource /events/stream                ← 建立 SSE，注册 audio.* 监听
└── GET /sessions/audio-record                ← 检查是否有进行中的录音（页面刷新恢复）

用户点击"开始录音"
├── POST /command { audio.record.start }
├── 等待 SSE "audio.record.started"           ← 确认开始
└── UI 进入录音态：计时 + 进度条

录音中
├── SSE "audio.record.chunk" (1Hz)            ← 驱动进度条
└── (可选) GET /sessions/audio-record         ← 周期性拉取精确状态

用户点击"停止录音"
├── POST /command { audio.record.stop }
├── SSE "audio.record.data"                   ← 显示录音完成信息
├── SSE "audio.stt.processing"                ← 显示"识别中"
└── SSE "audio.stt.result" → 展示转写文本

结果验证
├── GET /artifacts/audio-record-latest        ← 拉取完整元数据（可二次确认 sttText）
└── <audio> /artifacts/.../blob              ← 播放录音
```

---

## 6. 平台侧 STT（独立调用）

如果需要在平台侧对任意音频做 STT 转写（不走终端录音流程）：

```bash
POST /api/v1/sdui/debug/{deviceId}/command
{
  "command": "audio.stt.transcribe",
  "params": {
    "audioData": "<base64-encoded-wav>",
    "format": "wav"
  }
}
```

响应：
```json
{
  "code": 20000,
  "data": {
    "sent": true,
    "status": "OK",
    "transcription": "转写文本"
  }
}
```

STT 不可用时返回 `status: "ERROR"`，`error: "STT is not available"`。

---

## 7. 注意事项

- **ACK ≠ 录音开始**：以 SSE `audio.record.started` 为准，不是 `cmd/control_ack`
- **SSE 每设备单连接**：同设备多页面同时连接会互顶，页面切换时主动 `close()`
- **产物只存一份**：新录音覆盖旧结果，如需历史须前端自行保存
- **PCM 格式固定**：22050Hz / Mono / 16-bit signed LE
- **STT 可用性**：依赖服务端安装 ffmpeg + whisper，不可用时 `audio.stt.result` 不发出，`sttText` 为空字符串
- **会话与产物的关系**：Session 表示"正在发生"（录音中），Artifact 表示"已经完成"（录音结束后的结果）。两者互斥 — 同一设备同一时刻不会有 audio-record 的 session 和 artifact 同时有效
