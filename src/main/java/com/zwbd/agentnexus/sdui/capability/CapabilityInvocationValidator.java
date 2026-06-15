package com.zwbd.agentnexus.sdui.capability;

import com.zwbd.agentnexus.sdui.service.CommandSchemaRegistry;
import com.zwbd.agentnexus.sdui.service.SduiCapabilityService;
import org.springframework.stereotype.Service;

import java.util.*;

@Service
public class CapabilityInvocationValidator {

    private final CommandSchemaRegistry commandSchemaRegistry;
    private final SduiCapabilityService capabilityService;
    private final PlatformCapabilityRegistry platformCapabilityRegistry;

    public CapabilityInvocationValidator(CommandSchemaRegistry commandSchemaRegistry,
                                         SduiCapabilityService capabilityService,
                                         PlatformCapabilityRegistry platformCapabilityRegistry) {
        this.commandSchemaRegistry = commandSchemaRegistry;
        this.capabilityService = capabilityService;
        this.platformCapabilityRegistry = platformCapabilityRegistry;
    }

    public record ValidationResult(Map<String, Object> normalizedParams, List<String> errors) {
        public boolean valid() {
            return errors == null || errors.isEmpty();
        }
    }

    public ValidationResult validateDebugInvocation(String deviceId, String command, Map<String, Object> params) {
        Map<String, Object> safeParams = params != null ? new LinkedHashMap<>(params) : new LinkedHashMap<>();
        Optional<PlatformCapabilityRegistry.PlatformCapabilityDef> platformCapability =
                platformCapabilityRegistry.findByDebugRoute(command);
        if (platformCapability.isPresent()) {
            return validatePlatformCapability(platformCapability.get(), safeParams);
        }

        if (!capabilityService.getAvailableCommands(deviceId).contains(command)) {
            return invalid("Unsupported device command for " + deviceId + ": " + command);
        }

        Map<String, Object> effectiveParams = commandSchemaRegistry.applyDefaults(deviceId, command, safeParams);
        List<String> errors = new ArrayList<>(commandSchemaRegistry.validate(deviceId, command, effectiveParams));
        return new ValidationResult(effectiveParams, errors);
    }

    private ValidationResult validatePlatformCapability(PlatformCapabilityRegistry.PlatformCapabilityDef capability,
                                                        Map<String, Object> params) {
        if (!capability.available()) {
            return invalid("Platform capability is unavailable: " + capability.id());
        }

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> fields =
                (List<Map<String, Object>>) capability.schema().getOrDefault("params", List.of());
        Map<String, Object> normalized = applyFieldDefaults(fields, params);
        List<String> errors = validateFieldSpecs(fields, normalized);
        return new ValidationResult(normalized, errors);
    }

    private Map<String, Object> applyFieldDefaults(List<Map<String, Object>> fieldSpecs, Map<String, Object> params) {
        Map<String, Object> normalized = new LinkedHashMap<>(params);
        for (Map<String, Object> field : fieldSpecs) {
            String name = asString(field.get("name"));
            if (name == null || normalized.containsKey(name)) {
                continue;
            }
            if (field.containsKey("default")) {
                normalized.put(name, field.get("default"));
            }
        }
        return normalized;
    }

    private List<String> validateFieldSpecs(List<Map<String, Object>> fieldSpecs, Map<String, Object> params) {
        List<String> errors = new ArrayList<>();
        for (Map<String, Object> field : fieldSpecs) {
            String name = asString(field.get("name"));
            if (name == null || name.isBlank()) {
                continue;
            }
            Object value = params.get(name);
            boolean required = Boolean.TRUE.equals(field.get("required"));
            if (required && value == null && !params.containsKey(name)) {
                errors.add(name + ": required field missing");
                continue;
            }
            if (value == null) {
                continue;
            }
            validateValueType(name, asString(field.get("type")), value, errors);
            Object options = field.get("options");
            if (options instanceof List<?> list && !list.isEmpty() && !list.contains(String.valueOf(value))) {
                errors.add(name + ": expected one of " + list + ", got " + value);
            }
        }
        return errors;
    }

    private void validateValueType(String name, String type, Object value, List<String> errors) {
        String normalizedType = type != null ? type : "object";
        switch (normalizedType) {
            case "string" -> {
                if (!(value instanceof String)) {
                    errors.add(name + ": expected string, got " + value.getClass().getSimpleName());
                }
            }
            case "int", "number" -> {
                if (!(value instanceof Number)) {
                    errors.add(name + ": expected number, got " + value.getClass().getSimpleName());
                }
            }
            case "bool", "boolean" -> {
                if (!(value instanceof Boolean)) {
                    errors.add(name + ": expected boolean, got " + value.getClass().getSimpleName());
                }
            }
            case "map", "object" -> {
                if (!(value instanceof Map<?, ?>)) {
                    errors.add(name + ": expected object, got " + value.getClass().getSimpleName());
                }
            }
            case "actions[]" -> {
                if (!(value instanceof List<?>)) {
                    errors.add(name + ": expected array, got " + value.getClass().getSimpleName());
                }
            }
            default -> {
                // Keep validator extensible: unknown abstract types are tolerated here.
            }
        }
    }

    private ValidationResult invalid(String error) {
        return new ValidationResult(Map.of(), List.of(error));
    }

    private String asString(Object value) {
        return value instanceof String s ? s : null;
    }
}
