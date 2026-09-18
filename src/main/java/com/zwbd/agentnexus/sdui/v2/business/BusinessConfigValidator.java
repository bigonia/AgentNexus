package com.zwbd.agentnexus.sdui.v2.business;

import com.fasterxml.jackson.databind.JsonNode;
import com.zwbd.agentnexus.sdui.v2.V2ProtocolProperties;
import com.zwbd.agentnexus.sdui.v2.capability.CapabilitySchemaV2;
import com.zwbd.agentnexus.sdui.v2.protocol.V2Names;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 业务配置校验器。
 *
 * <p>04_PROTOCOL_MODEL.md §4 要求平台侧在执行动作前校验能力约束；01_INTERACTION_MODEL.md §6.2 要求
 * 终端"先完整解析和校验，再原子替换"。平台侧必须在**下发之前**做同等校验，否则终端会拒绝而配置
 * 已被平台记为生效，形成漂移。</p>
 *
 * <p>校验失败整体拒绝，不部分应用。</p>
 */
@Slf4j
@Component
public class BusinessConfigValidator {

    private final V2ProtocolProperties properties;

    public BusinessConfigValidator(V2ProtocolProperties properties) {
        this.properties = properties;
    }

    /**
     * 完整校验。
     *
     * @param schema 设备当前生效的能力 Schema；为 null 时只做结构校验（设备能力尚未同步）
     */
    public ValidationResult validate(CapabilitySchemaV2 schema, BusinessConfig config) {
        List<String> errors = new ArrayList<>();
        if (config == null) {
            return ValidationResult.failed(List.of("config 为空"));
        }

        List<TriggerBinding> triggers = config.triggers();
        if (triggers.size() > properties.getMaxBindingsPerConfig()) {
            errors.add("绑定数量 " + triggers.size() + " 超过上限 " + properties.getMaxBindingsPerConfig());
        }

        Set<String> seenTriggerIds = new HashSet<>();
        Set<String> seenTokens = new HashSet<>();

        for (int i = 0; i < triggers.size(); i++) {
            TriggerBinding binding = triggers.get(i);
            String path = "triggers[" + i + "]";

            if (binding.triggerId() == null || binding.triggerId().isBlank()) {
                errors.add(path + ".triggerId 不能为空");
                continue;
            }
            if (!seenTriggerIds.add(binding.triggerId())) {
                errors.add(path + ".triggerId 重复: " + binding.triggerId());
            }

            TriggerSource source = binding.source() != null
                    ? binding.source()
                    : TriggerSource.infer(binding.triggerId());
            if (source == null) {
                errors.add(path + ".source 无法识别，且 triggerId 前缀不匹配任何已知来源: " + binding.triggerId());
            } else {
                if (source == TriggerSource.PLATFORM) {
                    String token = binding.token();
                    if (token == null || token.isBlank()) {
                        errors.add(path + ".token 云端 Trigger 必须携带 token");
                    } else {
                        if (token.length() > properties.getMaxTokenLength()) {
                            errors.add(path + ".token 长度 " + token.length()
                                    + " 超过上限 " + properties.getMaxTokenLength());
                        }
                        if (!seenTokens.add(token)) {
                            errors.add(path + ".token 在本配置内重复: " + token);
                        }
                    }
                } else if (binding.token() != null && !binding.token().isBlank()) {
                    errors.add(path + ".token 物理 Trigger 不应携带 token");
                }
            }

            validateResponses(schema, binding, path, errors);
        }

        if (errors.isEmpty()) {
            return ValidationResult.passed();
        }
        log.warn("业务配置校验失败: device={}, errors={}", config.deviceId(), errors);
        return ValidationResult.failed(errors);
    }

    private void validateResponses(CapabilitySchemaV2 schema, TriggerBinding binding, String path, List<String> errors) {
        List<ResponseStep> responses = binding.responses();
        if (responses.isEmpty()) {
            errors.add(path + ".responses 不能为空");
            return;
        }
        if (responses.size() > properties.getMaxResponsesPerBinding()) {
            errors.add(path + ".responses 数量 " + responses.size()
                    + " 超过上限 " + properties.getMaxResponsesPerBinding());
        }

        int maxResponses = resolveMaxResponses(schema, binding);
        if (maxResponses > 0 && responses.size() > maxResponses) {
            errors.add(path + ".responses 数量 " + responses.size()
                    + " 超过终端为该 Trigger 声明的上限 " + maxResponses);
        }

        if (schema == null) {
            return;
        }

        CapabilitySchemaV2.TriggerSpec triggerSpec = schema.trigger(binding.triggerId());
        if (triggerSpec == null) {
            errors.add(path + ".triggerId 不在设备能力 Schema 中: " + binding.triggerId());
        } else if (!triggerSpec.configurable()) {
            errors.add(path + ".triggerId 该 Trigger 不允许配置: " + binding.triggerId());
        }

        for (int i = 0; i < responses.size(); i++) {
            validateStep(schema, responses.get(i), path + ".responses[" + i + "]", errors);
        }
    }

