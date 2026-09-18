package com.zwbd.agentnexus.sdui.v2.business;

import com.zwbd.agentnexus.sdui.v2.V2ProtocolProperties;
import com.zwbd.agentnexus.sdui.v2.protocol.V2Names;
import com.zwbd.agentnexus.sdui.v2.sim.SimulatedLcd085Device;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 业务配置校验器：结构规则与能力约束。
 */
class BusinessConfigValidatorTest {

    private final V2ProtocolProperties properties = new V2ProtocolProperties();
    private final BusinessConfigValidator validator = new BusinessConfigValidator(properties);

    private static ResponseStep report(String token) {
        return ResponseStep.of(V2Names.ACTION_PLATFORM_REPORT, Map.of("token", token));
    }

    @Test
    @DisplayName("合法配置通过校验")
    void acceptsValidConfig() {
        BusinessConfig config = new BusinessConfig("dev-1", 1, List.of(
                new TriggerBinding("button.ok", TriggerSource.PHYSICAL, null, List.of(
                        ResponseStep.of("prompt.play", Map.of("preset", "start")),
                        ResponseStep.of("audio.record.start"))),
                new TriggerBinding("platform.trigger", TriggerSource.PLATFORM, "pt_abcdefghijklmnopqrstuvwx", List.of(
                        ResponseStep.of("audio.record.stop"),
                        report("rt_abcdefghijklmnopqrstuvwx")))
        ));

        BusinessConfigValidator.ValidationResult result =
                validator.validate(SimulatedLcd085Device.SCHEMA, config);

        assertTrue(result.ok(), result.errorSummary());
    }

    @Test
    @DisplayName("云端 Trigger 缺少 token 被拒绝")
    void rejectsPlatformTriggerWithoutToken() {
        BusinessConfig config = new BusinessConfig("dev-1", 1, List.of(
                new TriggerBinding("platform.trigger", TriggerSource.PLATFORM, null,
                        List.of(ResponseStep.of("audio.record.start")))));

        BusinessConfigValidator.ValidationResult result =
                validator.validate(SimulatedLcd085Device.SCHEMA, config);

        assertFalse(result.ok());
        assertTrue(result.errorSummary().contains("必须携带 token"), result.errorSummary());
    }

    @Test
    @DisplayName("物理 Trigger 携带 token 被拒绝")
    void rejectsPhysicalTriggerWithToken() {
        BusinessConfig config = new BusinessConfig("dev-1", 1, List.of(
                new TriggerBinding("button.ok", TriggerSource.PHYSICAL, "pt_whatever",
                        List.of(ResponseStep.of("audio.record.start")))));

        assertFalse(validator.validate(SimulatedLcd085Device.SCHEMA, config).ok());
    }

    @Test
    @DisplayName("token 超过长度上限被拒绝")
    void rejectsOversizedToken() {
        String tooLong = "pt_" + "a".repeat(properties.getMaxTokenLength());
        BusinessConfig config = new BusinessConfig("dev-1", 1, List.of(
                new TriggerBinding("platform.trigger", TriggerSource.PLATFORM, tooLong,
                        List.of(ResponseStep.of("audio.record.start")))));

        BusinessConfigValidator.ValidationResult result =
                validator.validate(SimulatedLcd085Device.SCHEMA, config);

        assertFalse(result.ok());
        assertTrue(result.errorSummary().contains("长度"), result.errorSummary());
    }

    @Test
    @DisplayName("未知 Trigger 被拒绝")
    void rejectsUnknownTrigger() {
        BusinessConfig config = new BusinessConfig("dev-1", 1, List.of(
                new TriggerBinding("button.long_press", TriggerSource.PHYSICAL, null,
                        List.of(ResponseStep.of("audio.record.start")))));

        BusinessConfigValidator.ValidationResult result =
                validator.validate(SimulatedLcd085Device.SCHEMA, config);

        assertFalse(result.ok());
        assertTrue(result.errorSummary().contains("不在设备能力 Schema 中"), result.errorSummary());
    }

    @Test
    @DisplayName("未知动作或不可用于绑定的动作被拒绝")
    void rejectsInvalidActions() {
        BusinessConfig unknownAction = new BusinessConfig("dev-1", 1, List.of(
                new TriggerBinding("button.ok", TriggerSource.PHYSICAL, null,
                        List.of(ResponseStep.of("audio.something.else")))));
        assertFalse(validator.validate(SimulatedLcd085Device.SCHEMA, unknownAction).ok());

        BusinessConfig requestOnlyAction = new BusinessConfig("dev-1", 1, List.of(
                new TriggerBinding("button.ok", TriggerSource.PHYSICAL, null,
                        List.of(ResponseStep.of("system.reboot")))));
        assertFalse(validator.validate(SimulatedLcd085Device.SCHEMA, requestOnlyAction).ok());
    }

