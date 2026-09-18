package com.zwbd.agentnexus.sdui.v2.transport.sink;

import com.zwbd.agentnexus.sdui.v2.audio.AudioStreamService;
import com.zwbd.agentnexus.sdui.v2.protocol.V2Names;
import com.zwbd.agentnexus.sdui.v2.transport.V2Contexts;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 音频生命周期事件接收器。
 *
 * <p>04_PROTOCOL_MODEL.md §7：音频流"开始、数据传输、正常结束和异常终止由音频协议自身表达"。
 * 上行只有两个终止事件：{@code audio.stop} 正常结束，{@code audio.abort(reason)} 异常终止。
 * 终端上报 {@code buffer_full} 时，平台按异常终止处理并保留已收到的部分数据由业务层取舍。</p>
 *
 * <p>平台未收到结束事件时由 {@link AudioStreamService} 的超时扫描兜底（04§9）。</p>
 */
@Slf4j
@Component
public class AudioLifecycleEventSink implements V2Contexts.EventSink {

    private final AudioStreamService audioStreamService;
    private final AudioUplinkConsumer uplinkConsumer;

    public AudioLifecycleEventSink(AudioStreamService audioStreamService, AudioUplinkConsumer uplinkConsumer) {
        this.audioStreamService = audioStreamService;
        this.uplinkConsumer = uplinkConsumer;
    }

    @Override
    public List<String> names() {
        return List.of(V2Names.AUDIO_START, V2Names.AUDIO_STOP, V2Names.AUDIO_ABORT);
    }

    @Override
    public void onEvent(V2Contexts.EventContext context) {
        String deviceId = context.deviceId();
        String reason = context.body() == null ? null : context.body().path("reason").asText(null);

        if (V2Names.AUDIO_START.equals(context.name())) {
            String direction = context.body() == null ? null : context.body().path("direction").asText(null);
            // 终端只在本地响应序列触发录音时主动宣告上行流；下行由平台自己发起，因此缺省按上行处理。
            // 缺口 G22：文档未定义上行流的开始宣告方式，这里是平台侧的临时约定。
            var startDirection = "downlink".equalsIgnoreCase(direction)
                    ? AudioStreamService.Direction.DOWNLINK
                    : AudioStreamService.Direction.UPLINK;
            var startResult = audioStreamService.start(deviceId, startDirection);
            if (!startResult.ok()) {
                log.warn("终端宣告音频流开始被拒绝: device={}, direction={}, error={}",
                        deviceId, startDirection, startResult.error());
            }
            return;
        }

        boolean abort = V2Names.AUDIO_ABORT.equals(context.name());
        var result = abort
                ? audioStreamService.abort(deviceId, reason == null || reason.isBlank() ? "aborted" : reason)
                : audioStreamService.stop(deviceId);

        if (!result.ok()) {
            log.debug("音频结束事件无对应活动流: device={}, name={}", deviceId, context.name());
            return;
        }

        String normalizedReason = abort
                ? (reason == null || reason.isBlank() ? "aborted" : reason)
                : "stopped";
        uplinkConsumer.onStreamEnded(deviceId, normalizedReason);
        log.info("音频流已结束: device={}, name={}, reason={}", deviceId, context.name(), normalizedReason);
    }
}
