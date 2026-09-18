package com.zwbd.agentnexus.sdui.v2.display;

import com.zwbd.agentnexus.sdui.v2.protocol.ProtocolErrors;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 平台侧主视图会话跟踪。
 *
 * <p>03_UI_MODEL.md §1：LCD_085 同一时间只存在一个平台主视图，三种主视图互斥，新的主视图完整替换
 * 旧主视图。§5 又强调图片与 Canvas 的产品语义和生命周期保持独立，不能因为底层复用而合并成
 * 含义模糊的通用 UI 消息。因此这里按模式分别跟踪，而不是抽象成一个统一的"显示对象"。</p>
 *
 * <p>平台侧需要这份状态只为了实现两件事：</p>
 * <ol>
 *   <li>在发出新主视图前结束上一个生命周期（03§1、04§8.1）；</li>
 *   <li>校验图片与 Canvas 的数据长度、调色板与分辨率上限（03§4.3）。</li>
 * </ol>
 *
 * <p>它**不是**终端显示的镜像：终端在短暂断网时保留主视图，平台不做镜像恢复，只说"身份已</p>
 * 记录、需要时可重新下发"。</p>
 */
@Slf4j
@Service
public class DisplaySessionService {

    /** 主视图模式。{@code NONE} 表示当前无平台主视图（业务未接入或已被 reset 清空）。 */
    public enum ViewMode {
        NONE, SECTION, IMAGE, CANVAS
    }

    /** 全屏图片参数。 */
    public record ImageSpec(int width, int height, int paletteSize, long expectedBytes) {}

    /** Canvas 会话参数。分辨率、色数与帧率上限来自终端能力 Schema（缺口 G21）。 */
    public record CanvasSpec(int width, int height, int paletteSize, Integer maxFps) {}

    /** 单设备主视图状态。 */
    public static final class DeviceDisplayState {

        private ViewMode mode = ViewMode.NONE;
        private ImageSpec imageSpec;
        private CanvasSpec canvasSpec;
        private long imageBytesReceived;
        private long canvasFramesReceived;
        private long canvasFramesRejected;

        public ViewMode mode() {
            return mode;
        }

        public ImageSpec imageSpec() {
            return imageSpec;
        }

        public CanvasSpec canvasSpec() {
            return canvasSpec;
        }

        public long imageBytesReceived() {
            return imageBytesReceived;
        }

        public long canvasFramesReceived() {
            return canvasFramesReceived;
        }

        public long canvasFramesRejected() {
            return canvasFramesRejected;
        }
    }

    private final Map<String, DeviceDisplayState> states = new ConcurrentHashMap<>();

    public ViewMode modeOf(String deviceId) {
        return state(deviceId).mode;
    }

    public DeviceDisplayState snapshot(String deviceId) {
        return state(deviceId);
    }

    public Optional<CanvasSpec> canvasSpec(String deviceId) {
        return Optional.ofNullable(state(deviceId).canvasSpec);
    }

    public Optional<ImageSpec> imageSpec(String deviceId) {
        return Optional.ofNullable(state(deviceId).imageSpec);
    }

    /**
     * 开始一个新的 Section 主视图，结束上一个互斥主视图。
     *
     * @return 被替换掉的旧模式，便于日志与观测
     */
    public ViewMode beginSection(String deviceId) {
        DeviceDisplayState state = state(deviceId);
        ViewMode previous = replace(state, ViewMode.SECTION);
        state.imageSpec = null;
        state.canvasSpec = null;
        state.imageBytesReceived = 0L;
        return previous;
    }

    /** 开始接收全屏图片。 */
    public ViewMode beginImage(String deviceId, int width, int height, int paletteSize, long expectedBytes) {
        DeviceDisplayState state = state(deviceId);
        ViewMode previous = replace(state, ViewMode.IMAGE);
        state.imageSpec = new ImageSpec(width, height, paletteSize, expectedBytes);
        state.canvasSpec = null;
        state.imageBytesReceived = 0L;
        return previous;
    }

