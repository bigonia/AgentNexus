package com.zwbd.agentnexus.sdui.v2.transport.sink;

import com.zwbd.agentnexus.sdui.v2.display.DisplaySessionService;
import com.zwbd.agentnexus.sdui.v2.protocol.BinaryDataType;
import com.zwbd.agentnexus.sdui.v2.transport.V2Contexts;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * 全屏图片数据接收器。
 *
 * <p>03_UI_MODEL.md §3：图片按全屏内容设计，不增加任意坐标的局部图片覆盖能力。
 * 04_PROTOCOL_MODEL.md §8：图片属于**有界对象**，断线后可丢弃未完成数据、需要时从头重新发送。</p>
 *
 * <p>因此这里只累加数据并在 {@code display.image.end} 时判定完整性，不实现跨连接续传。</p>
 */
@Slf4j
@Component
public class ImageChunkBinarySink implements V2Contexts.BinarySink {

    private final DisplaySessionService displaySessionService;

    public ImageChunkBinarySink(DisplaySessionService displaySessionService) {
        this.displaySessionService = displaySessionService;
    }

    @Override
    public BinaryDataType dataType() {
        return BinaryDataType.IMAGE;
    }

    @Override
    public boolean onData(V2Contexts.BinaryContext context) {
        long received = displaySessionService.onImageChunk(context.deviceId(), context.payload());
        if (received < 0) {
            log.debug("无活动图片生命周期，图片数据被丢弃: device={}", context.deviceId());
            return false;
        }
        return true;
    }
}
