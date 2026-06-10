package com.zwbd.agentnexus.sdui.workflow.node;

import com.zwbd.agentnexus.sdui.protocol.catalog.CommandSpec;
import com.zwbd.agentnexus.sdui.protocol.catalog.DeviceCapabilityProjection;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

@Slf4j
@Component
public class CapabilityNodeRegistry {

    private final Map<String, CapabilityNode> nodeMap = new ConcurrentHashMap<>();
    private final Map<String, Map<String, CapabilityNode>> deviceNodes = new ConcurrentHashMap<>();
    private final DeviceCapabilityProjection capabilityProjection;

    public CapabilityNodeRegistry(List<CapabilityNode> allNodes,
                                  DeviceCapabilityProjection capabilityProjection) {
        this.capabilityProjection = capabilityProjection;
        for (CapabilityNode node : allNodes) {
            String type = node.type();
            if (nodeMap.containsKey(type)) {
                log.warn("Duplicate CapabilityNode type '{}' — overwriting with {}", type,
                        node.getClass().getSimpleName());
            }
            nodeMap.put(type, node);
            log.info("Registered CapabilityNode: {} ({})", type, node.getClass().getSimpleName());
        }
        log.info("CapabilityNodeRegistry initialized with {} node types", nodeMap.size());
    }

    /** Get all node schemas available globally (platform + flow control nodes). */
    public List<NodeSchema> getGlobalSchemas() {
        return nodeMap.values().stream()
                .map(CapabilityNode::schema)
                .filter(schema -> "platform".equals(schema.category()) || "flow_control".equals(schema.category()))
                .map(schema -> withDeviceSupport(schema, null))
                .sorted(Comparator.comparing(NodeSchema::category).thenComparing(NodeSchema::displayName))
                .collect(Collectors.toList());
    }

    /** Get all schemas available for a specific device (global + device-specific + enriched). */
    public List<NodeSchema> getDeviceSchemas(String deviceId) {
        List<NodeSchema> schemas = new ArrayList<>();
        schemas.addAll(getGlobalSchemas());

        // Add per-device registered nodes (from capability snapshot)
        Map<String, CapabilityNode> devNodes = deviceNodes.get(deviceId);
        Set<String> includedTypes = schemas.stream()
                .map(NodeSchema::type).collect(Collectors.toSet());

        if (devNodes != null) {
            devNodes.values().stream()
                    .map(CapabilityNode::schema)
                    .map(schema -> withDeviceSupport(schema, Boolean.TRUE))
                    .peek(s -> includedTypes.add(s.type()))
                    .forEach(schemas::add);
        }

        // Fallback: include device-category nodes from global registry
        // so the editor always shows them for composition (capability check is at bind time)
        nodeMap.values().stream()
                .map(CapabilityNode::schema)
                .filter(schema -> "device".equals(schema.category()))
                .filter(schema -> !includedTypes.contains(schema.type()))
                .map(schema -> withDeviceSupport(schema, null))
                .forEach(schemas::add);

        // Enrich device.control with actual device commands (same source as debug commands API)
        schemas = schemas.stream()
                .map(schema -> enrichWithDeviceCommands(schema, deviceId))
                .sorted(Comparator.comparing(NodeSchema::category).thenComparing(NodeSchema::displayName))
                .collect(Collectors.toList());

        return schemas;
    }

