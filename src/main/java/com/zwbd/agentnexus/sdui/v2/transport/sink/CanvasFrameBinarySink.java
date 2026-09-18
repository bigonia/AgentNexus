package com.zwbd.agentnexus.sdui.v2.transport.sink;

import com.zwbd.agentnexus.sdui.v2.display.DisplaySessionService;
import com.zwbd.agentnexus.sdui.v2.protocol.BinaryDataType;
import com.zwbd.agentnexus.sdui.v2.transport.V2Contexts;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * Canvas 帧接收器。
 *
 * <p>03_UI_MODEL.md §4.3：Canvas 按完整帧更新，新帧替换旧帧，不维护历史帧；新的 Section、图片或
 * 业务 reset 会结束当前 Canvas。因此只有处于 Canvas 会话中时帧才被接受。</p>
 */
@Slf4j
@Component
public class CanvasFrameBinarySink implements V2Contexts.BinarySink {

    private final DisplaySessionService displaySessionService;

    public CanvasFrameBinarySink(DisplaySessionService displaySessionService) {
        this.displaySessionService = displaySessionService;
    }

    @Override
    public BinaryDataType dataType() {
        return BinaryDataType.CANVAS;
    }

    @Override
    public boolean onData(V2Contexts.BinaryContext context) {
        if (displaySessionService.modeOf(context.deviceId()) != DisplaySessionService.ViewMode.CANVAS) {
            return false;
        }
        return displaySessionService.onCanvasFrame(context.deviceId(), context.payload());
    }
}
