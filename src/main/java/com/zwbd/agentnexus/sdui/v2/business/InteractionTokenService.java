package com.zwbd.agentnexus.sdui.v2.business;

import com.zwbd.agentnexus.sdui.v2.V2ProtocolProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 不透明 token 注册表。
 *
 * <p>01_INTERACTION_MODEL.md §3 与 §4 定义了方向相反的两类 token，二者"可以采用相同的字段形式，
 * 但分别属于当前配置中的上报 Response 和云端 Trigger，不隐含递归关系"。</p>
 *
 * <pre>
 * 出站  platform.trigger(token)          平台生成 → 终端匹配后执行预设序列
 * 入站  platform.interaction.report(token) 平台生成 → 终端原样上报 → 平台反查业务上下文
 * </pre>
 *
 * <p>两条路径使用**相互独立**的注册表与不同前缀。这是刻意的设计（见
 * {@code 12_DESIGN_NOTES.md} §4.5）：若共用一张表，一个上报 token 被误用于 {@code business.trigger}
 * 会造成语义错位的递归执行。</p>
 *
 * <p>token 长度上限来自 {@link V2ProtocolProperties#getMaxTokenLength()}，对应 §4「有长度上限」。
 * 具体格式属平台侧临时假设（缺口 G15）。</p>
 */
@Slf4j
@Component
public class InteractionTokenService {

    /** token 所属方向。 */
    public enum Domain {
        /** 云端 Trigger，下发给终端用于匹配绑定。 */
        TRIGGER("pt_"),
        /** 业务交互上报，终端原样回传。 */
        REPORT("rt_");

        private final String prefix;

        Domain(String prefix) {
            this.prefix = prefix;
        }

        public String prefix() {
            return prefix;
        }
    }

    /** token 解析结果。{@code contextRef} 由平台解释，终端不感知。 */
    public record TokenRef(Domain domain, String token, String deviceId, String triggerId, String contextRef) {}

    private static final char[] ALPHABET = "0123456789abcdefghijklmnopqrstuvwxyz".toCharArray();
    private static final int BODY_LENGTH = 24;

    private final Map<String, TokenRef> triggerTokens = new ConcurrentHashMap<>();
    private final Map<String, TokenRef> reportTokens = new ConcurrentHashMap<>();
    private final SecureRandom random = new SecureRandom();

    public String issueTriggerToken(String deviceId, String triggerId, String contextRef) {
        return issue(Domain.TRIGGER, deviceId, triggerId, contextRef, triggerTokens);
    }

    public String issueReportToken(String deviceId, String triggerId, String contextRef) {
        return issue(Domain.REPORT, deviceId, triggerId, contextRef, reportTokens);
    }

    private String issue(Domain domain, String deviceId, String triggerId, String contextRef,
                         Map<String, TokenRef> registry) {
        String token = generate(domain);
        registry.put(token, new TokenRef(domain, token, deviceId, triggerId, contextRef));
        return token;
    }

    /** 解析云端 Trigger token；不存在的 token 返回空。 */
    public Optional<TokenRef> resolveTrigger(String token) {
        return Optional.ofNullable(token == null ? null : triggerTokens.get(token));
    }

    /** 解析上报 token；不存在的 token 返回空。 */
    public Optional<TokenRef> resolveReport(String token) {
        return Optional.ofNullable(token == null ? null : reportTokens.get(token));
    }

    /** 撤销指定 token（两个方向都尝试）。 */
    public void revoke(String token) {
        if (token == null) {
            return;
        }
        triggerTokens.remove(token);
        reportTokens.remove(token);
    }

    public void revokeAll(List<String> tokens) {
        if (tokens == null) {
            return;
        }
        tokens.forEach(this::revoke);
    }

    /**
     * 只保留给定 token，撤销该设备其余全部 token。
     *
     * <p>用于全量配置原子替换成功后：旧配置的 token 不应再能被触发或上报。</p>
     */
    public List<String> retainOnly(String deviceId, List<String> keepTokens) {
        List<String> keep = keepTokens == null ? List.of() : List.copyOf(keepTokens);
        List<String> revoked = new ArrayList<>();
        revoked.addAll(revokeMatching(triggerTokens, deviceId, keep));
        revoked.addAll(revokeMatching(reportTokens, deviceId, keep));
        return revoked;
    }

    /** 撤销某设备全部 token。用于 {@code business.reset}。 */
    public List<String> revokeAllOfDevice(String deviceId) {
        return retainOnly(deviceId, List.of());
    }

    private List<String> revokeMatching(Map<String, TokenRef> registry, String deviceId, List<String> keep) {
        List<String> revoked = new ArrayList<>();
        for (Map.Entry<String, TokenRef> entry : registry.entrySet()) {
            TokenRef ref = entry.getValue();
            if (deviceId.equals(ref.deviceId()) && !keep.contains(entry.getKey())) {
                if (registry.remove(entry.getKey(), ref)) {
                    revoked.add(entry.getKey());
                }
            }
        }
        return revoked;
    }

    public int triggerTokenCount() {
        return triggerTokens.size();
    }

    public int reportTokenCount() {
        return reportTokens.size();
    }

    public int tokenCountOfDevice(String deviceId) {
        int count = 0;
        for (TokenRef ref : triggerTokens.values()) {
            if (deviceId.equals(ref.deviceId())) {
                count++;
            }
        }
        for (TokenRef ref : reportTokens.values()) {
            if (deviceId.equals(ref.deviceId())) {
                count++;
            }
        }
        return count;
    }

    private String generate(Domain domain) {
        StringBuilder sb = new StringBuilder(domain.prefix());
        for (int i = 0; i < BODY_LENGTH; i++) {
            sb.append(ALPHABET[random.nextInt(ALPHABET.length)]);
        }
        String token = sb.toString().toLowerCase(Locale.ROOT);
        // 极端碰撞下重试，避免覆盖已有注册项
        boolean exists = triggerTokens.containsKey(token) || reportTokens.containsKey(token);
        return exists ? generate(domain) : token;
    }
}
