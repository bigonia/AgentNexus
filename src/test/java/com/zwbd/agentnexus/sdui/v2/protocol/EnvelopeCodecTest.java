package com.zwbd.agentnexus.sdui.v2.protocol;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 信封编解码：判定规则与非法报文拒绝。
 */
class EnvelopeCodecTest {

    private final EnvelopeCodec codec = new EnvelopeCodec(new ObjectMapper());

    @Test
    @DisplayName("id + name 判定为请求，body 省略时为空")
    void decodesRequest() {
        Envelope envelope = codec.decode("{\"id\":\"req-1\",\"name\":\"business.reset\"}");

        Envelope.Request request = assertInstanceOf(Envelope.Request.class, envelope);
        assertEquals("req-1", request.id());
        assertEquals("business.reset", request.name());
        assertNull(request.body());
    }

    @Test
    @DisplayName("id + ok 判定为结果")
    void decodesResult() {
        Envelope okEnvelope = codec.decode("{\"id\":\"req-1\",\"ok\":true}");
        Envelope.Result okResult = assertInstanceOf(Envelope.Result.class, okEnvelope);
        assertTrue(okResult.ok());
        assertNull(okResult.error());

        Envelope failedEnvelope = codec.decode("{\"id\":\"req-2\",\"ok\":false,\"error\":\"audio_busy\"}");
        Envelope.Result failedResult = assertInstanceOf(Envelope.Result.class, failedEnvelope);
        assertFalse(failedResult.ok());
        assertEquals("audio_busy", failedResult.error());
    }

    @Test
    @DisplayName("只有 name 判定为主动事件")
    void decodesEvent() {
        Envelope envelope = codec.decode("{\"name\":\"platform.interaction\",\"body\":{\"token\":\"rt_x\"}}");

        Envelope.Event event = assertInstanceOf(Envelope.Event.class, envelope);
        assertEquals("platform.interaction", event.name());
        assertEquals("rt_x", event.body().path("token").asText());
    }

    @Test
    @DisplayName("结果缺少 id 时拒绝，避免把终端事件误判为结果")
    void rejectsResultWithoutId() {
        ProtocolException error = assertThrows(ProtocolException.class, () -> codec.decode("{\"ok\":true}"));
        assertEquals(ProtocolErrors.INVALID_ENVELOPE, error.code());
    }

    @Test
    @DisplayName("成功结果携带 error 时拒绝；失败结果缺少 error 时拒绝")
    void rejectsInconsistentResult() {
        assertEquals(ProtocolErrors.INVALID_ENVELOPE,
                assertThrows(ProtocolException.class, () -> codec.decode("{\"id\":\"r\",\"ok\":true,\"error\":\"x\"}")).code());
        assertEquals(ProtocolErrors.INVALID_ENVELOPE,
                assertThrows(ProtocolException.class, () -> codec.decode("{\"id\":\"r\",\"ok\":false}")).code());
    }

    @Test
    @DisplayName("既无 id/ok 也无 name 的报文被拒绝")
    void rejectsUnclassifiable() {
        assertEquals(ProtocolErrors.INVALID_ENVELOPE,
                assertThrows(ProtocolException.class, () -> codec.decode("{\"topic\":\"cmd/control\"}")).code());
        assertEquals(ProtocolErrors.INVALID_ENVELOPE,
                assertThrows(ProtocolException.class, () -> codec.decode("[1,2,3]")).code());
        assertEquals(ProtocolErrors.INVALID_ENVELOPE,
                assertThrows(ProtocolException.class, () -> codec.decode("not-json")).code());
    }

    @Test
    @DisplayName("编码的请求与结果可以再次被解码，且空 body 被省略")
    void roundTrips() {
        String requestJson = codec.toJson(codec.encodeRequest("req-9", "business.update", null));
        assertFalse(requestJson.contains("body"));
        Envelope.Request request = assertInstanceOf(Envelope.Request.class, codec.decode(requestJson));
        assertEquals("business.update", request.name());

        String resultJson = codec.toJson(codec.encodeResult("req-9", false, ProtocolErrors.TIMEOUT));
        Envelope.Result result = assertInstanceOf(Envelope.Result.class, codec.decode(resultJson));
        assertFalse(result.ok());
        assertEquals(ProtocolErrors.TIMEOUT, result.error());
    }
}