    private void validateStep(CapabilitySchemaV2 schema, ResponseStep step, String path, List<String> errors) {
        String action = step.action();
        if (action == null || action.isBlank()) {
            errors.add(path + ".action 不能为空");
            return;
        }
        // platform.interaction.report 是平台定义的专用 Response，终端能力 Schema 中未必声明为可绑定动作
        if (V2Names.ACTION_PLATFORM_REPORT.equals(action)) {
            Object token = step.params().get("token");
            if (token == null || String.valueOf(token).isBlank()) {
                errors.add(path + " platform.interaction.report 必须携带 token");
            } else if (String.valueOf(token).length() > properties.getMaxTokenLength()) {
                errors.add(path + " token 长度超过上限 " + properties.getMaxTokenLength());
            }
            return;
        }

        CapabilitySchemaV2.ActionSpec actionSpec = schema.action(action);
        if (actionSpec == null) {
            errors.add(path + ".action 不在设备能力 Schema 中: " + action);
            return;
        }
        if (!actionSpec.usableInBinding()) {
            errors.add(path + ".action 不允许用于本地绑定: " + action);
        }
        validateParams(actionSpec, step.params(), path, errors);
    }

    private void validateParams(CapabilitySchemaV2.ActionSpec actionSpec, Map<String, Object> params,
                                String path, List<String> errors) {
        if (actionSpec.params() == null) {
            return;
        }
        for (CapabilitySchemaV2.ParamSpec spec : actionSpec.params()) {
            Object value = params.get(spec.name());
            if (value == null || (value instanceof String s && s.isBlank())) {
                if (spec.required()) {
                    errors.add(path + " 缺少必填参数: " + spec.name());
                }
                continue;
            }
            switch (spec.type()) {
                case "int" -> {
                    Integer intValue = asInt(value);
                    if (intValue == null) {
                        errors.add(path + " 参数 " + spec.name() + " 不是整数: " + value);
                    } else {
                        if (spec.min() != null && intValue < spec.min()) {
                            errors.add(path + " 参数 " + spec.name() + " 小于下限 " + spec.min());
                        }
                        if (spec.max() != null && intValue > spec.max()) {
                            errors.add(path + " 参数 " + spec.name() + " 大于上限 " + spec.max());
                        }
                    }
                }
                case "boolean" -> {
                    if (!(value instanceof Boolean) && !"true".equalsIgnoreCase(String.valueOf(value))
                            && !"false".equalsIgnoreCase(String.valueOf(value))) {
                        errors.add(path + " 参数 " + spec.name() + " 不是布尔值: " + value);
                    }
                }
                case "enum" -> {
                    if (spec.values() != null && !spec.values().contains(String.valueOf(value))) {
                        errors.add(path + " 参数 " + spec.name() + " 不在取值域内: " + value);
                    }
                }
                case "string" -> {
                    if (!(value instanceof String)) {
                        errors.add(path + " 参数 " + spec.name() + " 不是字符串: " + value);
                    }
                }
                case "object" -> {
                    if (!(value instanceof Map) && !(value instanceof JsonNode)) {
                        errors.add(path + " 参数 " + spec.name() + " 不是对象: " + value);
                    }
                }
                case "array" -> {
                    if (!(value instanceof List) && !(value instanceof JsonNode)) {
                        errors.add(path + " 参数 " + spec.name() + " 不是数组: " + value);
                    }
                }
                default -> {
                    // 未知类型不在平台侧强校验范围，交由终端判定
                }
            }
        }
    }

    private int resolveMaxResponses(CapabilitySchemaV2 schema, TriggerBinding binding) {
        if (schema == null) {
            return 0;
        }
        CapabilitySchemaV2.TriggerSpec triggerSpec = schema.trigger(binding.triggerId());
        if (triggerSpec == null || triggerSpec.maxResponses() == null) {
            return 0;
        }
        return triggerSpec.maxResponses();
    }

    private static Integer asInt(Object value) {
        if (value instanceof Number number) {
            return number.intValue();
        }
        String text = String.valueOf(value).trim();
        try {
            return Integer.valueOf(text);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** 校验结果。失败时携带全部错误，便于一次性修正。 */
    public record ValidationResult(boolean ok, List<String> errors) {

        public static ValidationResult passed() {
            return new ValidationResult(true, List.of());
        }

        public static ValidationResult failed(List<String> errors) {
            return new ValidationResult(false, List.copyOf(errors));
        }

        public String errorSummary() {
            return String.join("; ", errors);
        }
    }
}
