package com.zwbd.agentnexus.sdui.v2.capability;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.zwbd.agentnexus.sdui.v2.sim.SimulatedLcd085Device;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * capability_hash：稳定性、字段顺序无关性、变化敏感性。
 *
 * <p>这些性质是 04_PROTOCOL_MODEL.md §4 缓存机制成立的前提：hash 必须只由内容决定。</p>
 */
class CapabilityHashTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    @DisplayName("同一 Schema 多次计算结果相同，且为 16 位小写十六进制")
    void isStable() {
        String first = CapabilityHash.compute(SimulatedLcd085Device.SCHEMA, objectMapper);
        String second = CapabilityHash.compute(SimulatedLcd085Device.SCHEMA, objectMapper);

        assertEquals(first, second);
        assertEquals(CapabilityHash.HASH_LENGTH, first.length());
        assertTrue(first.matches("[0-9a-f]{16}"));
    }

    @Test
    @DisplayName("规范化时对象键排序，键顺序不同不改变结果")
    void canonicalFormSortsObjectKeys() throws Exception {
        JsonNode left = objectMapper.readTree("{\"b\":1,\"a\":2}");
        JsonNode right = objectMapper.readTree("{\"a\":2,\"b\":1}");

        assertEquals(CapabilityHash.canonicalize(left), CapabilityHash.canonicalize(right));
        assertEquals("{\"a\":2,\"b\":1}", CapabilityHash.canonicalize(left));
    }

    @Test
    @DisplayName("规范化时剔除 null 字段")
    void canonicalFormOmitsNullFields() {
        String canonical = CapabilityHash.canonicalForm(
                new CapabilitySchemaV2("2", "1", "B", null, null, null), objectMapper);

        assertFalse(canonical.contains("triggers"));
        assertFalse(canonical.contains("actions"));
        assertTrue(canonical.contains("\"board\":\"B\""));
    }

    @Test
    @DisplayName("任一动作的可用范围变化都会改变 hash")
    void reactsToConstraintChange() {
        String baseline = CapabilityHash.compute(SimulatedLcd085Device.SCHEMA, objectMapper);

        CapabilitySchemaV2 changed = new CapabilitySchemaV2(
                SimulatedLcd085Device.SCHEMA.protocolVersion(),
                SimulatedLcd085Device.SCHEMA.schemaVersion(),
                SimulatedLcd085Device.SCHEMA.board(),
                SimulatedLcd085Device.SCHEMA.triggers(),
                SimulatedLcd085Device.SCHEMA.actions().stream()
                        .map(action -> action.name().equals("prompt.play")
                                ? new CapabilitySchemaV2.ActionSpec(action.name(), action.params(),
                                List.of("binding", "request"))
                                : action)
                        .toList(),
                SimulatedLcd085Device.SCHEMA.surface());

        assertNotEquals(baseline, CapabilityHash.compute(changed, objectMapper));
    }

    @Test
    @DisplayName("数组顺序具有语义：参数顺序变化会改变 hash")
    void arrayOrderMatters() {
        CapabilitySchemaV2 left = new CapabilitySchemaV2("2", "1", "B", null,
                List.of(new CapabilitySchemaV2.ActionSpec("a", List.of(
                        new CapabilitySchemaV2.ParamSpec("x", "int", true, null, null, null),
                        new CapabilitySchemaV2.ParamSpec("y", "int", true, null, null, null)
                ), List.of("binding"))), null);
        CapabilitySchemaV2 right = new CapabilitySchemaV2("2", "1", "B", null,
                List.of(new CapabilitySchemaV2.ActionSpec("a", List.of(
                        new CapabilitySchemaV2.ParamSpec("y", "int", true, null, null, null),
                        new CapabilitySchemaV2.ParamSpec("x", "int", true, null, null, null)
                ), List.of("binding"))), null);

        assertNotEquals(CapabilityHash.compute(left, objectMapper), CapabilityHash.compute(right, objectMapper));
    }

    @Test
    @DisplayName("null 与空列表语义不同：显式空绑定表会改变 hash")
    void distinguishesNullFromEmptyList() {
        CapabilitySchemaV2 withNull = new CapabilitySchemaV2("2", "1", "B", null, null, null);
        CapabilitySchemaV2 withEmpty = new CapabilitySchemaV2("2", "1", "B", List.of(), null, null);

        assertNotEquals(CapabilityHash.compute(withNull, objectMapper),
                CapabilityHash.compute(withEmpty, objectMapper));
    }

    @Test
    @DisplayName("null Schema 返回 null 而不是抛异常")
    void handlesNullSchema() {
        assertEquals(null, CapabilityHash.compute(null, objectMapper));
    }
}
