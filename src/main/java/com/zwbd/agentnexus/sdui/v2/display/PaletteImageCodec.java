package com.zwbd.agentnexus.sdui.v2.display;

import com.zwbd.agentnexus.sdui.v2.protocol.ProtocolException;
import com.zwbd.agentnexus.sdui.v2.protocol.ProtocolErrors;

/**
 * 调色板索引矩阵解码与校验。
 *
 * <p>03_UI_MODEL.md §3 与 §4 定义全屏图片与 Matrix Canvas 都用"调色板 + 颜色索引矩阵"表示，
 * 调色板项为 RGB565。文档未规定索引矩阵的打包方式（缺口 G20），平台侧首期假设：</p>
 *
 * <pre>
 * 调色板   ：每项 2 字节，大端序 RGB565，顺序即索引 0..N-1
 * 索引矩阵 ：按 ceil(log2(paletteSize)) 位紧凑打包，行优先，字节内低位在前
 * </pre>
 *
 * <p>该假设风险较高，必须与终端实现对齐后才能用于真实设备。</p>
 */
public final class PaletteImageCodec {

    private PaletteImageCodec() {}

    /** 调色板项字节数。 */
    public static final int PALETTE_ENTRY_BYTES = 2;

    /** 每个像素需要的索引位数。 */
    public static int indexBits(int paletteSize) {
        if (paletteSize <= 0) {
            throw new ProtocolException(ProtocolErrors.INVALID_ENVELOPE, "palette size must be positive");
        }
        if (paletteSize <= 2) {
            return 1;
        }
        if (paletteSize <= 4) {
            return 2;
        }
        if (paletteSize <= 16) {
            return 4;
        }
        if (paletteSize <= 256) {
            return 8;
        }
        throw new ProtocolException(ProtocolErrors.UNSUPPORTED,
                "palette size " + paletteSize + " exceeds the first-phase 16-color scope");
    }

    /** 解码调色板为 RGB565 数值数组。 */
    public static int[] decodePalette(byte[] raw) {
        if (raw == null || raw.length == 0) {
            throw new ProtocolException(ProtocolErrors.INVALID_ENVELOPE, "empty palette");
        }
        if (raw.length % PALETTE_ENTRY_BYTES != 0) {
            throw new ProtocolException(ProtocolErrors.INVALID_ENVELOPE,
                    "palette length " + raw.length + " is not a multiple of " + PALETTE_ENTRY_BYTES);
        }
        int count = raw.length / PALETTE_ENTRY_BYTES;
        int[] palette = new int[count];
        for (int i = 0; i < count; i++) {
            int high = raw[i * 2] & 0xFF;
            int low = raw[i * 2 + 1] & 0xFF;
            palette[i] = ((high << 8) | low) & 0xFFFF;
        }
        return palette;
    }

    /** 索引矩阵的期望字节数。 */
    public static int expectedIndexBytes(int pixelCount, int paletteSize) {
        if (pixelCount < 0) {
            throw new ProtocolException(ProtocolErrors.INVALID_ENVELOPE, "negative pixel count");
        }
        int bits = indexBits(paletteSize);
        return (int) (((long) pixelCount * bits + 7L) / 8L);
    }

    /** 校验索引矩阵长度，并检查每个索引是否落在调色板范围内。 */
    public static void validateIndexMatrix(byte[] packed, int pixelCount, int paletteSize) {
        int expected = expectedIndexBytes(pixelCount, paletteSize);
        if (packed == null || packed.length != expected) {
            throw new ProtocolException(ProtocolErrors.INVALID_ENVELOPE,
                    "index matrix length mismatch: expected " + expected
                            + ", actual " + (packed == null ? 0 : packed.length));
        }
        unpack(packed, pixelCount, paletteSize);
    }

    /** 解包为逐像素索引。用于校验与平台侧自检。 */
    public static int[] unpack(byte[] packed, int pixelCount, int paletteSize) {
        int bits = indexBits(paletteSize);
        int[] indices = new int[pixelCount];
        long bitPosition = 0L;
        for (int pixel = 0; pixel < pixelCount; pixel++) {
            int value = 0;
            for (int offset = 0; offset < bits; offset++) {
                long position = bitPosition + offset;
                int byteIndex = (int) (position >>> 3);
                int bitInByte = (int) (position & 0x7);
                if (byteIndex >= packed.length) {
                    throw new ProtocolException(ProtocolErrors.INVALID_ENVELOPE,
                            "index matrix truncated at pixel " + pixel);
                }
                int bit = (packed[byteIndex] >>> bitInByte) & 0x1;
                value |= bit << offset;
            }
            bitPosition += bits;
            if (value >= paletteSize) {
                throw new ProtocolException(ProtocolErrors.INVALID_ENVELOPE,
                        "pixel " + pixel + " index " + value + " out of palette range " + paletteSize);
            }
            indices[pixel] = value;
        }
        return indices;
    }
}
