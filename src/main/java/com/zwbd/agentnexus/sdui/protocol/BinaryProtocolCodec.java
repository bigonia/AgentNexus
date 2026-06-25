package com.zwbd.agentnexus.sdui.protocol;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.CRC32;

public final class BinaryProtocolCodec {

    public static final int HEADER_SIZE = 16;
    public static final int MAGIC = 0x5344;
    public static final int VERSION = 1;
    public static final int MSG_TYPE_SECTION_SCENE = 15;
    public static final int MSG_TYPE_SECTION_PATCH = 16;
    public static final int MSG_TYPE_AUDIO_PCM = 17;
    /** Inbound raw PCM audio chunks from device microphone recording (msgType=18). */
    public static final int MSG_TYPE_AUDIO_RECORD_CHUNK = 18;

    // ── Well-known TLV type constants (inbound events, msgType=9) ──
    /** TLV 120: Numeric event kind (1=UI_CLICK, 2=OVERLAY_CONFIRM, 3=TOGGLE_CHANGE, 4=BUTTON; higher values reserved for device-defined inputs such as sensors). */
    public static final int TLV_EVENT_KIND = 120;
    /** TLV 121: Node / element ID (button ID, list item ID, toggle option ID). */
    public static final int TLV_NODE_ID = 121;
    /** TLV 122: Event name string (e.g. "action.click", "press_down"). */
    public static final int TLV_EVENT_NAME = 122;
    /** TLV 123: Page ID where the event occurred. */
    public static final int TLV_PAGE_ID = 123;
    /** TLV 124: Section ID that emitted the event. */
    public static final int TLV_SECTION_ID = 124;
    /** TLV 125: Timestamp in milliseconds (u32). */
    public static final int TLV_TS = 125;
    /** TLV 126: Event-specific value (toggle state, input string, etc.) (NEW). */
    public static final int TLV_EVENT_VALUE = 126;

    private BinaryProtocolCodec() {}

    public static byte[] encode(int msgType, int seq, byte[] payload) {
        int totalLen = HEADER_SIZE + payload.length;
        byte[] frame = new byte[totalLen];
        ByteBuffer hdr = ByteBuffer.wrap(frame).order(ByteOrder.LITTLE_ENDIAN);
        hdr.putShort((short) MAGIC);
        hdr.put((byte) VERSION);
        hdr.put((byte) msgType);
        hdr.putInt(seq);
        hdr.putInt(payload.length);
        hdr.putInt(0); // crc32 placeholder
        System.arraycopy(payload, 0, frame, HEADER_SIZE, payload.length);

        CRC32 crc = new CRC32();
        crc.update(frame, 0, 12);
        crc.update(0); // 4 zero bytes placeholder matching terminal protocol
        crc.update(0);
        crc.update(0);
        crc.update(0);
        crc.update(frame, HEADER_SIZE, payload.length);
        int crcVal = (int) crc.getValue();
        hdr.position(12);
        hdr.putInt(crcVal);
        return frame;
    }

    public static DecodedFrame decode(byte[] frame) {
        if (frame.length < HEADER_SIZE)
            throw new IllegalArgumentException("frame too short: " + frame.length);
        ByteBuffer hdr = ByteBuffer.wrap(frame, 0, HEADER_SIZE).order(ByteOrder.LITTLE_ENDIAN);
        int magic = hdr.getShort() & 0xFFFF;
        int version = hdr.get() & 0xFF;
        int msgType = hdr.get() & 0xFF;
        int seq = hdr.getInt();
        int payloadLen = hdr.getInt();
        int crc = hdr.getInt();

        if (magic != MAGIC)
            throw new IllegalArgumentException("bad magic: " + Integer.toHexString(magic));
        if (version != VERSION)
            throw new IllegalArgumentException("unsupported version: " + version);
        if (frame.length < HEADER_SIZE + payloadLen)
            throw new IllegalArgumentException("payload truncated");

        CRC32 check = new CRC32();
        check.update(frame, 0, 12);
        check.update(0);
        check.update(0);
        check.update(0);
        check.update(0);
        check.update(frame, HEADER_SIZE, payloadLen);
        if (((int) check.getValue()) != crc)
            throw new IllegalArgumentException("CRC32 mismatch");

        byte[] payload = new byte[payloadLen];
        System.arraycopy(frame, HEADER_SIZE, payload, 0, payloadLen);
        List<TlvEntry> tlvs = parseTlvs(payload);
        return new DecodedFrame(msgType, seq, payload, tlvs);
    }

    public static int crc32(byte[] data, int offset, int len) {
        CRC32 crc = new CRC32();
        crc.update(data, offset, len);
        return (int) crc.getValue();
    }

    private static List<TlvEntry> parseTlvs(byte[] payload) {
        List<TlvEntry> tlvs = new ArrayList<>();
        int off = 0;
        while (off + 4 <= payload.length) {
            ByteBuffer tlvHdr = ByteBuffer.wrap(payload, off, 4).order(ByteOrder.LITTLE_ENDIAN);
            int type = tlvHdr.getShort() & 0xFFFF;
            int len = tlvHdr.getShort() & 0xFFFF;
            off += 4;
            if (off + len > payload.length) break;
            byte[] value = new byte[len];
            System.arraycopy(payload, off, value, 0, len);
            tlvs.add(new TlvEntry(type, value));
            off += len;
        }
        return tlvs;
    }

    public record DecodedFrame(int msgType, int seq, byte[] payload, List<TlvEntry> tlvs) {}

    public record TlvEntry(int type, byte[] value) {
        public int asU8() { return value[0] & 0xFF; }
        public int asU16() {
            return ByteBuffer.wrap(value).order(ByteOrder.LITTLE_ENDIAN).getShort() & 0xFFFF;
        }
        public long asU32() {
            return ByteBuffer.wrap(value).order(ByteOrder.LITTLE_ENDIAN).getInt() & 0xFFFFFFFFL;
        }
        public String asString() { return new String(value, java.nio.charset.StandardCharsets.UTF_8); }
    }
}
