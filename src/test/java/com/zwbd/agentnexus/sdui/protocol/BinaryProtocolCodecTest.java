package com.zwbd.agentnexus.sdui.protocol;

import org.junit.jupiter.api.Test;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

import static org.junit.jupiter.api.Assertions.*;

class BinaryProtocolCodecTest {

    @Test
    void encodeDecodeRoundTrip() {
        TlvBuilder b = new TlvBuilder();
        b.addString(31, "section");
        b.addString(32, "{\"page_id\":\"test\"}");

        byte[] frame = BinaryProtocolCodec.encode(15, 1, b.build());
        BinaryProtocolCodec.DecodedFrame decoded = BinaryProtocolCodec.decode(frame);

        assertEquals(15, decoded.msgType());
        assertEquals(1, decoded.seq());
        assertEquals(2, decoded.tlvs().size());
        assertEquals(31, decoded.tlvs().get(0).type());
        assertEquals("section", decoded.tlvs().get(0).asString());
        assertEquals(32, decoded.tlvs().get(1).type());
    }

    @Test
    void crc32DetectsCorruption() {
        TlvBuilder b = new TlvBuilder();
        b.addU32(30, 42);
        byte[] frame = BinaryProtocolCodec.encode(13, 0, b.build());
        frame[20] ^= 0xFF;
        assertThrows(IllegalArgumentException.class, () -> BinaryProtocolCodec.decode(frame));
    }

    @Test
    void tlvBuilderTypes() {
        TlvBuilder b = new TlvBuilder();
        b.addU8(101, 1);
        b.addU16(102, 466);
        b.addU32(30, 1234567890L);
        byte[] payload = b.build();
        byte[] frame = BinaryProtocolCodec.encode(1, 0, payload);
        BinaryProtocolCodec.DecodedFrame d = BinaryProtocolCodec.decode(frame);

        assertEquals(1, d.tlvs().get(0).asU8());
        assertEquals(466, d.tlvs().get(1).asU16());
        assertEquals(1234567890L, d.tlvs().get(2).asU32());
    }

    @Test
    void encodeHeaderFields() {
        byte[] frame = BinaryProtocolCodec.encode(10, 0, new byte[0]);
        assertEquals(16, frame.length);

        ByteBuffer hdr = ByteBuffer.wrap(frame).order(ByteOrder.LITTLE_ENDIAN);
        assertEquals(0x5344, hdr.getShort() & 0xFFFF);
        assertEquals(1, hdr.get() & 0xFF);
        assertEquals(10, hdr.get() & 0xFF);
        assertEquals(0, hdr.getInt());
        assertEquals(0, hdr.getInt());
    }
}
