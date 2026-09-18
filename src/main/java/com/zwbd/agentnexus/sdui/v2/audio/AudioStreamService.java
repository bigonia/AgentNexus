package com.zwbd.agentnexus.sdui.v2.audio;

import com.zwbd.agentnexus.sdui.v2.OperationResult;
import com.zwbd.agentnexus.sdui.v2.V2ProtocolProperties;
import com.zwbd.agentnexus.sdui.v2.protocol.ProtocolErrors;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 平台侧音频流状态。
 *
 * <p>01_INTERACTION_MODEL.md §5：音频等持续能力"自行维护必要的内部运行状态，不为此引入通用 Fact 层
 * 或全局状态机"；录音与播放互斥；"短暂网络断开本身不终止音频"。</p>
 *
 * <p>04_PROTOCOL_MODEL.md §7：同一设备同一方向同一时刻最多存在一个活动音频流；平台收到开始后
 * "维护该设备唯一的音频接收状态"；缓冲区满时终端立即停止，"平台将当前音频标记为异常终止，
 * 并决定丢弃还是使用已经收到的部分音频"。</p>
 *
 * <p>因此本服务按设备维护单一活动流，并只区分「正常结束」与「异常终止 + 原因」，
 * 不引入更细的状态机。</p>
 */
@Slf4j
@Service
public class AudioStreamService {

    /** 音频方向。{@code NONE} 表示无活动音频。 */
    public enum Direction {
        NONE, UPLINK, DOWNLINK
    }

    /** 流终态。 */
    public enum State {
        IDLE, ACTIVE, ABNORMAL
    }

    /** 单设备音频状态。{@code bytesReceived} 保留部分数据，由业务层决定取舍（04§7）。 */
    public record StreamState(Direction direction, State state, long startedAt, long lastDataAt,
                              long bytesReceived, String endReason) {

        public boolean isActive() {
            return state == State.ACTIVE;
        }
    }

    private final V2ProtocolProperties properties;
    private final Map<String, StreamState> streams = new ConcurrentHashMap<>();

    public AudioStreamService(V2ProtocolProperties properties) {
        this.properties = properties;
    }

    public Direction directionOf(String deviceId) {
        StreamState state = streams.get(deviceId);
        return state == null || state.state() != State.ACTIVE ? Direction.NONE : state.direction();
    }

    public boolean hasActiveStream(String deviceId) {
        return directionOf(deviceId) != Direction.NONE;
    }

    public Optional<StreamState> stateOf(String deviceId) {
        return Optional.ofNullable(streams.get(deviceId));
    }

    /**
     * 开始一个方向的音频流。
     *
     * <p>01§5：正在录音时请求播放、正在播放时请求录音，默认返回 {@code audio_busy}，
     * 不自动抢占。</p>
     */
    public OperationResult start(String deviceId, Direction direction) {
        if (direction == null || direction == Direction.NONE) {
            return OperationResult.failure(ProtocolErrors.INVALID_VALUE);
        }
        StreamState current = streams.get(deviceId);
        if (current != null && current.isActive()) {
            if (current.direction() == direction) {
                // 同方向重复开始按幂等处理：终端同样只有一个活动流
                log.debug("同方向音频流重复开始，按幂等处理: device={}, direction={}", deviceId, direction);
                return OperationResult.success();
            }
            log.info("音频方向冲突被拒绝: device={}, active={}, requested={}",
                    deviceId, current.direction(), direction);
            return OperationResult.failure(ProtocolErrors.AUDIO_BUSY);
        }
        long now = now();
        streams.put(deviceId, new StreamState(direction, State.ACTIVE, now, now, 0L, null));
        log.info("音频流开始: device={}, direction={}", deviceId, direction);
        return OperationResult.success();
    }

    /** 正常结束。无活动流时返回错误（04§8.1：没有活动生命周期时收到对应数据应拒绝）。 */
    public OperationResult stop(String deviceId) {
        StreamState current = streams.get(deviceId);
        if (current == null || !current.isActive()) {
            return OperationResult.failure(ProtocolErrors.NO_ACTIVE_STREAM);
        }
        streams.put(deviceId, new StreamState(current.direction(), State.IDLE,
                current.startedAt(), now(), current.bytesReceived(), "stopped"));
        log.info("音频流正常结束: device={}, direction={}, bytes={}",
                deviceId, current.direction(), current.bytesReceived());
        return OperationResult.success();
    }

    /** 异常终止，携带原因（例如 {@code buffer_full}、{@code playback_timeout}）。 */
    public OperationResult abort(String deviceId, String reason) {
        StreamState current = streams.get(deviceId);
        if (current == null || !current.isActive()) {
            return OperationResult.failure(ProtocolErrors.NO_ACTIVE_STREAM);
        }
        streams.put(deviceId, new StreamState(current.direction(), State.ABNORMAL,
                current.startedAt(), now(), current.bytesReceived(), reason));
        log.warn("音频流异常终止: device={}, direction={}, reason={}, receivedBytes={}",
                deviceId, current.direction(), reason, current.bytesReceived());
        return OperationResult.success();
    }

    /**
     * 录音缓冲区满。
     *
     * <p>01§5 与 04§7：终端立即停止采集、丢弃未发送缓冲、结束录音反馈并释放状态；
     * 平台侧标记异常终止，但**保留**已收到的部分数据由业务层决定取舍。</p>
     */
    public OperationResult onBufferFull(String deviceId) {
        return abort(deviceId, ProtocolErrors.REASON_BUFFER_FULL);
    }

    /** 收到上行音频数据。无活动上行流时返回 false，数据被丢弃并计数。 */
    public boolean onUplinkData(String deviceId, byte[] chunk) {
        StreamState current = streams.get(deviceId);
        if (current == null || !current.isActive() || current.direction() != Direction.UPLINK) {
            log.debug("无活动上行音频流，数据被丢弃: device={}", deviceId);
            return false;
        }
        streams.put(deviceId, new StreamState(current.direction(), State.ACTIVE, current.startedAt(),
                now(), current.bytesReceived() + (chunk == null ? 0 : chunk.length), null));
        return true;
    }

    /**
     * 平台侧音频等待超时。
     *
     * <p>04§6：录音缓冲满或持续无数据时平台标记异常终止；播放"缺少新数据时等待，超过播放等待时限
     * 后结束并清理"。</p>
     */
    @Scheduled(fixedDelayString = "${sdui.v2.audio-timeout-scan-ms:2000}")
    public void sweepTimeouts() {
        long now = now();
        for (Map.Entry<String, StreamState> entry : new ArrayList<>(streams.entrySet())) {
            StreamState state = entry.getValue();
            if (!state.isActive()) {
                continue;
            }
            if (now - state.lastDataAt() < properties.getAudioReceiveTimeoutMs()) {
                continue;
            }
            String reason = state.direction() == Direction.UPLINK
                    ? ProtocolErrors.REASON_BUFFER_FULL
                    : ProtocolErrors.REASON_PLAYBACK_TIMEOUT;
            abort(entry.getKey(), reason);
        }
    }

    /** 业务清理：结束并移除音频状态（01§6.1「停止录音、音频上传和播放」）。 */
    public List<String> clearAll(String deviceId) {
        List<String> ended = new ArrayList<>();
        StreamState removed = streams.remove(deviceId);
        if (removed != null) {
            ended.add(removed.direction().name().toLowerCase());
        }
        return ended;
    }

    public int activeStreamCount() {
        return (int) streams.values().stream().filter(StreamState::isActive).count();
    }

    protected long now() {
        return System.currentTimeMillis();
    }
}