    /**
     * Inject per-device command metadata into the device.control node's
     * command input constraints, so the frontend editor can render a command
     * picker with typed parameter schemas — the same data that powers
     * GET /debug/{deviceId}/commands.
     */
    private NodeSchema enrichWithDeviceCommands(NodeSchema schema, String deviceId) {
        if (!"device.control".equals(schema.type())) {
            return schema;
        }

        List<CommandSpec> commands = capabilityProjection.commands(deviceId);
        List<Map<String, Object>> commandOptions = commands.stream()
                .map(cmd -> {
                    Map<String, Object> option = new LinkedHashMap<>();
                    option.put("value", cmd.id());
                    option.put("params", cmd.params().stream()
                            .map(f -> {
                                Map<String, Object> field = new LinkedHashMap<>();
                                field.put("name", f.name());
                                field.put("type", f.type());
                                return field;
                            })
                            .toList());
                    return option;
                })
                .toList();

        List<NodeSchema.ParamDef> enrichedInputs = schema.inputs().stream()
                .map(input -> {
                    if ("command".equals(input.name())) {
                        Map<String, Object> constraints = new LinkedHashMap<>(input.constraints());
                        constraints.put("options", commandOptions);
                        return new NodeSchema.ParamDef(
                                input.name(), input.type(), input.required(),
                                input.defaultValue(), input.description(), constraints);
                    }
                    return input;
                })
                .toList();

        return new NodeSchema(
                schema.type(), schema.displayName(), schema.description(),
                schema.category(), schema.icon(), enrichedInputs, schema.outputs(),
                schema.suspendable(), schema.timeoutMs(), schema.deviceSupported(),
                schema.source(), schema.protocol(), schema.runtimeHandler(),
                schema.constraints());
    }

    /** Resolve a node type to an executable instance for a specific device. */
    public CapabilityNode resolve(String deviceId, String nodeType) {
        Map<String, CapabilityNode> devNodes = deviceNodes.get(deviceId);
        if (devNodes != null) {
            CapabilityNode devNode = devNodes.get(nodeType);
            if (devNode != null) return devNode;
        }
        CapabilityNode node = nodeMap.get(nodeType);
        if (node != null) return node;

        // fallback: check if it's a device.* node that hasn't been registered per-device
        if (nodeType.startsWith("device.")) {
            log.warn("Device node type '{}' not instantiated for device {} — using generic fallback",
                    nodeType, deviceId);
            return nodeMap.get(nodeType);
        }
        return null;
    }

    /** Register per-device nodes derived from a device's capability snapshot. */
    public void registerDeviceNodes(String deviceId, Map<String, Object> capabilitySnapshot) {
        Map<String, CapabilityNode> nodes = new ConcurrentHashMap<>();
        for (CapabilityNode node : nodeMap.values()) {
            if ("device".equals(node.schema().category()) && isSupportedByDevice(node.type(), capabilitySnapshot)) {
                nodes.put(node.type(), node);
            }
        }
        deviceNodes.put(deviceId, nodes);
        log.info("Registered {} device nodes for device {}", nodes.size(), deviceId);
    }

    private NodeSchema withDeviceSupport(NodeSchema schema, Boolean deviceSupported) {
        return new NodeSchema(
                schema.type(),
                schema.displayName(),
                schema.description(),
                schema.category(),
                schema.icon(),
                schema.inputs(),
                schema.outputs(),
                schema.suspendable(),
                schema.timeoutMs(),
                deviceSupported,
                schema.source(),
                schema.protocol(),
                schema.runtimeHandler(),
                schema.constraints()
        );
    }

    @SuppressWarnings("unchecked")
    private boolean isSupportedByDevice(String nodeType, Map<String, Object> capabilitySnapshot) {
        List<String> outputs = capabilitySnapshot.get("outputs") instanceof List<?> list
                ? (List<String>) list : List.of();
        boolean hasDisplay = capabilitySnapshot.get("display") instanceof Map<?, ?>;

        return switch (nodeType) {
            case "device.control" -> !outputs.isEmpty();
            case "device.page.render", "device.page.switch", "device.section.patch" -> hasDisplay;
            default -> true;
        };
    }

    public void unregisterDeviceNodes(String deviceId) {
        deviceNodes.remove(deviceId);
        log.info("Unregistered device nodes for device {}", deviceId);
    }

    /** How many registered node types total (global + per-device). */
    public int globalNodeCount() {
        return nodeMap.size();
    }
}
