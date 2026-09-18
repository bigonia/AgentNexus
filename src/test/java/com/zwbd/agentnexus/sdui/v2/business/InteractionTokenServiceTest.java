package com.zwbd.agentnexus.sdui.v2.business;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * token 注册表：两域隔离、撤销语义。
 *
 * <p>两域必须隔离是 01_INTERACTION_MODEL.md §4 的明确要求：上报 token 被误用于
 * {@code business.trigger} 会形成语义错位的递归执行。</p>
 */
class InteractionTokenServiceTest {

    private final InteractionTokenService service = new InteractionTokenService();

    @Test
    @DisplayName("触发 token 不能被当作上报 token 解析，反之亦然")
    void keepsDomainsIsolated() {
        String triggerToken = service.issueTriggerToken("dev-1", "platform.trigger", "ctx");
        String reportToken = service.issueReportToken("dev-1", "button.ok", "ctx");

        assertTrue(service.resolveTrigger(triggerToken).isPresent());
        assertTrue(service.resolveReport(reportToken).isPresent());

        assertTrue(service.resolveReport(triggerToken).isEmpty());
        assertTrue(service.resolveTrigger(reportToken).isEmpty());
        assertNotEquals(triggerToken, reportToken);
    }

    @Test
    @DisplayName("token 带方向前缀且长度不超过上限")
    void tokenShape() {
        String triggerToken = service.issueTriggerToken("dev-1", "platform.trigger", "ctx");
        String reportToken = service.issueReportToken("dev-1", "button.ok", "ctx");

        assertTrue(triggerToken.startsWith(InteractionTokenService.Domain.TRIGGER.prefix()));
        assertTrue(reportToken.startsWith(InteractionTokenService.Domain.REPORT.prefix()));
        assertTrue(triggerToken.length() <= 48);
        assertTrue(reportToken.length() <= 48);
    }

    @Test
    @DisplayName("重复签发产生不同 token，且都能解析到自己的上下文")
    void issuesUniqueTokens() {
        String first = service.issueTriggerToken("dev-1", "platform.trigger", "ctx-a");
        String second = service.issueTriggerToken("dev-1", "platform.trigger", "ctx-b");

        assertNotEquals(first, second);
        assertEquals("ctx-a", service.resolveTrigger(first).orElseThrow().contextRef());
        assertEquals("ctx-b", service.resolveTrigger(second).orElseThrow().contextRef());
    }

    @Test
    @DisplayName("retainOnly 撤销该设备其余 token，保留指定项")
    void retainOnlyRevokesTheRest() {
        String keptTrigger = service.issueTriggerToken("dev-1", "platform.trigger", "ctx");
        String keptReport = service.issueReportToken("dev-1", "button.ok", "ctx");
        String staleTrigger = service.issueTriggerToken("dev-1", "platform.trigger", "old");
        String staleReport = service.issueReportToken("dev-1", "button.ok", "old");
        String otherDevice = service.issueTriggerToken("dev-2", "platform.trigger", "ctx");

        List<String> revoked = service.retainOnly("dev-1", List.of(keptTrigger, keptReport));

        assertEquals(2, revoked.size());
        assertTrue(service.resolveTrigger(keptTrigger).isPresent());
        assertTrue(service.resolveReport(keptReport).isPresent());
        assertTrue(service.resolveTrigger(staleTrigger).isEmpty());
        assertTrue(service.resolveReport(staleReport).isEmpty());
        // 其他设备的 token 不受影响
        assertTrue(service.resolveTrigger(otherDevice).isPresent());
    }

    @Test
    @DisplayName("revokeAllOfDevice 撤销该设备全部 token")
    void revokeAllOfDevice() {
        String triggerToken = service.issueTriggerToken("dev-1", "platform.trigger", "ctx");
        String reportToken = service.issueReportToken("dev-1", "button.ok", "ctx");
        String otherDevice = service.issueReportToken("dev-2", "button.ok", "ctx");

        List<String> revoked = service.revokeAllOfDevice("dev-1");

        assertEquals(2, revoked.size());
        assertTrue(service.resolveTrigger(triggerToken).isEmpty());
        assertTrue(service.resolveReport(reportToken).isEmpty());
        assertTrue(service.resolveReport(otherDevice).isPresent());
        assertEquals(0, service.tokenCountOfDevice("dev-1"));
    }

    @Test
    @DisplayName("未知 token 解析为空，不抛异常")
    void unknownToken() {
        assertTrue(service.resolveTrigger("pt_nope").isEmpty());
        assertTrue(service.resolveReport("rt_nope").isEmpty());
        assertTrue(service.resolveTrigger(null).isEmpty());
        assertTrue(service.resolveReport(null).isEmpty());
        assertFalse(service.resolveTrigger("").isPresent());
    }
}
