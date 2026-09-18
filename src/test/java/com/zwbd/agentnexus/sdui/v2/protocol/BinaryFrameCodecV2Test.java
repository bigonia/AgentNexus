package com.zwbd.agentnexus.sdui.v2.protocol;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * v2 二进制帧编解码：帧头、上限与旧帧拒绝。
 */
class BinaryFrameCodecV2Test {

    private static final int MAX = 8192;

    @Test
    @DisplayName("编码后可原样解码并保留数据类型")
    void roundTrips() {
        byte[] payload = {1, 2, 3, 4, 5};

        byte[] frame = BinaryFrameCodecV2.encode(BinaryDataType.CANVAS, payload, MAX);
        assertEquals(BinaryFrameCodecV2.HEADER_SIZE + payload.length, frame.length);

        BinaryFrameCodecV2.DecodedFrame decoded = BinaryFrameCodecV2.decode(frame, MAX);
        assertEquals(BinaryDataType.CANVAS, decoded.dataType());
        assertArrayEquals(payload, decoded.payload());
    }

    @Test
    @DisplayName("空 payload 合法")
    void allowsEmptyPayload() {
        byte[] frame = BinaryFrameCodecV2.encode(BinaryDataType.AUDIO, new byte[0], MAX);
        assertEquals(BinaryFrameCodecV2.HEADER_SIZE, frame.length);
        assertEquals(0, BinaryFrameCodecV2.decode(frame, MAX).payload().length);
    }

    @Test
    @DisplayName("超过单帧上限时拒绝编码")
    void rejectsOversizedPayloadOnEncode() {
        ProtocolException error = assertThrows(ProtocolException.class,
                () -> BinaryFrameCodecV2.encode(BinaryDataType.IMAGE, new byte[MAX + 1], MAX));
        assertEquals(ProtocolErrors.FRAME_TOO_LARGE, error.code());
    }

    @Test
    @DisplayName("声明的长度超过上限时拒绝解码")
    void rejectsOversizedPayloadOnDecode() {
        byte[] frame = BinaryFrameCodecV2.encode(BinaryDataType.IMAGE, new byte[64], MAX);
        assertEquals(ProtocolErrors.FRAME_TOO_LARGE,
                assertThrows(ProtocolException.class, () -> BinaryFrameCodecV2.decode(frame, 16)).code());
    }

    @Test
    @DisplayName("旧 16 字节帧头在新通道明确被拒绝")
    void rejectsLegacyFrame() {
        byte[] legacy = new byte[32];
        legacy[0] = 0x44; // 0x5344 little-endian
        legacy[1] = 0x53;
        legacy[2] = 1;

        ProtocolException error = assertThrows(ProtocolException.class,
                () -> BinaryFrameCodecV2.decode(legacy, MAX));
        assertEquals(ProtocolErrors.INVALID_ENVELOPE, error.code());
        assertEquals(true, error.getMessage().contains("legacy"));
    }

    @Test
    @DisplayName("未知 dataType 被拒绝")
    void rejectsUnknownDataType() {
        byte[] frame = BinaryFrameCodecV2.encode(BinaryDataType.AUDIO, new byte[2], MAX);
        frame[3] = 99;
        assertEquals(ProtocolErrors.INVALID_ENVELOPE,
                assertThrows(ProtocolException.class, () -> BinaryFrameCodecV2.decode(frame, MAX)).code());
    }

    @Test
    @DisplayName("帧过短或 payload 截断被拒绝")
    void rejectsTruncatedFrames() {
        assertEquals(ProtocolErrors.INVALID_ENVELOPE,
                assertThrows(ProtocolException.class, () -> BinaryFrameCodecV2.decode(new byte[4], MAX)).code());

        byte[] frame = BinaryFrameCodecV2.encode(BinaryDataType.AUDIO, new byte[10], MAX);
        byte[] truncated = new byte[frame.length - 3];
        System.arraycopy(frame, 0, truncated, 0, truncated.length);
        assertEquals(ProtocolErrors.INVALID_ENVELOPE,
                assertThrows(ProtocolException.class, () -> BinaryFrameCodecV2.decode(truncated, MAX)).code());
    }
}
