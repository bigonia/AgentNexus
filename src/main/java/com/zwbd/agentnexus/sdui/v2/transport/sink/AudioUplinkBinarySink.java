package com.zwbd.agentnexus.sdui.v2.transport.sink;

import com.zwbd.agentnexus.sdui.v2.audio.AudioStreamService;
import com.zwbd.agentnexus.sdui.v2.protocol.BinaryDataType;
import com.zwbd.agentnexus.sdui.v2.transport.V2Contexts;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * 上行音频数据接收器。
 *
 * <p>04_PROTOCOL_MODEL.md §8.1：没有活动生命周期时收到对应 Binary 数据应拒绝或丢弃。
 * 因此本接收器只在存在活动上行音频流时消费数据，否则返回 false 交由路由器计入丢弃统计。</p>
 */
@Slf4j
@Component
public class AudioUplinkBinarySink implements V2Contexts.BinarySink {

    private final AudioStreamService audioStreamService;
    private final AudioUplinkConsumer uplinkConsumer;

    public AudioUplinkBinarySink(AudioStreamService audioStreamService, AudioUplinkConsumer uplinkConsumer) {
        this.audioStreamService = audioStreamService;
        this.uplinkConsumer = uplinkConsumer;
    }

    @Override
    public BinaryDataType dataType() {
        return BinaryDataType.AUDIO;
    }

    @Override
    public boolean onData(V2Contexts.BinaryContext context) {
        if (!audioStreamService.onUplinkData(context.deviceId(), context.payload())) {
            return false;
        }
        if (audioStreamService.directionOf(context.deviceId()) != AudioStreamService.Direction.UPLINK) {
            // 下行音频流期间收到上行数据，按无活动上行流处理
            return true;
        }
        try {
            uplinkConsumer.onAudioChunk(context.deviceId(), context.payload());
        } catch (RuntimeException e) {
            log.warn("上行音频下游处理失败: device={}", context.deviceId(), e);
        }
        return true;
    }
}