    @Test
    @DisplayName("参数必填、范围与取值域均被校验")
    void validatesParams() {
        BusinessConfig missingRequired = new BusinessConfig("dev-1", 1, List.of(
                new TriggerBinding("button.ok", TriggerSource.PHYSICAL, null,
                        List.of(ResponseStep.of("rgb.effect.set", Map.of("r", 10, "g", 20))))));
        assertTrue(validator.validate(SimulatedLcd085Device.SCHEMA, missingRequired)
                .errorSummary().contains("缺少必填参数"));

        BusinessConfig outOfRange = new BusinessConfig("dev-1", 1, List.of(
                new TriggerBinding("button.ok", TriggerSource.PHYSICAL, null,
                        List.of(ResponseStep.of("rgb.effect.set", Map.of("r", 999, "g", 20, "b", 30))))));
        assertTrue(validator.validate(SimulatedLcd085Device.SCHEMA, outOfRange)
                .errorSummary().contains("大于上限"));

        BusinessConfig badEnum = new BusinessConfig("dev-1", 1, List.of(
                new TriggerBinding("button.ok", TriggerSource.PHYSICAL, null,
                        List.of(ResponseStep.of("prompt.play", Map.of("preset", "nope"))))));
        assertTrue(validator.validate(SimulatedLcd085Device.SCHEMA, badEnum)
                .errorSummary().contains("不在取值域内"));
    }

    @Test
    @DisplayName("响应序列为空或超过上限被拒绝")
    void validatesResponseSequenceSize() {
        BusinessConfig empty = new BusinessConfig("dev-1", 1, List.of(
                new TriggerBinding("button.ok", TriggerSource.PHYSICAL, null, List.of())));
        assertFalse(validator.validate(SimulatedLcd085Device.SCHEMA, empty).ok());

        List<ResponseStep> tooMany = java.util.stream.IntStream
                .range(0, properties.getMaxResponsesPerBinding() + 1)
                .mapToObj(i -> ResponseStep.of("audio.record.start"))
                .toList();
        BusinessConfig oversized = new BusinessConfig("dev-1", 1, List.of(
                new TriggerBinding("button.ok", TriggerSource.PHYSICAL, null, tooMany)));
        assertFalse(validator.validate(SimulatedLcd085Device.SCHEMA, oversized).ok());
    }

    @Test
    @DisplayName("platform.interaction.report 是平台专用 Response，不需要出现在能力 Schema 中")
    void acceptsPlatformReportWithoutSchemaDeclaration() {
        BusinessConfig config = new BusinessConfig("dev-1", 1, List.of(
                new TriggerBinding("button.ok", TriggerSource.PHYSICAL, null, List.of(report("rt_token")))));

        assertTrue(validator.validate(SimulatedLcd085Device.SCHEMA, config).ok());
    }

    @Test
    @DisplayName("platform.interaction.report 缺少 token 被拒绝")
    void rejectsPlatformReportWithoutToken() {
        BusinessConfig config = new BusinessConfig("dev-1", 1, List.of(
                new TriggerBinding("button.ok", TriggerSource.PHYSICAL, null,
                        List.of(ResponseStep.of(V2Names.ACTION_PLATFORM_REPORT)))));

        assertFalse(validator.validate(SimulatedLcd085Device.SCHEMA, config).ok());
    }

    @Test
    @DisplayName("重复 triggerId 与重复 token 都被拒绝")
    void rejectsDuplicates() {
        BusinessConfig duplicateTrigger = new BusinessConfig("dev-1", 1, List.of(
                new TriggerBinding("button.ok", TriggerSource.PHYSICAL, null,
                        List.of(ResponseStep.of("audio.record.start"))),
                new TriggerBinding("button.ok", TriggerSource.PHYSICAL, null,
                        List.of(ResponseStep.of("audio.record.stop")))));
        assertTrue(validator.validate(SimulatedLcd085Device.SCHEMA, duplicateTrigger)
                .errorSummary().contains("重复"));

        String shared = "pt_sharedtokenvalue0000";
        BusinessConfig duplicateToken = new BusinessConfig("dev-1", 1, List.of(
                new TriggerBinding("platform.trigger.a", TriggerSource.PLATFORM, shared,
                        List.of(ResponseStep.of("audio.record.start"))),
                new TriggerBinding("platform.trigger.b", TriggerSource.PLATFORM, shared,
                        List.of(ResponseStep.of("audio.record.start")))));
        // 用 null schema 隔离 token 规则本身
        BusinessConfigValidator.ValidationResult result = validator.validate(null, duplicateToken);
        assertFalse(result.ok());
        assertTrue(result.errorSummary().contains("token 在本配置内重复"), result.errorSummary());
    }

    @Test
    @DisplayName("绑定数量超过上限被拒绝")
    void rejectsTooManyBindings() {
        List<TriggerBinding> bindings = java.util.stream.IntStream
                .range(0, properties.getMaxBindingsPerConfig() + 1)
                .mapToObj(i -> new TriggerBinding("button.ok" + i, TriggerSource.PHYSICAL, null,
                        List.of(ResponseStep.of("audio.record.start"))))
                .toList();

        BusinessConfig config = new BusinessConfig("dev-1", 1, bindings);
        // 用 null schema 隔离数量规则本身，避免被未知 Trigger 规则掩盖
        BusinessConfigValidator.ValidationResult result = validator.validate(null, config);
        assertFalse(result.ok());
        assertTrue(result.errorSummary().contains("绑定数量"), result.errorSummary());
    }

    @Test
    @DisplayName("能力未同步（schema 为 null）时只做结构校验")
    void skipsCapabilityChecksWithoutSchema() {
        BusinessConfig config = new BusinessConfig("dev-1", 1, List.of(
                new TriggerBinding("button.ok", TriggerSource.PHYSICAL, null,
                        List.of(ResponseStep.of("unknown.action")))));

        assertTrue(validator.validate(null, config).ok());
    }
}
