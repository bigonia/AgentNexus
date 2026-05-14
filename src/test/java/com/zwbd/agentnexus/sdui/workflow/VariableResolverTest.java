package com.zwbd.agentnexus.sdui.workflow;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class VariableResolverTest {

    @Test
    void resolveDataPath() {
        Map<String, Object> data = Map.of("unread_count", 5, "label", "CPU");
        Object result = VariableResolver.resolveExpression("$data.unread_count", data, Map.of(), Map.of());
        assertEquals(5, result);
    }

    @Test
    void resolveTriggerPath() {
        Map<String, Object> trigger = Map.of("from", "alice@example.com", "subject", "Hello");
        assertEquals("alice@example.com", VariableResolver.resolveExpression("$trigger.from", Map.of(), trigger, Map.of()));
    }

    @Test
    void resolveEnvVar() {
        Map<String, String> env = Map.of("MAIL_API", "https://api.example.com");
        assertEquals("https://api.example.com", VariableResolver.resolveExpression("$env.MAIL_API", Map.of(), Map.of(), env));
    }

    @Test
    void resolveStringLiteral() {
        assertEquals("未读邮件", VariableResolver.resolveExpression("'未读邮件'", Map.of(), Map.of(), Map.of()));
    }

    @Test
    void resolveNumberLiteral() {
        assertEquals(3000, VariableResolver.resolveExpression("3000", Map.of(), Map.of(), Map.of()));
    }

    @Test
    void resolveNestedPath() {
        Map<String, Object> data = Map.of("recent", List.of(
                Map.of("title", "Server OK", "subtitle", "2m ago"),
                Map.of("title", "CPU 92%", "subtitle", "5m ago")
        ));
        assertEquals("Server OK", VariableResolver.resolveExpression("$data.recent[0].title", data, Map.of(), Map.of()));
        assertEquals("CPU 92%", VariableResolver.resolveExpression("$data.recent[1].title", data, Map.of(), Map.of()));
    }

    @Test
    void resolveBindMap() {
        Map<String, String> bind = Map.of("value", "$data.count", "label", "'Status'", "progress", "$data.count");
        Map<String, Object> data = Map.of("count", 72);
        Map<String, Object> resolved = VariableResolver.resolve(bind, data, Map.of(), Map.of());
        assertEquals(72, resolved.get("value"));
        assertEquals("Status", resolved.get("label"));
        assertEquals(72, resolved.get("progress"));
    }

    @Test
    void missingVariableReturnsExpression() {
        Object result = VariableResolver.resolveExpression("$data.nonexistent", Map.of(), Map.of(), Map.of());
        assertNull(result);
    }

    @Test
    void nullExprReturnsNull() {
        assertNull(VariableResolver.resolveExpression(null, Map.of(), Map.of(), Map.of()));
    }

    @Test
    void plainTextReturnsAsIs() {
        assertEquals("plain text", VariableResolver.resolveExpression("plain text", Map.of(), Map.of(), Map.of()));
    }
}
