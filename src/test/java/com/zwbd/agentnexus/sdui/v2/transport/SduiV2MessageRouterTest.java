package com.zwbd.agentnexus.sdui.v2.transport;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.zwbd.agentnexus.sdui.v2.OperationResult;
import com.zwbd.agentnexus.sdui.v2.protocol.BinaryDataType;
import com.zwbd.agentnexus.sdui.v2.protocol.BinaryFrameCodecV2;
import com.zwbd.agentnexus.sdui.v2.protocol.EnvelopeCodec;
import com.zwbd.agentnexus.sdui.v2.protocol.ProtocolErrors;
import com.zwbd.agentnexus.sdui.v2.protocol.V2Names;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 接入层路由：三种信封的分发、结果回写与丢弃统计。
 */
class SduiV2MessageRouterTest {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final EnvelopeCodec codec = new EnvelopeCodec(objectMapper);

    private DeviceSender sender;
    private PlatformRequestService requestService;
    private CapturingRequestHandler requestHandler;
    private CapturingEventSink eventSink;
    private CapturingBinarySink binarySink;
    private SduiV2MessageRouter router;

    /** 一个最小请求处理器，便于验证结果回写。 */
    private static final class CapturingRequestHandler implements V2Contexts.RequestHandler {
        private V2Contexts.RequestContext lastContext;
        private OperationResult answer = OperationResult.success();

        @Override
        public String name() {
            return "device.ping";
        }

        @Override
        public com.zwbd.agentnexus.sdui.v2.OperationResult handle(V2Contexts.RequestContext context) {
            lastContext = context;
            return answer;
        }
    }

    private static final class CapturingEventSink implements V2Contexts.EventSink {
        private final List<V2Contexts.EventContext> events = new ArrayList<>();

        @Override
        public List<String> names() {
            return List.of(V2Names.PLATFORM_INTERACTION);
        }

        @Override
        public void onEvent(V2Contexts.EventContext context) {
            events.add(context);
        }
    }

    private static final class CapturingBinarySink implements V2Contexts.BinarySink {
        private final List<byte[]> payloads = new ArrayList<>();
        private boolean consume = true;

        @Override
        public BinaryDataType dataType() {
            return BinaryDataType.AUDIO;
        }

        @Override
        public boolean onData(V2Contexts.BinaryContext context) {
            payloads.add(context.payload());
            return consume;
        }
    }

    @BeforeEach
    void setUp() {
        sender = mock(DeviceSender.class);
        when(sender.sendControl(anyString(), any(JsonNode.class))).thenReturn(true);
        requestService = mock(PlatformRequestService.class);
        requestHandler = new CapturingRequestHandler();
        eventSink = new CapturingEventSink();
        binarySink = new CapturingBinarySink();
        router = new SduiV2MessageRouter(codec, sender, requestService,
                List.of(requestHandler), List.of(eventSink), List.of(binarySink));
    }

    @Test
    @DisplayName("事件按名称分发给接收器")
    void dispatchesEvent() {
        router.onText("dev-1", 1L, "{\"name\":\"platform.interaction\",\"body\":{\"token\":\"rt_x\"}}");

        assertEquals(1, eventSink.events.size());
        assertEquals("dev-1", eventSink.events.get(0).deviceId());
        assertEquals(1L, eventSink.events.get(0).generation());
        assertEquals(V2Names.PLATFORM_INTERACTION, eventSink.events.get(0).name());
        assertEquals("rt_x", eventSink.events.get(0).body().path("token").asText());
    }

    @Test
    @DisplayName("未知事件被忽略且不抛异常")
    void ignoresUnknownEvent() {
        router.onText("dev-1", 1L, "{\"name\":\"some.other.event\"}");
        assertTrue(eventSink.events.isEmpty());
    }

    @Test
    @DisplayName("请求被处理后回写成功结果")
    void handlesRequestAndReplies() {
        router.onText("dev-1", 7L, "{\"id\":\"req-1\",\"name\":\"device.ping\",\"body\":{\"k\":1}}");

        assertEquals("req-1", requestHandler.lastContext.requestId());
        assertEquals("dev-1", requestHandler.lastContext.deviceId());
        assertEquals(7L, requestHandler.lastContext.generation());
        assertEquals(1, requestHandler.lastContext.body().path("k").asInt());

        JsonNode reply = captureSentEnvelope();
        assertEquals("req-1", reply.path("id").asText());
        assertTrue(reply.path("ok").asBoolean());
        assertFalse(reply.has("error"));
    }

    @Test
    @DisplayName("处理器返回失败时回写错误名")
    void repliesFailureFromHandler() {
        requestHandler.answer = OperationResult.failure(ProtocolErrors.AUDIO_BUSY);

        router.onText("dev-1", 1L, "{\"id\":\"req-2\",\"name\":\"device.ping\"}");

        JsonNode reply = captureSentEnvelope();
        assertFalse(reply.path("ok").asBoolean());
        assertEquals(ProtocolErrors.AUDIO_BUSY, reply.path("error").asText());
    }

