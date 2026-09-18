package com.zwbd.agentnexus.sdui.v2.audio;

import com.zwbd.agentnexus.sdui.v2.V2ProtocolProperties;
import com.zwbd.agentnexus.sdui.v2.protocol.ProtocolErrors;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 音频流状态：方向互斥、异常终止与超时兜底。
 */
class AudioStreamServiceTest {

    /** 可注入时钟的测试子类。 */
    private static final class Testable extends AudioStreamService {
        private long clock = 1_000L;

        Testable(V2ProtocolProperties properties) {
            super(properties);
        }

        void advance(long millis) {
            clock += millis;
        }

        @Override
        protected long now() {
            return clock;
        }
    }

    private V2ProtocolProperties properties;
    private Testable service;

    @BeforeEach
    void setUp() {
        properties = new V2ProtocolProperties();
        properties.setAudioReceiveTimeoutMs(1_000L);
        service = new Testable(properties);
    }

    @Test
    @DisplayName("初始无活动音频")
    void startsIdle() {
        assertEquals(AudioStreamService.Direction.NONE, service.directionOf("dev-1"));
        assertFalse(service.hasActiveStream("dev-1"));
    }

    @Test
    @DisplayName("开始上行录音后方向为上行")
    void startsUplink() {
        assertTrue(service.start("dev-1", AudioStreamService.Direction.UPLINK).ok());

        assertEquals(AudioStreamService.Direction.UPLINK, service.directionOf("dev-1"));
        assertTrue(service.hasActiveStream("dev-1"));
    }

    @Test
    @DisplayName("录音与播放互斥：反向请求返回 audio_busy 且不抢占")
    void directionsAreMutuallyExclusive() {
        service.start("dev-1", AudioStreamService.Direction.UPLINK);

        var rejected = service.start("dev-1", AudioStreamService.Direction.DOWNLINK);

        assertFalse(rejected.ok());
        assertEquals(ProtocolErrors.AUDIO_BUSY, rejected.error());
        assertEquals(AudioStreamService.Direction.UPLINK, service.directionOf("dev-1"), "原方向保持");
    }

    @Test
    @DisplayName("同方向重复开始按幂等处理")
    void sameDirectionStartIsIdempotent() {
        service.start("dev-1", AudioStreamService.Direction.UPLINK);
        assertTrue(service.start("dev-1", AudioStreamService.Direction.UPLINK).ok());
    }

    @Test
    @DisplayName("方向为 NONE 的开始请求被拒绝")
    void rejectsNoneDirection() {
        var result = service.start("dev-1", AudioStreamService.Direction.NONE);
        assertFalse(result.ok());
        assertEquals(ProtocolErrors.INVALID_VALUE, result.error());
    }

    @Test
    @DisplayName("结束后的流方向为 NONE，可再次开始反方向")
    void stopReleasesDirection() {
        service.start("dev-1", AudioStreamService.Direction.UPLINK);
        assertTrue(service.stop("dev-1").ok());

        assertEquals(AudioStreamService.Direction.NONE, service.directionOf("dev-1"));
        assertTrue(service.start("dev-1", AudioStreamService.Direction.DOWNLINK).ok());
    }

    @Test
    @DisplayName("无活动流时结束返回 no_active_stream")
    void stopWithoutStream() {
        var result = service.stop("dev-1");
        assertFalse(result.ok());
        assertEquals(ProtocolErrors.NO_ACTIVE_STREAM, result.error());
    }

    @Test
    @DisplayName("录音缓冲区满：异常终止并保留原因与已收字节数")
    void bufferFullAbnormallyTerminates() {
        service.start("dev-1", AudioStreamService.Direction.UPLINK);
        service.onUplinkData("dev-1", new byte[128]);
        service.onUplinkData("dev-1", new byte[256]);

        var result = service.onBufferFull("dev-1");

        assertTrue(result.ok());
        var state = service.stateOf("dev-1").orElseThrow();
        assertEquals(AudioStreamService.State.ABNORMAL, state.state());
        assertEquals(ProtocolErrors.REASON_BUFFER_FULL, state.endReason());
        assertEquals(384L, state.bytesReceived(), "已收到的部分数据被保留，由业务层决定取舍");
        assertEquals(AudioStreamService.Direction.NONE, service.directionOf("dev-1"));
    }

    @Test
    @DisplayName("无活动上行流时上行数据被丢弃")
    void dropsUplinkDataWithoutStream() {
        assertFalse(service.onUplinkData("dev-1", new byte[16]));

        service.start("dev-1", AudioStreamService.Direction.DOWNLINK);
        assertFalse(service.onUplinkData("dev-1", new byte[16]), "下行流期间不接受上行数据");
    }

    @Test
    @DisplayName("持续无数据超过接收超时：上行标记 buffer_full，下行标记 playback_timeout")
    void sweepsTimeoutsByDirection() {
        service.start("dev-1", AudioStreamService.Direction.UPLINK);
        service.start("dev-2", AudioStreamService.Direction.DOWNLINK);

        service.advance(999L);
        service.sweepTimeouts();
        assertTrue(service.hasActiveStream("dev-1"), "未到超时不应终止");

        service.advance(2L);
        service.sweepTimeouts();

        assertEquals(AudioStreamService.State.ABNORMAL, service.stateOf("dev-1").orElseThrow().state());
        assertEquals(ProtocolErrors.REASON_BUFFER_FULL, service.stateOf("dev-1").orElseThrow().endReason());
        assertEquals(ProtocolErrors.REASON_PLAYBACK_TIMEOUT, service.stateOf("dev-2").orElseThrow().endReason());
    }

    @Test
    @DisplayName("收到数据会刷新等待超时")
    void incomingDataRefreshesTimeout() {
        service.start("dev-1", AudioStreamService.Direction.UPLINK);

        for (int i = 0; i < 5; i++) {
            service.advance(600L);
            service.onUplinkData("dev-1", new byte[16]);
            service.sweepTimeouts();
        }

        assertTrue(service.hasActiveStream("dev-1"), "持续有数据时不应超时");
    }

    @Test
    @DisplayName("业务清理移除音频状态")
    void clearAllRemovesState() {
        service.start("dev-1", AudioStreamService.Direction.UPLINK);

        assertEquals(1, service.clearAll("dev-1").size());
        assertTrue(service.stateOf("dev-1").isEmpty());
        assertFalse(service.hasActiveStream("dev-1"));
    }
}
