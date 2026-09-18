package com.zwbd.agentnexus.sdui.v2;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * v2 协议的可配置上限与超时。
 *
 * <p>04_PROTOCOL_MODEL.md §8 只给出"缓冲、队列和对象大小必须有明确上限"的原则，
 * 未给出具体数值。这里集中成配置项，便于终端升级后一次性对齐（缺口 G4 / G6）。</p>
 */
@Data
@Component
@ConfigurationProperties(prefix = "sdui.v2")
public class V2ProtocolProperties {

    /** 平台请求的默认超时（毫秒）。按请求名可覆写。 */
    private long requestTimeoutMs = 10_000L;

    /** 二进制单帧 payload 上限（字节）。 */
    private int maxBinaryFrameBytes = 8_192;

    /** 单设备控制消息出站队列上限（条）。 */
    private int maxControlQueue = 256;

    /** 单个业务配置允许的绑定数量上限。 */
    private int maxBindingsPerConfig = 32;

    /** 单条绑定允许的 Response 数量上限。 */
    private int maxResponsesPerBinding = 8;

    /** token 最大长度（字节），对应 01_INTERACTION_MODEL.md §4「有长度上限」。 */
    private int maxTokenLength = 48;

    /** 平台侧音频接收超时（毫秒），超时后标记异常终止。 */
    private long audioReceiveTimeoutMs = 15_000L;

    /** Canvas 待发帧队列深度。固定为 1 表示只保留最新帧。 */
    private int canvasPendingFrameDepth = 1;
}
