package com.zwbd.agentnexus.sdui.v2.display;

import com.zwbd.agentnexus.sdui.v2.protocol.ProtocolException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * 调色板索引矩阵的位打包与校验。
 *
 * <p>位序（字节内低位在前）属于平台侧临时假设（缺口 G20），本测试固定该假设，
 * 便于终端实现偏离时能立刻发现。</p>
 */
class PaletteImageCodecTest {

    @Test
    @DisplayName("索引位数按调色板大小推导")
    void indexBits() {
        assertEquals(1, PaletteImageCodec.indexBits(2));
        assertEquals(2, PaletteImageCodec.indexBits(3));
        assertEquals(2, PaletteImageCodec.indexBits(4));
        assertEquals(4, PaletteImageCodec.indexBits(5));
        assertEquals(4, PaletteImageCodec.indexBits(16));
        assertEquals(8, PaletteImageCodec.indexBits(17));
        assertEquals(8, PaletteImageCodec.indexBits(256));
    }

    @Test
    @DisplayName("首期范围之外或非法的调色板大小被拒绝")
    void rejectsUnsupportedPaletteSize() {
        assertThrows(ProtocolException.class, () -> PaletteImageCodec.indexBits(0));
        assertThrows(ProtocolException.class, () -> PaletteImageCodec.indexBits(257));
    }

    @Test
    @DisplayName("期望字节数按位向上取整")
    void expectedIndexBytes() {
        // 32x32 16 色 → 4 bit/px → 512 字节（03_UI_MODEL.md §4.2 的例子）
        assertEquals(512, PaletteImageCodec.expectedIndexBytes(32 * 32, 16));
        // 128x128 16 色 → 8192 字节
        assertEquals(8192, PaletteImageCodec.expectedIndexBytes(128 * 128, 16));
        // 非整除像素数需向上取整
        assertEquals(1, PaletteImageCodec.expectedIndexBytes(5, 2));
        assertEquals(1, PaletteImageCodec.expectedIndexBytes(1, 16));
    }

    @Test
    @DisplayName("调色板按大端序 RGB565 解码")
    void decodesPalette() {
        byte[] raw = {(byte) 0xF8, 0x00, (byte) 0x07, (byte) 0xE0, 0x00, 0x1F};

        int[] palette = PaletteImageCodec.decodePalette(raw);

        assertArrayEquals(new int[]{0xF800, 0x07E0, 0x001F}, palette);
    }

    @Test
    @DisplayName("调色板长度必须为 2 的倍数")
    void rejectsOddPalette() {
        assertThrows(ProtocolException.class, () -> PaletteImageCodec.decodePalette(new byte[3]));
        assertThrows(ProtocolException.class, () -> PaletteImageCodec.decodePalette(new byte[0]));
    }

    @Test
    @DisplayName("4 bit/px 的索引矩阵按低位在前打包与解包")
    void roundTripsFourBitIndices() {
        // 像素序列 0,1,2,...,15 打包后每个字节承载两个像素（低半字节是偶数位像素）
        byte[] packed = new byte[8];
        for (int pixel = 0; pixel < 16; pixel++) {
            int value = pixel & 0xF;
            if (pixel % 2 == 0) {
                packed[pixel / 2] |= (byte) value;
            } else {
                packed[pixel / 2] |= (byte) (value << 4);
            }
        }

        int[] indices = PaletteImageCodec.unpack(packed, 16, 16);

        for (int pixel = 0; pixel < 16; pixel++) {
            assertEquals(pixel, indices[pixel], "pixel " + pixel);
        }
    }

    @Test
    @DisplayName("1 bit/px 与 2 bit/px 的边界像素解包正确")
    void unpacksLowBitDepths() {
        // 1 bit/px：0b1010_0101 → 像素 0..7 = 1,0,1,0,0,1,0,1
        byte[] oneBit = {(byte) 0xA5};
        assertArrayEquals(new int[]{1, 0, 1, 0, 0, 1, 0, 1}, PaletteImageCodec.unpack(oneBit, 8, 2));

        // 2 bit/px：0b11_10_01_00 → 像素 0..3 = 0,1,2,3
        byte[] twoBit = {(byte) 0xE4};
        assertArrayEquals(new int[]{0, 1, 2, 3}, PaletteImageCodec.unpack(twoBit, 4, 4));
    }

    @Test
    @DisplayName("长度不匹配的索引矩阵被拒绝")
    void rejectsWrongLength() {
        assertThrows(ProtocolException.class,
                () -> PaletteImageCodec.validateIndexMatrix(new byte[10], 32 * 32, 16));
        assertThrows(ProtocolException.class,
                () -> PaletteImageCodec.validateIndexMatrix(null, 4, 16));
    }

    @Test
    @DisplayName("索引超出调色板范围被拒绝")
    void rejectsIndexOutOfRange() {
        // 2 色（1 bit）但字节里出现索引 1 是合法的；用 2 色承载不了索引 2
        // 这里改用 3 色调色板（2 bit/px）并写入索引 3，超出范围
        byte[] packed = {(byte) 0x03};
        assertThrows(ProtocolException.class, () -> PaletteImageCodec.unpack(packed, 1, 3));
    }

    @Test
    @DisplayName("全零帧在任意合法调色板下都通过校验")
    void acceptsZeroFrame() {
        PaletteImageCodec.validateIndexMatrix(new byte[PaletteImageCodec.expectedIndexBytes(1024, 16)], 1024, 16);
    }
}
