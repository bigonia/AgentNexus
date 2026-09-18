package com.zwbd.agentnexus.sdui.v2.display;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 主视图互斥与图片/Canvas 生命周期。
 */
class DisplaySessionServiceTest {

    private final DisplaySessionService sessions = new DisplaySessionService();

    private static byte[] canvasFrame(int width, int height, int paletteSize) {
        return new byte[PaletteImageCodec.expectedIndexBytes(width * height, paletteSize)];
    }

    @Test
    @DisplayName("初始无主视图")
    void startsEmpty() {
        assertEquals(DisplaySessionService.ViewMode.NONE, sessions.modeOf("dev-1"));
        assertFalse(sessions.hasActiveView("dev-1"));
    }

    @Test
    @DisplayName("三种主视图互斥，新视图完整替换旧视图")
    void viewsAreMutuallyExclusive() {
        assertEquals(DisplaySessionService.ViewMode.NONE, sessions.beginSection("dev-1"));
        assertEquals(DisplaySessionService.ViewMode.SECTION, sessions.beginImage("dev-1", 128, 128, 16, 8192L));
        assertEquals(DisplaySessionService.ViewMode.IMAGE, sessions.openCanvas("dev-1", 32, 32, 16, 10));
        assertEquals(DisplaySessionService.ViewMode.CANVAS, sessions.beginSection("dev-1"));
        assertEquals(DisplaySessionService.ViewMode.SECTION, sessions.modeOf("dev-1"));
    }

    @Test
    @DisplayName("切换主视图会清除上一会话的参数")
    void switchingClearsPreviousSpec() {
        sessions.openCanvas("dev-1", 32, 32, 16, 10);
        assertTrue(sessions.canvasSpec("dev-1").isPresent());

        sessions.beginSection("dev-1");
        assertTrue(sessions.canvasSpec("dev-1").isEmpty(), "切到 Section 后不应残留 Canvas 会话");
    }

    @Test
    @DisplayName("图片数据在活动会话内累加，恰好到达期望长度算完整")
    void tracksImageProgress() {
        sessions.beginImage("dev-1", 128, 128, 16, 10L);

        assertEquals(4L, sessions.onImageChunk("dev-1", new byte[4]));
        assertEquals(10L, sessions.onImageChunk("dev-1", new byte[6]));
        assertTrue(sessions.completeImage("dev-1"));
        assertEquals(DisplaySessionService.ViewMode.NONE, sessions.modeOf("dev-1"));
    }

    @Test
    @DisplayName("图片数据不足时结束返回不完整，且状态被清空")
    void incompleteImage() {
        sessions.beginImage("dev-1", 128, 128, 16, 10L);
        sessions.onImageChunk("dev-1", new byte[3]);

        assertFalse(sessions.completeImage("dev-1"));
        assertEquals(DisplaySessionService.ViewMode.NONE, sessions.modeOf("dev-1"));
    }

    @Test
    @DisplayName("非图片会话期间收到图片数据返回 -1 且不影响计数")
    void imageChunkWithoutSession() {
        assertEquals(-1L, sessions.onImageChunk("dev-1", new byte[8]));

        sessions.beginSection("dev-1");
        assertEquals(-1L, sessions.onImageChunk("dev-1", new byte[8]));
    }

    @Test
    @DisplayName("Canvas 会话内接受合法帧并计数")
    void acceptsValidCanvasFrames() {
        sessions.openCanvas("dev-1", 32, 32, 16, 10);

        assertTrue(sessions.onCanvasFrame("dev-1", canvasFrame(32, 32, 16)));
        assertTrue(sessions.onCanvasFrame("dev-1", canvasFrame(32, 32, 16)));

        var state = sessions.snapshot("dev-1");
        assertEquals(2L, state.canvasFramesReceived());
        assertEquals(0L, state.canvasFramesRejected());
    }

    @Test
    @DisplayName("长度错误的 Canvas 帧被拒绝但会话保持")
    void rejectsMalformedCanvasFrame() {
        sessions.openCanvas("dev-1", 32, 32, 16, 10);

        assertFalse(sessions.onCanvasFrame("dev-1", new byte[10]));

        var state = sessions.snapshot("dev-1");
        assertEquals(0L, state.canvasFramesReceived());
        assertEquals(1L, state.canvasFramesRejected());
        assertEquals(DisplaySessionService.ViewMode.CANVAS, sessions.modeOf("dev-1"));
    }

    @Test
    @DisplayName("重新打开会话后旧调色板对应的帧长度不匹配会被拒绝")
    void rejectsFrameFromPreviousPalette() {
        sessions.openCanvas("dev-1", 32, 32, 4, 10);

        int pixelCount = 32 * 32;
        byte[] fourColorFrame = new byte[PaletteImageCodec.expectedIndexBytes(pixelCount, 4)];
        assertTrue(sessions.onCanvasFrame("dev-1", fourColorFrame));

        // 以 2 色重开会话后，4 色的帧长度超出期望，应被拒绝
        sessions.openCanvas("dev-1", 32, 32, 2, 10);
        assertFalse(sessions.onCanvasFrame("dev-1", fourColorFrame));
    }

    @Test
    @DisplayName("无 Canvas 会话时帧被拒绝")
    void rejectsFrameWithoutSession() {
        assertFalse(sessions.onCanvasFrame("dev-1", canvasFrame(32, 32, 16)));

        sessions.beginSection("dev-1");
        assertFalse(sessions.onCanvasFrame("dev-1", canvasFrame(32, 32, 16)));
    }

    @Test
    @DisplayName("business.reset 清空主视图")
    void clearRemovesState() {
        sessions.openCanvas("dev-1", 32, 32, 16, 10);
        sessions.clear("dev-1");

        assertEquals(DisplaySessionService.ViewMode.NONE, sessions.modeOf("dev-1"));
        assertTrue(sessions.canvasSpec("dev-1").isEmpty());
    }
}
