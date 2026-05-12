package com.zwbd.agentnexus.sdui.protocol;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;

public class TlvBuilder {
    private final ByteArrayOutputStream buf = new ByteArrayOutputStream();

    public TlvBuilder addU8(int type, int value) {
        writeTlvHeader(type, 1);
        buf.write(value & 0xFF);
        return this;
    }

    public TlvBuilder addU16(int type, int value) {
        writeTlvHeader(type, 2);
        byte[] b = ByteBuffer.allocate(2).order(ByteOrder.BIG_ENDIAN).putShort((short) value).array();
        try { buf.write(b); } catch (IOException e) { throw new RuntimeException(e); }
        return this;
    }

    public TlvBuilder addU32(int type, long value) {
        writeTlvHeader(type, 4);
        byte[] b = ByteBuffer.allocate(4).order(ByteOrder.BIG_ENDIAN).putInt((int) value).array();
        try { buf.write(b); } catch (IOException e) { throw new RuntimeException(e); }
        return this;
    }

    public TlvBuilder addBytes(int type, byte[] value) {
        writeTlvHeader(type, value.length);
        try { buf.write(value); } catch (IOException e) { throw new RuntimeException(e); }
        return this;
    }

    public TlvBuilder addString(int type, String value) {
        return addBytes(type, value.getBytes(StandardCharsets.UTF_8));
    }

    public TlvBuilder addJson(int type, String json) {
        return addString(type, json);
    }

    private void writeTlvHeader(int type, int len) {
        byte[] h = ByteBuffer.allocate(4).order(ByteOrder.BIG_ENDIAN)
                .putShort((short) type).putShort((short) len).array();
        try { buf.write(h); } catch (IOException e) { throw new RuntimeException(e); }
    }

    public byte[] build() {
        return buf.toByteArray();
    }

    public int size() {
        return buf.size();
    }
}
