package com.zwbd.agentnexus.sdui.workflow.node;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Discovers all CapabilityNode @Component beans via Spring constructor injection,
 * and maintains per-device node instances derived from device capability snapshots.
 */
@Slf4j
@Component
public class CapabilityNodeRegistry {

    private final Map<String, CapabilityNode> nodeMap = new ConcurrentHashMap<>();
    private final Map<String, Map<String, CapabilityNode>> deviceNodes = new ConcurrentHashMap<>();

    public CapabilityNodeRegistry(List<CapabilityNode> allNodes) {
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
        List<NodeSchema> schemas = new ArrayList<>();
        for (CapabilityNode node : nodeMap.values()) {
            String cat = node.schema().category();
            if ("platform".equals(cat) || "flow_control".equals(cat)) {
                schemas.add(node.schema());
            }
        }
        return schemas;
    }

    /** Get all schemas available for a specific device (global + device-specific). */
    public List<NodeSchema> getDeviceSchemas(String deviceId) {
        List<NodeSchema> schemas = new ArrayList<>(getGlobalSchemas());
        Map<String, CapabilityNode> devNodes = deviceNodes.get(deviceId);
        if (devNodes != null) {
            for (CapabilityNode node : devNodes.values()) {
                schemas.add(node.schema());
            }
        }
        return schemas;
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
        // Generic device nodes (shared across all devices) become available for this device
        for (CapabilityNode node : nodeMap.values()) {
            if ("device".equals(node.schema().category())) {
                nodes.put(node.type(), node);
            }
        }
        deviceNodes.put(deviceId, nodes);
        log.info("Registered {} device nodes for device {}", nodes.size(), deviceId);
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
