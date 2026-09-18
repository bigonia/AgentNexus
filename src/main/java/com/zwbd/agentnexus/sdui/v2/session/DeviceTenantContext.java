package com.zwbd.agentnexus.sdui.v2.session;

import com.zwbd.agentnexus.common.web.GlobalContext;
import com.zwbd.agentnexus.sdui.repo.SduiDeviceRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.function.Supplier;

/**
 * 在设备线程上建立该设备归属用户的租户上下文。
 *
 * <h2>为什么必须有这一层</h2>
 * <p>平台的数据隔离靠 Hibernate 的 {@code @TenantId} 实现，租户取自
 * {@link GlobalContext#getUserId()}（见 {@code UserIdResolver}）。设备连接跑在 WebSocket 线程上，
 * 这个线程没有 HTTP 请求上下文，租户会退化成 {@code default}。</p>
 *
 * <p>而设备表 {@code sdui_device} <b>不是</b>租户表——它是一张全局表，用 {@code ownerUserId} 列记录
 * 归属。工作流部署、运行、UI 上下文、artifact 这些表才是租户表。于是同一个设备线程上：
 * 读设备成功（全局表），读该设备的部署记录却查不到（租户不匹配）。</p>
 *
 * <p>旧协议栈里 {@code MessageRouter} 已经做了这件事——在每条消息分发前按设备归属设置租户。
 * v2 接入层最初漏掉了这一步；本类补上，并作为 v2 侧唯一的租户建立入口。</p>
 *
 * <h2>为什么读设备这一步在设租户之前</h2>
 * <p>租户信息本身就来自设备行，存在先有鸡还是先有蛋：必须先以"无租户过滤"的方式读到设备，才能
 * 知道该用哪个租户。{@code sdui_device} 没有 {@code @TenantId}，所以这一步天然可行；若将来它被
 * 改成租户表，这里会立刻查不到设备，属于会显式暴露的失败而不是静默串租户。</p>
 */
@Slf4j
@Component
public class DeviceTenantContext {

    private final SduiDeviceRepository deviceRepository;

    public DeviceTenantContext(SduiDeviceRepository deviceRepository) {
        this.deviceRepository = deviceRepository;
    }

    /** 设备归属用户；未认领或查不到时返回 {@code default}。 */
    public String ownerOf(String deviceId) {
        if (deviceId == null || deviceId.isBlank()) {
            return GlobalContext.DEFAULT_USER_ID;
        }
        return deviceRepository.findById(deviceId)
                .map(device -> device.getOwnerUserId())
                .filter(owner -> owner != null && !owner.isBlank())
                .orElse(GlobalContext.DEFAULT_USER_ID);
    }

    /** 在设备归属租户下执行并返回结果。 */
    public <T> T callWith(String deviceId, Supplier<T> action) {
        String previous = GlobalContext.getString(GlobalContext.KEY_USER_ID);
        GlobalContext.set(GlobalContext.KEY_USER_ID, ownerOf(deviceId));
        try {
            return action.get();
        } finally {
            restore(previous);
        }
    }

    /** 在设备归属租户下执行。 */
    public void runWith(String deviceId, Runnable action) {
        callWith(deviceId, () -> {
            action.run();
            return null;
        });
    }

    private static void restore(String previous) {
        if (previous == null || previous.isBlank()) {
            GlobalContext.clear();
        } else {
            GlobalContext.set(GlobalContext.KEY_USER_ID, previous);
        }
    }
}
