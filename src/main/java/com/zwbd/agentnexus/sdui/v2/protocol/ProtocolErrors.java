package com.zwbd.agentnexus.sdui.v2.protocol;

/**
 * v2 协议错误名集合。
 *
 * <p>终端设计文档只要求"最小错误集合"（04_PROTOCOL_MODEL.md §10），未给出具体名称。
 * 这里给出平台侧的首期集合，终端升级完成后需要对齐。
 * 详见 {@code docs/sdui/lcd085-refactor/2026-09-18/12_DESIGN_NOTES.md} 缺口 G5。</p>
 */
public final class ProtocolErrors {

    private ProtocolErrors() {}

    /** 报文不是合法的 request / result / event 三种结构之一。 */
    public static final String INVALID_ENVELOPE = "invalid_envelope";

    /** 请求名未知或当前设备不支持。 */
    public static final String UNKNOWN_NAME = "unknown_name";

    /** 设备不在线，或连接已被新连接接管。 */
    public static final String NOT_CONNECTED = "not_connected";

    /** 当前没有活动业务，无法执行依赖业务上下文的操作。 */
    public static final String BUSINESS_NOT_ACTIVE = "business_not_active";

    /** token 在当前设备配置中找不到对应绑定。 */
    public static final String BINDING_NOT_FOUND = "binding_not_found";

    /** 配置校验失败，整体拒绝且不部分应用。 */
    public static final String CONFIG_INVALID = "config_invalid";

    /** 请求参数取值非法（类型、范围或枚举不匹配）。 */
    public static final String INVALID_VALUE = "invalid_value";

    /** 资源不足，包括能力尚未同步完成。 */
    public static final String RESOURCE_EXHAUSTED = "resource_exhausted";

    /** 出站队列已满。 */
    public static final String QUEUE_FULL = "queue_full";

    /** 二进制帧超出声明的单帧上限。 */
    public static final String FRAME_TOO_LARGE = "frame_too_large";

    /** 音频互斥：正在录音时请求播放，或正在播放时请求录音。 */
    public static final String AUDIO_BUSY = "audio_busy";

    /** 没有活动的音频或显示生命周期。 */
    public static final String NO_ACTIVE_STREAM = "no_active_stream";

    /** 功能未实现或首期不支持。 */
    public static final String UNSUPPORTED = "unsupported";

    /** 平台侧等待结果超时。 */
    public static final String TIMEOUT = "timeout";

    /** 设备主动上报的异常结束原因：录音缓冲区满。 */
    public static final String REASON_BUFFER_FULL = "buffer_full";

    /** 设备主动上报的异常结束原因：播放等待新数据超时。 */
    public static final String REASON_PLAYBACK_TIMEOUT = "playback_timeout";
}
