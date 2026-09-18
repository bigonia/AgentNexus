package com.zwbd.agentnexus.sdui.v2.system;

import com.zwbd.agentnexus.sdui.v2.protocol.ProtocolErrors;
import com.zwbd.agentnexus.sdui.v2.protocol.V2Names;
import com.zwbd.agentnexus.sdui.v2.transport.PlatformRequestService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 系统命令：音量、亮度、重启、配网。
 *
 * <p>02_SYSTEM_BOUNDARY.md §4 规定这些功能的产品行为不属于本轮重构范围，但必须"完整迁移到新的
 * 统一 request/result 协议，旧 {@code cmd/control}、旧 action 名称和旧 ACK 路径不保留、不兼容、
 * 也不并行运行"。音量、亮度等期望状态由平台维护，终端不持久化、不做版本同步或重连恢复。</p>
 *
 * <p>关于"终端不持久化"带来的一个推论：终端重启与网络重连在平台看来不可区分（04§3 不使用
 * {@code boot_id}），因此平台在连接接管后按需重发系统期望值，符合 §4「平台认为有必要时在
 * 连接建立后重新下发」。见 {@code 12_DESIGN_NOTES.md} §4.4。</p>
 */
@Slf4j
@Service
public class SystemCommandService {

    /** 平台侧维护的系统期望状态。 */
    public record DesiredSystemState(Integer volume, Integer brightness) {

        public boolean isUnset() {
            return volume == null && brightness == null;
        }

        public DesiredSystemState withVolume(Integer newVolume) {
            return new DesiredSystemState(newVolume, brightness);
        }

        public DesiredSystemState withBrightness(Integer newBrightness) {
            return new DesiredSystemState(volume, newBrightness);
        }
    }

    private static final int MIN_PERCENT = 0;
    private static final int MAX_PERCENT = 100;

    private final PlatformRequestService requests;
    private final Map<String, DesiredSystemState> desiredStates = new ConcurrentHashMap<>();

    public SystemCommandService(PlatformRequestService requests) {
        this.requests = requests;
    }

    public CompletableFuture<PlatformRequestService.Outcome> setVolume(String deviceId, int value) {
        if (value < MIN_PERCENT || value > MAX_PERCENT) {
            return CompletableFuture.completedFuture(
                    PlatformRequestService.Outcome.failure(ProtocolErrors.INVALID_VALUE));
        }
        desiredStates.merge(deviceId, new DesiredSystemState(value, null),
                (existing, incoming) -> existing.withVolume(incoming.volume()));
        return requests.send(deviceId, V2Names.SYSTEM_VOLUME_SET, Map.of("value", value));
    }

    public CompletableFuture<PlatformRequestService.Outcome> setBrightness(String deviceId, int value) {
        if (value < MIN_PERCENT || value > MAX_PERCENT) {
            return CompletableFuture.completedFuture(
                    PlatformRequestService.Outcome.failure(ProtocolErrors.INVALID_VALUE));
        }
        desiredStates.merge(deviceId, new DesiredSystemState(null, value),
                (existing, incoming) -> existing.withBrightness(incoming.brightness()));
        return requests.send(deviceId, V2Names.SYSTEM_BRIGHTNESS_SET, Map.of("value", value));
    }

    public CompletableFuture<PlatformRequestService.Outcome> reboot(String deviceId) {
        return requests.send(deviceId, V2Names.SYSTEM_REBOOT, Map.of());
    }

    public CompletableFuture<PlatformRequestService.Outcome> startProvisioning(String deviceId) {
        return requests.send(deviceId, V2Names.SYSTEM_PROVISIONING_START, Map.of());
    }

    /**
     * 连接接管后重发系统期望值。
     *
     * <p>不清理、不持久化到终端，只是重新声明平台认为正确的状态。</p>
     */
    public List<CompletableFuture<PlatformRequestService.Outcome>> resendDesired(String deviceId) {
        DesiredSystemState desired = desiredStates.get(deviceId);
        if (desired == null || desired.isUnset()) {
            return List.of();
        }
        List<CompletableFuture<PlatformRequestService.Outcome>> futures = new ArrayList<>(2);
        if (desired.volume() != null) {
            futures.add(requests.send(deviceId, V2Names.SYSTEM_VOLUME_SET, Map.of("value", desired.volume())));
        }
        if (desired.brightness() != null) {
            futures.add(requests.send(deviceId, V2Names.SYSTEM_BRIGHTNESS_SET,
                    Map.of("value", desired.brightness())));
        }
        log.info("连接接管后重发系统期望值: device={}, volume={}, brightness={}",
                deviceId, desired.volume(), desired.brightness());
        return futures;
    }

    /** 系统期望状态**不**被 {@code business.reset} 清除（02§4）。 */
    public DesiredSystemState desiredOf(String deviceId) {
        return desiredStates.getOrDefault(deviceId, new DesiredSystemState(null, null));
    }

    public void forgetDevice(String deviceId) {
        desiredStates.remove(deviceId);
    }
}
