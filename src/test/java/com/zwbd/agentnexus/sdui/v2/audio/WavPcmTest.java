package com.zwbd.agentnexus.sdui.v2.audio;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * WAV 容器拆解。
 *
 * <p>下行音频传的是裸 PCM，而平台侧的产物多是 WAV。这里固定住"能正确取出载荷"与"识别不了时
 * 不假装成功"两条行为。</p>
 */
class WavPcmTest {

    private static final int SAMPLE_RATE = 16000;

    @Test
    @DisplayName("取出 data 载荷并读出格式参数")
    void extractsDataChunk() {
        byte[] pcm = {1, 2, 3, 4, 5, 6, 7, 8};
        WavPcm.Payload payload = WavPcm.extract(wav(pcm, SAMPLE_RATE, 1, 16));

        assertTrue(payload.wav());
        assertArrayEquals(pcm, payload.pcm());
        assertEquals(SAMPLE_RATE, payload.sampleRate());
        assertEquals(1, payload.channels());
        assertEquals(16, payload.bitsPerSample());
        assertEquals(0, payload.durationMs());
    }

    @Test
    @DisplayName("跳过 data 之前的其他 chunk")
    void skipsUnknownChunks() {
        byte[] pcm = {9, 8, 7, 6};
        byte[] wav = concat(
                header(),
                fmtChunk(SAMPLE_RATE, 1, 16),
                chunk("LIST", new byte[]{'I', 'N', 'F', 'O'}),
                chunk("data", pcm));

        WavPcm.Payload payload = WavPcm.extract(wav);
        assertArrayEquals(pcm, payload.pcm());
        assertEquals(SAMPLE_RATE, payload.sampleRate());
    }

    @Test
    @DisplayName("非 RIFF/WAVE 按裸 PCM 原样返回并标记")
    void passesThroughRawPcm() {
        byte[] raw = {11, 22, 33};
        WavPcm.Payload payload = WavPcm.extract(raw);

        assertFalse(payload.wav());
        assertArrayEquals(raw, payload.pcm());
        assertEquals(0, payload.sampleRate());
    }

    @Test
    @DisplayName("长度字段不可信时停止解析，不越界读取")
    void toleratesTruncatedInput() {
        byte[] truncated = concat(header(), fmtChunk(SAMPLE_RATE, 1, 16), chunk("data", new byte[]{1, 2, 3, 4}));
        byte[] cut = new byte[truncated.length - 5];
        System.arraycopy(truncated, 0, cut, 0, cut.length);

        WavPcm.Payload payload = WavPcm.extract(cut);
        // 载荷不完整时宁可返回空，也不返回半段音频
        assertEquals(0, payload.pcm().length);
    }

    @Test
    @DisplayName("空输入不抛异常")
    void handlesEmptyInput() {
        assertTrue(WavPcm.extract(new byte[0]).isEmpty());
        assertTrue(WavPcm.extract(null).isEmpty());
    }

    // ── 构造夹具 ────────────────────────────────────────────────────────────

    private static byte[] wav(byte[] pcm, int sampleRate, int channels, int bitsPerSample) {
        return concat(header(), fmtChunk(sampleRate, channels, bitsPerSample), chunk("data", pcm));
    }

    private static byte[] header() {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.writeBytes("RIFF".getBytes(StandardCharsets.US_ASCII));
        out.writeBytes(intLe(0));
        out.writeBytes("WAVE".getBytes(StandardCharsets.US_ASCII));
        return out.toByteArray();
    }

    private static byte[] fmtChunk(int sampleRate, int channels, int bitsPerSample) {
        ByteBuffer body = ByteBuffer.allocate(16).order(ByteOrder.LITTLE_ENDIAN);
        body.putShort((short) 1);
        body.putShort((short) channels);
        body.putInt(sampleRate);
        body.putInt(sampleRate * channels * bitsPerSample / 8);
        body.putShort((short) (channels * bitsPerSample / 8));
        body.putShort((short) bitsPerSample);
        return chunk("fmt ", body.array());
    }

    private static byte[] chunk(String id, byte[] body) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.writeBytes(id.getBytes(StandardCharsets.US_ASCII));
        out.writeBytes(intLe(body.length));
        out.writeBytes(body);
        if (body.length % 2 != 0) {
            out.write(0);
        }
        return out.toByteArray();
    }

    private static byte[] intLe(int value) {
        return ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(value).array();
    }

    private static byte[] concat(byte[]... parts) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        for (byte[] part : parts) {
            out.writeBytes(part);
        }
        return out.toByteArray();
    }
}