    @Test
    @DisplayName("未知请求名回写 unknown_name")
    void repliesUnknownName() {
        router.onText("dev-1", 1L, "{\"id\":\"req-3\",\"name\":\"business.nope\"}");

        JsonNode reply = captureSentEnvelope();
        assertFalse(reply.path("ok").asBoolean());
        assertEquals(ProtocolErrors.UNKNOWN_NAME, reply.path("error").asText());
    }

    @Test
    @DisplayName("结果信封交给请求服务完成，不在本地回写")
    void routesResultToRequestService() {
        router.onText("dev-1", 1L, "{\"id\":\"req-9\",\"ok\":false,\"error\":\"audio_busy\"}");

        verify(requestService).onResult("dev-1", "req-9", false, "audio_busy");
        verify(sender, never()).sendControl(anyString(), any(JsonNode.class));
    }

    @Test
    @DisplayName("非法信封被拒绝且计入统计")
    void rejectsInvalidEnvelope() {
        router.onText("dev-1", 1L, "{\"topic\":\"cmd/control\"}");
        router.onText("dev-1", 1L, "not-json");

        assertEquals(2L, router.rejectedEnvelopes());
        assertTrue(eventSink.events.isEmpty());
    }

    @Test
    @DisplayName("二进制帧按数据类型分发给接收器")
    void dispatchesBinary() {
        byte[] payload = {9, 8, 7};
        router.onBinary("dev-1", 1L, BinaryFrameCodecV2.encode(BinaryDataType.AUDIO, payload, 4096));

        assertEquals(1, binarySink.payloads.size());
        assertEquals(3, binarySink.payloads.get(0).length);
    }

    @Test
    @DisplayName("无接收器或接收器未消费的二进制帧计入丢弃")
    void countsDroppedBinary() {
        // 无接收器：CANVAS
        router.onBinary("dev-1", 1L, BinaryFrameCodecV2.encode(BinaryDataType.CANVAS, new byte[4], 4096));
        assertEquals(1L, router.droppedBinaryFrames());

        // 有接收器但未消费
        binarySink.consume = false;
        router.onBinary("dev-1", 1L, BinaryFrameCodecV2.encode(BinaryDataType.AUDIO, new byte[4], 4096));
        assertEquals(2L, router.droppedBinaryFrames());
    }

    @Test
    @DisplayName("非法二进制帧被拒绝且计入统计")
    void rejectsInvalidBinary() {
        router.onBinary("dev-1", 1L, new byte[3]);

        assertEquals(1L, router.rejectedEnvelopes());
        assertTrue(binarySink.payloads.isEmpty());
    }

    @Test
    @DisplayName("重复注册同名处理器或接收器时直接失败")
    void rejectsDuplicateRegistration() {
        IllegalStateException handlerConflict = org.junit.jupiter.api.Assertions.assertThrows(
                IllegalStateException.class,
                () -> new SduiV2MessageRouter(codec, sender, requestService,
                        List.of(new CapturingRequestHandler(), new CapturingRequestHandler()),
                        List.of(), List.of()));
        assertTrue(handlerConflict.getMessage().contains("device.ping"));

        IllegalStateException sinkConflict = org.junit.jupiter.api.Assertions.assertThrows(
                IllegalStateException.class,
                () -> new SduiV2MessageRouter(codec, sender, requestService,
                        List.of(), List.of(new CapturingEventSink(), new CapturingEventSink()), List.of()));
        assertTrue(sinkConflict.getMessage().contains(V2Names.PLATFORM_INTERACTION));
    }

    @Test
    @DisplayName("事件接收器抛异常不影响后续报文处理")
    void eventSinkFailureIsIsolated() {
        V2Contexts.EventSink faulty = new V2Contexts.EventSink() {
            @Override
            public List<String> names() {
                return List.of("boom");
            }

            @Override
            public void onEvent(V2Contexts.EventContext context) {
                throw new IllegalStateException("sink failed");
            }
        };
        SduiV2MessageRouter guarded = new SduiV2MessageRouter(codec, sender, requestService,
                List.of(requestHandler), List.of(faulty), List.of(binarySink));

        guarded.onText("dev-1", 1L, "{\"name\":\"boom\"}");
        guarded.onText("dev-1", 1L, "{\"id\":\"req-1\",\"name\":\"device.ping\"}");

        JsonNode reply = captureSentEnvelope();
        assertTrue(reply.path("ok").asBoolean());
    }

    private JsonNode captureSentEnvelope() {
        ArgumentCaptor<JsonNode> captor = ArgumentCaptor.forClass(JsonNode.class);
        verify(sender, org.mockito.Mockito.atLeastOnce()).sendControl(eq("dev-1"), captor.capture());
        return captor.getValue();
    }
}
