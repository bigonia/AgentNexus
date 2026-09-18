package com.zwbd.agentnexus.sdui.v2.session;

import com.zwbd.agentnexus.sdui.v2.V2TestSupport;
import com.zwbd.agentnexus.sdui.v2.protocol.ProtocolErrors;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 未完成请求登记：完成、超时与接管清理。
 */
class PendingRequestRegistryTest {

    /** 可注入时钟的测试子类。 */
    private static final class Testable extends PendingRequestRegistry {
        private long clock = 1_000L;

        void advance(long millis) {
            clock += millis;
        }

        @Override
        protected long now() {
            return clock;
        }
    }

    @Test
    @DisplayName("登记后可用结果完成，且只能完成一次")
    void completesOnce() {
        Testable registry = new Testable();
        registry.register("dev-1", "req-1", "business.reset", 5_000L);
        assertEquals(1, registry.pendingCount("dev-1"));

        var completion = registry.complete("dev-1", "req-1", true, null);
        assertTrue(completion.isPresent());
        assertTrue(completion.get().ok());
        assertEquals("business.reset", completion.get().name());
        assertEquals(0, registry.pendingCount("dev-1"));

        // 重复结果（迟到或重放）不再匹配
        assertTrue(registry.complete("dev-1", "req-1", true, null).isEmpty());
    }

    @Test
    @DisplayName("超时的请求被扫描出来，未到期的保持挂起")
    void sweepsExpiredOnly() {
        Testable registry = new Testable();
        registry.register("dev-1", "req-fast", "a", 500L);
        registry.register("dev-1", "req-slow", "b", 10_000L);

        registry.advance(600L);
        List<PendingRequestRegistry.Completion> expired = registry.sweep();

        assertEquals(1, expired.size());
        assertEquals("req-fast", expired.get(0).id());
        assertEquals(ProtocolErrors.TIMEOUT, expired.get(0).error());
        assertTrue(expired.get(0).isTimeout());
        assertEquals(1, registry.pendingCount("dev-1"));
    }

    @Test
    @DisplayName("接管时该设备全部未完成请求按指定错误结束，其他设备不受影响")
    void failsAllForDevice() {
        Testable registry = new Testable();
        registry.register("dev-1", "req-1", "a", 30_000L);
        registry.register("dev-1", "req-2", "b", 30_000L);
        registry.register("dev-2", "req-3", "c", 30_000L);

        List<PendingRequestRegistry.Completion> failed = registry.failAll("dev-1", ProtocolErrors.NOT_CONNECTED);

        assertEquals(2, failed.size());
        assertTrue(failed.stream().allMatch(completion -> ProtocolErrors.NOT_CONNECTED.equals(completion.error())));
        assertFalse(failed.stream().anyMatch(PendingRequestRegistry.Completion::isTimeout));
        assertEquals(0, registry.pendingCount("dev-1"));
        assertEquals(1, registry.pendingCount("dev-2"));
    }

    @Test
    @DisplayName("为不存在的设备清理返回空列表")
    void failAllOnUnknownDevice() {
        Testable registry = new Testable();
        assertTrue(registry.failAll("dev-x", ProtocolErrors.NOT_CONNECTED).isEmpty());
    }

    @Test
    @DisplayName("接管清理后迟到的结果不会再次完成")
    void lateResultAfterFailAll() {
        Testable registry = new Testable();
        registry.register("dev-1", "req-1", "a", 30_000L);
        registry.failAll("dev-1", ProtocolErrors.NOT_CONNECTED);

        assertTrue(registry.complete("dev-1", "req-1", true, null).isEmpty());
    }
}
