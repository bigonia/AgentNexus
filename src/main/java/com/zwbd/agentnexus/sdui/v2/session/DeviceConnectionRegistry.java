package com.zwbd.agentnexus.sdui.v2.session;

import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.WebSocketSession;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 设备连接注册表：保证同一 {@code device_id} 同一时刻只有一个有效连接。
 *
 * <p>对应 04_PROTOCOL_MODEL.md §2：</p>
 *
 * <pre>
 * 新连接建立 → 平台将新连接设为当前连接 → 旧连接失效 → 旧连接后续消息全部忽略
 * </pre>
 *
 * <p>与旧的 {@code DeviceSessionManager} 的关键差异：</p>
 * <ul>
 *   <li>不写设备表、不记连接日志，只维护连接本身（旧实现把连接管理与设备持久化耦合在一起）；</li>
 *   <li>引入 {@code generation}，使上行报文可以做代次校验，旧连接消息被明确忽略而非静默处理。</li>
 * </ul>
 */
@Slf4j
@Component
public class DeviceConnectionRegistry {

    private final Map<String, DeviceConnection> connections = new ConcurrentHashMap<>();
    private final Map<String, String> sessionToDevice = new ConcurrentHashMap<>();
    private final AtomicLong generationSequence = new AtomicLong();
    private final ApplicationEventPublisher eventPublisher;

    public DeviceConnectionRegistry(ApplicationEventPublisher eventPublisher) {
        this.eventPublisher = eventPublisher;
    }

    /**
     * 注册或接管一个设备连接。
     *
     * @return 新建立的连接对象，调用方应持有其 {@code generation} 用于上行代次校验
     */
    public DeviceConnection attach(String deviceId, WebSocketSession session, DeviceHandshake handshake) {
        DeviceConnection existingSameSession = connections.get(deviceId);
        if (existingSameSession != null && session.getId().equals(existingSameSession.sessionId())) {
            // 同一会话重复 attach 按幂等处理，避免重复建立连接对象或自增代次
            existingSameSession.updateHandshake(existingSameSession.getHandshake().merge(handshake));
            return existingSameSession;
        }

        long generation = generationSequence.incrementAndGet();
        DeviceConnection connection = new DeviceConnection(deviceId, generation, session, handshake);

        DeviceConnection previous = connections.put(deviceId, connection);
        sessionToDevice.put(session.getId(), deviceId);

        long previousGeneration = 0L;
        if (previous != null) {
            previousGeneration = previous.getGeneration();
            sessionToDevice.remove(previous.sessionId(), deviceId);
            log.info("设备连接被接管: device={}, oldGeneration={}, newGeneration={}, oldSession={}, newSession={}",
                    deviceId, previousGeneration, generation, previous.sessionId(), session.getId());
        } else {
            log.info("设备首次连接: device={}, generation={}, session={}", deviceId, generation, session.getId());
        }

        eventPublisher.publishEvent(new DeviceConnectionTakenOverEvent(deviceId, previousGeneration, generation));
        return connection;
    }

    /**
     * 连接关闭时移除。只有当关闭的连接仍是当前连接时才真正下线；
     * 已被新连接接管的旧连接关闭不影响在线状态。
     */
    public void detach(WebSocketSession session) {
        if (session == null) {
            return;
        }
        String deviceId = sessionToDevice.remove(session.getId());
        if (deviceId == null) {
            return;
        }
        boolean removed = connections.computeIfPresent(deviceId, (key, current) -> {
            if (current.sessionId().equals(session.getId())) {
                log.info("设备连接已关闭并下线: device={}, generation={}", deviceId, current.getGeneration());
                return null;
            }
            log.debug("旧连接关闭，设备仍由新连接持有: device={}, closedSession={}", deviceId, session.getId());
            return current;
        }) == null;
        if (!removed) {
            log.debug("连接关闭事件来自非当前连接: device={}", deviceId);
        }
    }

    /** 当前有效连接。 */
    public Optional<DeviceConnection> find(String deviceId) {
        return Optional.ofNullable(connections.get(deviceId));
    }

    /** 设备是否在线。 */
    public boolean isOnline(String deviceId) {
        return find(deviceId).map(DeviceConnection::isOpen).orElse(false);
    }

    /**
     * 主动断开设备连接。
     *
     * <p>用于设备注销等平台侧强制场景。关闭底层会话即可——后续的断开事件会自然走到
     * {@link #detach}，不需要在这里重复做注销。</p>
     *
     * @return 是否存在过连接
     */
    public boolean disconnect(String deviceId) {
        return find(deviceId).map(connection -> {
            connection.close();
            log.info("平台主动断开设备连接: device={}, generation={}",
                    deviceId, connection.getGeneration());
            return true;
        }).orElse(false);
    }

    /**
     * 上行报文代次校验。旧连接的报文 generation 会小于当前值，据此忽略。
     */
    public boolean isCurrent(String deviceId, long generation) {
        return find(deviceId)
                .filter(DeviceConnection::isOpen)
                .map(connection -> connection.getGeneration() == generation)
                .orElse(false);
    }

    public long currentGeneration(String deviceId) {
        return find(deviceId).map(DeviceConnection::getGeneration).orElse(0L);
    }

    public String deviceIdOfSession(String sessionId) {
        return sessionToDevice.get(sessionId);
    }
}
