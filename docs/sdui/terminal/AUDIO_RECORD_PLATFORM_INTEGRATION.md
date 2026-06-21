# 音频采集协议平台侧接入说明

本文仅说明当前固件的音频采集新实现，平台接入时请以本文为准，不需要兼容旧版 Base64 WAV JSON 方案。

## 1. 设计概览

当前实现将音频采集分为两条通道：

- 控制面：平台通过 `cmd/control` 下发开始/停止录音命令，终端通过 `cmd/control_ack` 返回命令处理结果。
- 数据面：终端通过 `audio/record` 文本消息上报录音流开始/结束，通过 UI3 Binary 二进制帧上报录音 PCM 分片。

音频流不是 JSON，也不是 WAV 文件整包上传，而是：

1. `audio/record` `state=start`
2. 若干个 `AUDIO_RECORD_CHUNK` 二进制分片
3. `audio/record` `state=stop`

平台侧应按上述顺序组装一段完整录音。

## 2. 控制命令

### 2.1 开始录音

平台下发到 `cmd/control`：

```json
{
  "cmd_id": "rec-001",
  "action": "audio_record_start"
}
```

### 2.2 停止录音

平台下发到 `cmd/control`：

```json
{
  "cmd_id": "rec-002",
  "action": "audio_record_stop"
}
```

## 3. ACK 语义

终端返回 topic `cmd/control_ack`。

### 3.1 `audio_record_start`

成功：

```json
{
  "cmd_id": "rec-001",
  "action": "audio_record_start",
  "status": "ACKED",
  "reason": "",
  "requested_value": 0,
  "ts": 1718612345678
}
```

拒绝：

```json
{
  "cmd_id": "rec-001",
  "action": "audio_record_start",
  "status": "REJECTED",
  "reason": "already_recording",
  "requested_value": 0,
  "ts": 1718612345678
}
```

可能的 `reason`：

- `already_recording`：终端已经处于录音中。
- `audio_busy`：终端仍在处理上一段录音的上传收尾，暂时不能开启新录音。
- `record_start_failed`：启动录音失败，对应 `status=ERROR`。

### 3.2 `audio_record_stop`

成功：

```json
{
  "cmd_id": "rec-002",
  "action": "audio_record_stop",
  "status": "ACKED",
  "reason": "",
  "requested_value": 0,
  "ts": 1718612356789
}
```

说明：

- `audio_record_stop` 是幂等的。
- 即使当前没有在录音，终端也返回 `ACKED`。
- `record_stop_failed` 仅在极少数内部错误时出现，对应 `status=ERROR`。

## 4. `cmd_id` 的作用

`cmd_id` 只用于控制命令与 ACK 的关联，不参与录音流会话管理。

当前终端是无状态单工模型：

- 一次只能处理一段录音。
- 录音过程中新的 `audio_record_start` 会被直接拒绝。
- 平台不需要维护额外会话号，不需要把 `cmd_id` 透传到音频分片流中。

## 5. 上行数据流

## 5.1 录音开始标记

终端开始实际采集后，上报文本消息：

```json
{
  "topic": "audio/record",
  "device_id": "<device_id>",
  "payload": {
    "state": "start"
  }
}
```

平台收到该消息后，应创建一段新的录音缓存。

## 5.2 音频分片

终端通过 UI3 Binary 帧上报音频分片：

- `msg_type = 18`
- 消息名：`AUDIO_RECORD_CHUNK`
- 消息体：原始 PCM 字节流

分片内容不是 JSON，不带 WAV 头，不带 Base64。

平台侧需要复用已有 UI3 Binary 解帧逻辑，识别 `msg_type=18` 后，将 payload 原样追加到当前录音缓存。

### 当前音频格式

- 采样率：`22050`
- 声道数：`1`
- 位深：`16-bit`
- 数据格式：`PCM signed little-endian`

可按 `pcm_s16le / 22050 Hz / mono` 理解。

### 当前分片大小

- 单个发送分片最大约 `2048` 字节。
- 平台不要假设每片固定长度，应按收到的实际 payload 长度追加。

## 5.3 录音结束标记

终端在本段录音的所有 PCM 分片发送完成后，上报文本消息：

```json
{
  "topic": "audio/record",
  "device_id": "<device_id>",
  "payload": {
    "state": "stop"
  }
}
```

平台收到 `state=stop` 后，才应将当前缓存视为一段完整录音。

如果终端因为缓冲区写满而被迫中止录音，则会上报：

```json
{
  "topic": "audio/record",
  "device_id": "<device_id>",
  "payload": {
    "state": "stop",
    "reason": "buffer_full"
  }
}
```

建议平台将 `buffer_full` 视为“录音被截断但数据仍可用”。

## 6. 时序约定

平台侧要特别区分“命令已受理”和“音频流真实开始/结束”：

- `audio_record_start` 的 `ACKED` 仅表示终端接受了开始录音命令。
- 终端会先播放开始提示音，再真正进入采集。
- 平台应以 `audio/record state=start` 作为“真实开始录音”的判定点。

- `audio_record_stop` 的 `ACKED` 仅表示终端接受了停止命令。
- 停止命令后，终端可能还会继续发送缓冲区中尚未发完的 PCM 分片。
- 平台应以 `audio/record state=stop` 作为“整段录音上传完成”的判定点。

如果平台在开始提示音期间立即下发 `audio_record_stop`，终端会直接取消本次录音；这种情况下可能不会产生任何 `audio/record start/stop` 和音频分片。

## 7. 平台推荐处理流程

1. 平台发送 `audio_record_start`。
2. 收到 `cmd/control_ack`，确认命令是否被接受。
3. 等待 `audio/record state=start`，创建本段录音缓存。
4. 收到每个 `AUDIO_RECORD_CHUNK` 后，按顺序追加原始 PCM 数据。
5. 平台发送 `audio_record_stop`。
6. 收到 `cmd/control_ack` 后继续等待剩余分片。
7. 收到 `audio/record state=stop` 后结束组包。
8. 如业务需要，再由平台将 PCM 封装为 WAV 或转码为其他格式。

## 8. 平台侧注意事项

- 不要把 `cmd/control_ack` 当作录音流开始/结束标志。
- 不要期待终端上报 WAV 文件。
- 不要期待每个二进制分片都带 `cmd_id`。
- 不要假设音频分片长度固定。
- 如果在未收到 `state=start` 前收到新的开始命令结果，应以 ACK 为准处理冲突。
- 如果收到 `state=stop` 且带 `reason=buffer_full`，应允许业务侧决定是保留截断音频，还是提示用户重试。

## 9. 平台侧最小兼容要求

平台若要完成接入，至少需要具备以下能力：

- 能发送 `cmd/control` JSON 命令。
- 能接收并解析 `cmd/control_ack`。
- 能接收 `audio/record` 文本消息。
- 能解析 UI3 Binary 帧中的 `msg_type=18`。
- 能按顺序缓存 PCM 分片，并在 `state=stop` 后完成组包。

