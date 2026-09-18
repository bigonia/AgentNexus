package com.zwbd.agentnexus.sdui.v2.protocol;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/**
 * v2 最小二进制帧编解码器。
 *
 * <p>替代旧 16 字节头（magic {@code 0x5344} + CRC32 + sequence）。新帧头只保留接收端
 * 区分数据类别所必需的信息，不含序号、CRC 和长度校验字段：</p>
 *
 * <pre>
 * offset 0  2B  magic      0x414E ('A' 'N')
 * offset 2  1B  version    2
 * offset 3  1B  dataType   BinaryDataType#code
 * offset 4  4B  length     payload 字节数（小端）
 * offset 8  NB  payload
 * </pre>
 *
 * <p>文档只要求"最小数据类型标识"（04_PROTOCOL_MODEL.md §8.1）与"单个 chunk 必须有大小上限"（§8）。
 * 字节布局与上限均为平台侧临时假设，见缺口 G2 / G4。</p>
 *
 * <p>旧帧头（magic {@code 0x5344}）在本编码器下会因 magic 不匹配而被拒绝。</p>
 */
public final class BinaryFrameCodecV2 {

    /** 'A' 'N' */
    public static final int MAGIC = 0x414E;
    public static final int VERSION = 2;
    public static final int HEADER_SIZE = 8;

    /** 旧协议的 magic，用于在错误信息中明确区分版本。 */
    private static final int LEGACY_MAGIC = 0x5344;

    private BinaryFrameCodecV2() {}

    public static byte[] encode(BinaryDataType dataType, byte[] payload, int maxPayloadBytes) {
        byte[] body = payload != null ? payload : new byte[0];
        if (body.length > maxPayloadBytes) {
            throw new ProtocolException(ProtocolErrors.FRAME_TOO_LARGE,
                    "payload " + body.length + " exceeds limit " + maxPayloadBytes);
        }
        byte[] frame = new byte[HEADER_SIZE + body.length];
        ByteBuffer header = ByteBuffer.wrap(frame).order(ByteOrder.LITTLE_ENDIAN);
        header.putShort((short) MAGIC);
        header.put((byte) VERSION);
        header.put((byte) dataType.code());
        header.putInt(body.length);
        System.arraycopy(body, 0, frame, HEADER_SIZE, body.length);
        return frame;
    }

    public static DecodedFrame decode(byte[] frame, int maxPayloadBytes) {
        if (frame == null || frame.length < HEADER_SIZE) {
            throw new ProtocolException(ProtocolErrors.INVALID_ENVELOPE, "binary frame too short");
        }
        ByteBuffer header = ByteBuffer.wrap(frame, 0, HEADER_SIZE).order(ByteOrder.LITTLE_ENDIAN);
        int magic = header.getShort() & 0xFFFF;
        if (magic == LEGACY_MAGIC) {
            throw new ProtocolException(ProtocolErrors.INVALID_ENVELOPE,
                    "legacy binary frame is not accepted on the v2 channel");
        }
        if (magic != MAGIC) {
            throw new ProtocolException(ProtocolErrors.INVALID_ENVELOPE,
                    "bad binary magic: " + Integer.toHexString(magic));
        }
        int version = header.get() & 0xFF;
        if (version != VERSION) {
            throw new ProtocolException(ProtocolErrors.INVALID_ENVELOPE, "unsupported binary version: " + version);
        }
        BinaryDataType dataType = BinaryDataType.fromCode(header.get() & 0xFF);
        int length = header.getInt();
        if (length < 0 || length > maxPayloadBytes) {
            throw new ProtocolException(ProtocolErrors.FRAME_TOO_LARGE, "declared payload length: " + length);
        }
        if (frame.length < HEADER_SIZE + length) {
            throw new ProtocolException(ProtocolErrors.INVALID_ENVELOPE, "binary payload truncated");
        }
        byte[] payload = new byte[length];
        System.arraycopy(frame, HEADER_SIZE, payload, 0, length);
        return new DecodedFrame(dataType, payload);
    }

    public record DecodedFrame(BinaryDataType dataType, byte[] payload) {}
}
