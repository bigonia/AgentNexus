package com.zwbd.agentnexus.sdui.v2.display;

import com.zwbd.agentnexus.sdui.v2.protocol.BinaryDataType;
import com.zwbd.agentnexus.sdui.v2.protocol.ProtocolErrors;
import com.zwbd.agentnexus.sdui.v2.protocol.V2Names;
import com.zwbd.agentnexus.sdui.v2.transport.DeviceSender;
import com.zwbd.agentnexus.sdui.v2.transport.PlatformRequestService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

/**
 * 显示面下发：单 Section、全屏图片、Matrix Canvas。
 *
 * <p>03_UI_MODEL.md §1：三种主视图互斥，新的主视图完整替换旧主视图。平台侧在发出新主视图前
 * 先结束本地记录的上一个会话，避免平台状态与终端状态脱节。</p>
 *
 * <p>§2：Section 更新采用**完整替换**，不再有 Section 级增删改 Patch；也不再支持多 Section 拼接、
 * 嵌套、栅格与跨屏适配模式。因此这里只有一个 {@code display.section} 入口，没有 patch 入口。</p>
 *
 * <p>调色板随会话开始一并下发。由于首期色数上限为 16，RGB565 调色板最多只占 32 字节，
 * 直接放在 JSON body 中而不是单独走二进制通道（03§4.2「完整调色板最多只占少量额外字节」）。</p>
 */
@Slf4j
@Service
public class DisplayCommandService {

    private final DisplaySessionService sessions;
    private final PlatformRequestService requests;
    private final DeviceSender sender;

    public DisplayCommandService(DisplaySessionService sessions,
                                 PlatformRequestService requests,
                                 DeviceSender sender) {
        this.sessions = sessions;
        this.requests = requests;
        this.sender = sender;
    }

    /** 下发单个全屏 Section，完整替换当前主视图。 */
    public CompletableFuture<PlatformRequestService.Outcome> sendSection(String deviceId, Object section) {
        DisplaySessionService.ViewMode previous = sessions.beginSection(deviceId);
        log.info("下发 Section 主视图: device={}, replaced={}", deviceId, previous);
        return requests.send(deviceId, V2Names.DISPLAY_SECTION, section);
    }

    /** 开始接收全屏图片。调色板与总长度随 begin 一并下发。 */
    public CompletableFuture<PlatformRequestService.Outcome> beginImage(String deviceId, int width, int height,
                                                                        int paletteSize, long expectedBytes,
                                                                        int[] paletteRgb565) {
        DisplaySessionService.ViewMode previous =
                sessions.beginImage(deviceId, width, height, paletteSize, expectedBytes);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("width", width);
        body.put("height", height);
        body.put("paletteSize", paletteSize);
        body.put("paletteRgb565", paletteRgb565 == null ? new int[0] : paletteRgb565);
        body.put("totalBytes", expectedBytes);
        log.info("开始图片主视图: device={}, {}x{}, palette={}, expectedBytes={}, replaced={}",
                deviceId, width, height, paletteSize, expectedBytes, previous);
        return requests.send(deviceId, V2Names.DISPLAY_IMAGE_BEGIN, body);
    }

    /**
     * 发送一段图片数据。
     *
     * <p>04§8：图片属于有界对象，依赖当前连接的有序传输；无活动生命周期时明确失败。</p>
     */
    public boolean sendImageChunk(String deviceId, byte[] chunk) {
        if (sessions.modeOf(deviceId) != DisplaySessionService.ViewMode.IMAGE) {
            log.debug("无活动图片生命周期，图片数据丢弃: device={}", deviceId);
            return false;
        }
        boolean sent = sender.sendBinary(deviceId, BinaryDataType.IMAGE, chunk);
        if (sent) {
            sessions.onImageChunk(deviceId, chunk);
        }
        return sent;
    }

    /** 结束图片传输。数据不足时按不完整对象处理，可由平台从头上重发（04§6）。 */
    public CompletableFuture<PlatformRequestService.Outcome> endImage(String deviceId) {
        boolean complete = sessions.completeImage(deviceId);
        if (!complete) {
            return CompletableFuture.completedFuture(
                    PlatformRequestService.Outcome.failure(ProtocolErrors.INVALID_VALUE));
        }
        return requests.send(deviceId, V2Names.DISPLAY_IMAGE_END, Map.of());
    }

    /** 打开 Canvas 会话。03§4：会话开始时完整提供分辨率、调色板与索引位数。 */
    public CompletableFuture<PlatformRequestService.Outcome> openCanvas(String deviceId, int width, int height,
                                                                        int paletteSize, Integer maxFps,
                                                                        int[] paletteRgb565) {
        DisplaySessionService.ViewMode previous = sessions.openCanvas(deviceId, width, height, paletteSize, maxFps);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("width", width);
        body.put("height", height);
        body.put("paletteSize", paletteSize);
        body.put("paletteRgb565", paletteRgb565 == null ? new int[0] : paletteRgb565);
        if (maxFps != null) {
            body.put("maxFps", maxFps);
        }
        log.info("打开 Canvas 会话: device={}, {}x{}, palette={}, replaced={}",
                deviceId, width, height, paletteSize, previous);
        return requests.send(deviceId, V2Names.DISPLAY_CANVAS_OPEN, body);
    }

    /**
     * 发送一帧 Canvas 数据。
     *
     * <p>非阻塞、只保留最新帧（03§4.3）。帧先做本地校验，避免把必然被终端拒绝的数据推上网络。</p>
     */
    public boolean sendCanvasFrame(String deviceId, byte[] frame) {
        if (sessions.modeOf(deviceId) != DisplaySessionService.ViewMode.CANVAS) {
            log.debug("无活动 Canvas 会话，帧丢弃: device={}", deviceId);
            return false;
        }
        if (!sessions.onCanvasFrame(deviceId, frame)) {
            return false;
        }
        sender.sendCanvasFrame(deviceId, frame);
        return true;
    }

    /** 关闭 Canvas 会话。 */
    public CompletableFuture<PlatformRequestService.Outcome> closeCanvas(String deviceId) {
        sessions.closeCanvas(deviceId);
        return requests.send(deviceId, V2Names.DISPLAY_CANVAS_CLOSE, Map.of());
    }

    /**
     * 业务清理时清空主视图。
     *
     * <p>03§1、01§6.1：{@code business.reset} 会清除当前业务 UI 和本地导航状态；
     * 系统界面只在业务未建立、被 reset 清空或连接被确认断开时才能接管。</p>
     */
    public void clear(String deviceId) {
        sessions.clear(deviceId);
    }
}
