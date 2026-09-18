package com.zwbd.agentnexus.sdui.v2.business;

import java.time.Instant;

/**
 * 一次业务交互上报（平台侧领域事件）。
 *
 * <p>01_INTERACTION_MODEL.md §4：终端触发 {@code platform.interaction.report(token)} 时只上报
 * {@code device_id + token}；"平台根据设备和 token 恢复业务上下文、维护业务状态并决定后续操作"。</p>
 *
 * <p>{@code triggerId} 与 {@code contextRef} 都是平台在生成 token 时写入的，终端不感知，
 * 因此这里可以安全地承载业务语义。</p>
 */
public record BusinessInteraction(String deviceId, String token, String triggerId, String contextRef,
                                  Instant receivedAt) {
}
