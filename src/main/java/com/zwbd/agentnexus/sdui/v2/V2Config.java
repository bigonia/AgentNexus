package com.zwbd.agentnexus.sdui.v2;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.zwbd.agentnexus.sdui.v2.protocol.EnvelopeCodec;
import com.zwbd.agentnexus.sdui.v2.transport.sink.AudioUplinkConsumer;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * v2 协议内核的 Bean 装配。
 *
 * <p>刻意保持最小：编解码器是无状态的纯函数式组件，不依赖 Spring 上下文，便于单元测试直接构造。</p>
 */
@Slf4j
@Configuration
public class V2Config {

    @Bean
    public EnvelopeCodec v2EnvelopeCodec(ObjectMapper objectMapper) {
        return new EnvelopeCodec(objectMapper);
    }

    /**
     * 上行 PCM 的默认下游实现：只记录长度，不做业务处理。
     *
     * <p>v2 音频协议与音频业务处理刻意解耦（见 {@link AudioUplinkConsumer}）。在终端切换到 v2 音频
     * 之前，这里保持一个可观测的空实现，避免协议层出现"必须依赖旧录音会话"的耦合。</p>
     */
    @Bean
    @ConditionalOnMissingBean(AudioUplinkConsumer.class)
    public AudioUplinkConsumer noopAudioUplinkConsumer() {
        return new AudioUplinkConsumer() {
            @Override
            public void onAudioChunk(String deviceId, byte[] pcm) {
                log.debug("v2 上行音频（未接入业务处理）: device={}, bytes={}", deviceId, pcm == null ? 0 : pcm.length);
            }

            @Override
            public void onStreamEnded(String deviceId, String reason) {
                log.debug("v2 上行音频结束（未接入业务处理）: device={}, reason={}", deviceId, reason);
            }
        };
    }
}
