package com.zwbd.agentnexus.sdui.v2.audio;

import com.zwbd.agentnexus.sdui.v2.OperationResult;
import com.zwbd.agentnexus.sdui.v2.protocol.BinaryDataType;
import com.zwbd.agentnexus.sdui.v2.protocol.ProtocolErrors;
import com.zwbd.agentnexus.sdui.v2.protocol.V2Names;
import com.zwbd.agentnexus.sdui.v2.transport.DeviceSender;
import com.zwbd.agentnexus.sdui.v2.transport.PlatformRequestService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.concurrent.CompletableFuture;

/**
 * 下行音频下发。
 *
 * <p>04_PROTOCOL_MODEL.md §7：{@code JSON audio.start → Binary audio data → JSON audio.stop / audio.abort}。
 * 平台发起 {@code audio.start} 表示下行播放；上行录音由终端在本地响应序列中自行开始（例如
 * {@code button.down → audio.record.start}），并通过 {@code audio.start} 事件宣告，平台只跟踪状态。</p>
 *
 * <p>§7 同时规定首期不使用 {@code stream_id}、分片序号、确认位置、重传；跨连接只依赖设备唯一
 * 活动音频状态和有界缓冲继续传输。</p>
 */
@Slf4j
@Service
public class AudioCommandService {

    private final AudioStreamService streams;
    private final PlatformRequestService requests;
    private final DeviceSender sender;

    public AudioCommandService(AudioStreamService streams,
                               PlatformRequestService requests,
                               DeviceSender sender) {
        this.streams = streams;
        this.requests = requests;
        this.sender = sender;
    }

    /**
     * 开始下行播放。
     *
     * <p>01§5：录音与播放互斥，正在录音时请求播放默认返回 {@code audio_busy}，不自动抢占。
     * 本地状态先落定，避免出现"终端已开始播放但平台仍认为在录音"的错位。</p>
     */
    public CompletableFuture<PlatformRequestService.Outcome> startDownlink(String deviceId) {
        OperationResult local = streams.start(deviceId, AudioStreamService.Direction.DOWNLINK);
        if (!local.ok()) {
            return CompletableFuture.completedFuture(PlatformRequestService.Outcome.failure(local.error()));
        }
        return requests.send(deviceId, V2Names.AUDIO_START, Map.of())
                .thenApply(outcome -> {
                    if (!outcome.ok()) {
                        // 终端拒绝时回滚本地状态，避免状态悬挂
                        streams.abort(deviceId, outcome.error());
                    }
                    return outcome;
                });
    }

    /** 发送一段下行 PCM。无活动下行流时返回 false，数据被丢弃。 */
    public boolean sendDownlinkAudio(String deviceId, byte[] pcm) {
        if (streams.directionOf(deviceId) != AudioStreamService.Direction.DOWNLINK) {
            log.debug("无活动下行音频流，PCM 丢弃: device={}", deviceId);
            return false;
        }
        return sender.sendBinary(deviceId, BinaryDataType.AUDIO, pcm);
    }

    /** 正常结束下行播放。 */
    public CompletableFuture<PlatformRequestService.Outcome> stopDownlink(String deviceId) {
        if (streams.directionOf(deviceId) != AudioStreamService.Direction.DOWNLINK) {
            return CompletableFuture.completedFuture(
                    PlatformRequestService.Outcome.failure(ProtocolErrors.NO_ACTIVE_STREAM));
        }
        return requests.send(deviceId, V2Names.AUDIO_STOP, Map.of())
                .thenApply(outcome -> {
                    streams.stop(deviceId);
                    return outcome;
                });
    }

    /** 异常终止下行播放。 */
    public CompletableFuture<PlatformRequestService.Outcome> abortDownlink(String deviceId, String reason) {
        if (streams.directionOf(deviceId) != AudioStreamService.Direction.DOWNLINK) {
            return CompletableFuture.completedFuture(
                    PlatformRequestService.Outcome.failure(ProtocolErrors.NO_ACTIVE_STREAM));
        }
        String normalized = reason == null || reason.isBlank() ? "aborted" : reason;
        return requests.send(deviceId, V2Names.AUDIO_ABORT, Map.of("reason", normalized))
                .thenApply(outcome -> {
                    streams.abort(deviceId, normalized);
                    return outcome;
                });
    }

    public boolean hasActiveStream(String deviceId) {
        return streams.hasActiveStream(deviceId);
    }
}