    /** 累加图片数据；返回累计字节数。无活动图片生命周期时返回 -1。 */
    public long onImageChunk(String deviceId, byte[] chunk) {
        DeviceDisplayState state = state(deviceId);
        if (state.mode != ViewMode.IMAGE || state.imageSpec == null) {
            return -1L;
        }
        state.imageBytesReceived += chunk == null ? 0 : chunk.length;
        return state.imageBytesReceived;
    }

    /** 结束图片传输；数据不足时视为不完整对象，按 04§6 语义可从头上重发。 */
    public boolean completeImage(String deviceId) {
        DeviceDisplayState state = state(deviceId);
        if (state.mode != ViewMode.IMAGE || state.imageSpec == null) {
            return false;
        }
        boolean complete = state.imageBytesReceived >= state.imageSpec.expectedBytes();
        if (!complete) {
            log.warn("图片传输不完整: device={}, received={}, expected={}",
                    deviceId, state.imageBytesReceived, state.imageSpec.expectedBytes());
        }
        state.mode = ViewMode.NONE;
        state.imageSpec = null;
        state.imageBytesReceived = 0L;
        return complete;
    }

    /** 打开 Canvas 会话。 */
    public ViewMode openCanvas(String deviceId, int width, int height, int paletteSize, Integer maxFps) {
        DeviceDisplayState state = state(deviceId);
        ViewMode previous = replace(state, ViewMode.CANVAS);
        state.canvasSpec = new CanvasSpec(width, height, paletteSize, maxFps);
        state.imageSpec = null;
        state.canvasFramesReceived = 0L;
        state.canvasFramesRejected = 0L;
        return previous;
    }

    /**
     * 校验并记录一帧 Canvas 数据。
     *
     * @return 校验是否通过；不通过时帧被拒绝并计数，会话保持
     */
    public boolean onCanvasFrame(String deviceId, byte[] frame) {
        DeviceDisplayState state = state(deviceId);
        if (state.mode != ViewMode.CANVAS || state.canvasSpec == null) {
            return false;
        }
        CanvasSpec spec = state.canvasSpec;
        int pixelCount = spec.width() * spec.height();
        try {
            PaletteImageCodec.validateIndexMatrix(frame, pixelCount, spec.paletteSize());
        } catch (RuntimeException e) {
            state.canvasFramesRejected++;
            log.warn("Canvas 帧校验失败并被拒绝: device={}, error={}", deviceId, e.getMessage());
            return false;
        }
        state.canvasFramesReceived++;
        return true;
    }

    /** 关闭 Canvas 会话。 */
    public ViewMode closeCanvas(String deviceId) {
        DeviceDisplayState state = state(deviceId);
        ViewMode previous = state.mode;
        state.mode = ViewMode.NONE;
        state.canvasSpec = null;
        state.canvasFramesReceived = 0L;
        state.canvasFramesRejected = 0L;
        return previous;
    }

    /**
     * 清空当前主视图。
     *
     * <p>{@code business.reset} 会调用此方法（01§6.1「清除当前业务 UI 和本地导航状态」）。</p>
     */
    public void clear(String deviceId) {
        states.remove(deviceId);
    }

    private DeviceDisplayState state(String deviceId) {
        return states.computeIfAbsent(deviceId, key -> new DeviceDisplayState());
    }

    private ViewMode replace(DeviceDisplayState state, ViewMode next) {
        ViewMode previous = state.mode;
        if (previous != ViewMode.NONE && previous != next) {
            log.debug("主视图被替换: {} -> {}", previous, next);
        }
        state.mode = next;
        return previous;
    }

    /** 供上层判断是否需要先关闭旧会话。 */
    public boolean hasActiveView(String deviceId) {
        return modeOf(deviceId) != ViewMode.NONE;
    }

    /** 当前模式对应的名称，用于错误信息与日志。 */
    public String modeName(String deviceId) {
        return modeOf(deviceId).name().toLowerCase();
    }

    /** 无活动主视图时的标准错误。 */
    public static String noActiveStreamError() {
        return ProtocolErrors.NO_ACTIVE_STREAM;
    }
}
