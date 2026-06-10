package com.zwbd.agentnexus.sdui.capability;

import com.zwbd.agentnexus.sdui.service.CommandSchemaRegistry;
import com.zwbd.agentnexus.sdui.service.SduiCapabilityService;
import com.zwbd.agentnexus.sdui.workflow.node.CapabilityNodeRegistry;
import com.zwbd.agentnexus.sdui.workflow.node.NodeSchema;
import org.springframework.stereotype.Service;

import java.util.*;

@Service
public class CapabilityInvocationValidator {

    private final CommandSchemaRegistry commandSchemaRegistry;
    private final SduiCapabilityService capabilityService;
    private final CapabilityContractService contractService;
    private final PlatformCapabilityRegistry platformCapabilityRegistry;
    private final CapabilityNodeRegistry nodeRegistry;

    public CapabilityInvocationValidator(CommandSchemaRegistry commandSchemaRegistry,
                                         SduiCapabilityService capabilityService,
                                         CapabilityContractService contractService,
                                         PlatformCapabilityRegistry platformCapabilityRegistry,
                                         CapabilityNodeRegistry nodeRegistry) {
        this.commandSchemaRegistry = commandSchemaRegistry;
        this.capabilityService = capabilityService;
        this.contractService = contractService;
        this.platformCapabilityRegistry = platformCapabilityRegistry;
        this.nodeRegistry = nodeRegistry;
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

    public ValidationResult validateNodeAction(String deviceId, String nodeType, Map<String, Object> params) {
        NodeSchema schema = resolveSchema(deviceId, nodeType);
        if (schema == null) {
            return invalid("Unknown node type: " + nodeType);
        }

        List<String> errors = new ArrayList<>();
        if (schema.runtimeHandler() == null || schema.runtimeHandler().isBlank()) {
            errors.add("Node '" + nodeType + "' is missing runtimeHandler");
        }

        Map<String, Object> safeParams = params != null ? new LinkedHashMap<>(params) : new LinkedHashMap<>();
        errors.addAll(validateNodeParams(schema, safeParams));

        if (deviceId != null && !deviceId.isBlank()) {
            validateNodeSupport(deviceId, schema, safeParams, errors);
        }

        return new ValidationResult(safeParams, errors);
    }

    private void validateNodeSupport(String deviceId,
                                     NodeSchema schema,
                                     Map<String, Object> params,
                                     List<String> errors) {
        CapabilityContract contract = contractService.buildContract(deviceId);
        switch (schema.source()) {
            case "device" -> validateDeviceNodeSupport(deviceId, schema, params, errors, contract);
            case "platform" -> validatePlatformNodeSupport(schema, errors, contract);
            default -> {
                // workflow-local nodes have no external capability dependency
            }
        }
    }

    private void validateDeviceNodeSupport(String deviceId,
                                           NodeSchema schema,
                                           Map<String, Object> params,
                                           List<String> errors,
                                           CapabilityContract contract) {
        boolean supported = switch (schema.type()) {
            case "device.control" -> !contract.outputs().isEmpty();
            case "device.page.render", "device.page.switch", "device.section.patch" -> contract.display().supported();
            default -> nodeRegistry.getDeviceSchemas(deviceId).stream()
                    .anyMatch(node -> schema.type().equals(node.type()) && Boolean.TRUE.equals(node.deviceSupported()));
        };

        if (!supported) {
            errors.add("Node '" + schema.type() + "' is not supported by device " + deviceId);
            return;
        }

        if ("device.control".equals(schema.type())) {
            String command = asString(params.get("command"));
            if (command == null || command.isBlank()) {
                errors.add("device.control requires a non-empty command param");
                return;
            }
            if (!capabilityService.getAvailableCommands(deviceId).contains(command)) {
                errors.add("Unsupported device command for " + deviceId + ": " + command);
                return;
            }
            @SuppressWarnings("unchecked")
            Map<String, Object> rawCommandParams = params.get("params") instanceof Map<?, ?> map
                    ? (Map<String, Object>) map : Map.of();
            Map<String, Object> effectiveParams = commandSchemaRegistry.applyDefaults(deviceId, command, rawCommandParams);
            errors.addAll(commandSchemaRegistry.validate(deviceId, command, effectiveParams));
            params.put("params", effectiveParams);
        }

        if ("device.section.patch".equals(schema.type())) {
            String operation = asString(params.get("operation"));
            String sectionType = asString(params.get("sectionType"));
            if ("add".equals(operation) && (sectionType == null || sectionType.isBlank())) {
                errors.add("device.section.patch requires sectionType");
                return;
            }
            if (sectionType == null || sectionType.isBlank()) {
                return;
            }
            boolean knownSection = contract.display().sectionTypes().stream()
                    .anyMatch(cap -> cap.supported() && sectionType.equals(cap.id()));
            if (!knownSection) {
                errors.add("Unsupported sectionType for " + deviceId + ": " + sectionType);
            }
        }
    }

    private void validatePlatformNodeSupport(NodeSchema schema,
                                             List<String> errors,
                                             CapabilityContract contract) {
        if (schema.runtimeHandler() == null || schema.runtimeHandler().isBlank()) {
            errors.add("Platform node '" + schema.type() + "' is missing runtimeHandler");
            return;
        }
        boolean available = contract.platformCapabilities().stream()
                .anyMatch(cap -> cap.supported() && schema.runtimeHandler().equals(cap.runtimeHandler()));
        if (!available && hasManagedPlatformRuntime(schema.runtimeHandler())) {
            errors.add("Platform runtime is unavailable for node '" + schema.type() + "': " + schema.runtimeHandler());
        }
    }

    private boolean hasManagedPlatformRuntime(String runtimeHandler) {
        return platformCapabilityRegistry.listCapabilities().stream()
                .anyMatch(cap -> runtimeHandler.equals(cap.runtimeHandler()));
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

    private List<String> validateNodeParams(NodeSchema schema, Map<String, Object> params) {
        List<String> errors = new ArrayList<>();
        for (NodeSchema.ParamDef param : schema.inputs()) {
            Object value = params.get(param.name());
            if (value == null && param.defaultValue() != null && !params.containsKey(param.name())) {
                params.put(param.name(), param.defaultValue());
                value = param.defaultValue();
            }
            if (param.required() && value == null && !params.containsKey(param.name())) {
                errors.add("Missing required param '" + param.name() + "' for node '" + schema.type() + "'");
                continue;
            }
            if (value == null) {
                continue;
            }
            validateValueType(param.name(), param.type(), value, errors);
        }
        return errors;
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

    private NodeSchema resolveSchema(String deviceId, String nodeType) {
        if (deviceId != null && !deviceId.isBlank()) {
            return nodeRegistry.getDeviceSchemas(deviceId).stream()
                    .filter(schema -> nodeType.equals(schema.type()))
                    .findFirst()
                    .orElseGet(() -> {
                        var node = nodeRegistry.resolve(deviceId, nodeType);
                        return node != null ? node.schema() : null;
                    });
        }
        var node = nodeRegistry.resolve("", nodeType);
        return node != null ? node.schema() : null;
    }

    private ValidationResult invalid(String error) {
        return new ValidationResult(Map.of(), List.of(error));
    }

    private String asString(Object value) {
        return value instanceof String s ? s : null;
    }
}
