package com.zwbd.agentnexus.sdui.v2.session;

/**
 * 设备连接被新连接接管的事件。
 *
 * <p>接管发生后需要：把旧连接上未完成的请求按 {@code not_connected} 结束、重新下发当前
 * 完整业务配置、重发生效中的系统命令期望值。这些动作由监听方（{@code V2SessionBootstrapService}）
 * 执行，注册表本身不承担业务语义。</p>
 */
public record DeviceConnectionTakenOverEvent(String deviceId, long previousGeneration, long currentGeneration) {

    /** 是否为首次连接（没有旧连接被替换）。 */
    public boolean isFirstConnection() {
        return previousGeneration == 0L;
    }
}
