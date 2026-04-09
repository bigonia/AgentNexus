package com.zwbd.agentnexus.ai.tools;

import com.zwbd.agentnexus.ai.dto.ToolInfo;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.mcp.SyncMcpToolCallbackProvider;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * @Author: wnli
 * @Date: 2026/1/6 17:01
 * @Desc: Global tool manager with provider-level namespace.
 */
@Slf4j
@Service
public class GlobalToolManager {

    public static final String PROVIDER_TYPE_LOCAL = "LOCAL";
    public static final String PROVIDER_TYPE_MCP = "MCP";
    public static final String LOCAL_PROVIDER = "local";
    public static final String BOOTSTRAP_MCP_PROVIDER = "bootstrap-mcp";

    // qualifiedToolId -> entry
    private final Map<String, ToolEntry> toolRegistry = new ConcurrentHashMap<>();
    // rawName -> qualified ids
    private final Map<String, Set<String>> nameIndex = new ConcurrentHashMap<>();
    // providerId -> qualified ids
    private final Map<String, Set<String>> providerIndex = new ConcurrentHashMap<>();

    @Autowired(required = false)
    public void setSyncMcpToolCallbackProvider(SyncMcpToolCallbackProvider toolCallbackProvider) {
        if (toolCallbackProvider != null) {
            registerMcpTools(BOOTSTRAP_MCP_PROVIDER, PROVIDER_TYPE_MCP, toolCallbackProvider.getToolCallbacks());
        }
    }

    /**
     * Constructor: register all spring local ToolCallback beans.
     */
    public GlobalToolManager(List<ToolCallback> initialTools) {
        if (initialTools != null) {
            initialTools.forEach(tool -> registerLocalTool(tool, true));
        }
        log.info("GlobalToolManager initialized. tools={}", toolRegistry.size());
    }

    public void register(ToolCallback[] tools) {
        if (tools == null) {
            return;
        }
        for (ToolCallback toolCallback : tools) {
            registerLocalTool(toolCallback, true);
        }
    }

    public void register(ToolCallback tool) {
        registerLocalTool(tool, true);
    }

    public void registerLocalTool(ToolCallback tool, boolean enabled) {
        registerTool(LOCAL_PROVIDER, PROVIDER_TYPE_LOCAL, tool, enabled);
    }

    public synchronized void registerMcpTools(String providerId, String providerType, ToolCallback[] tools) {
        if (tools == null) {
            return;
        }
        removeByProvider(providerId);
        for (ToolCallback tool : tools) {
            registerTool(providerId, providerType, tool, true);
        }
        log.info("Registered {} MCP tools for provider [{}]", tools.length, providerId);
    }

    public synchronized void removeByProvider(String providerId) {
        Set<String> ids = providerIndex.remove(providerId);
        if (ids == null || ids.isEmpty()) {
            return;
        }
        for (String toolId : ids) {
            ToolEntry removed = toolRegistry.remove(toolId);
            if (removed == null) {
                continue;
            }
            Set<String> sameNameSet = nameIndex.get(removed.getName());
            if (sameNameSet != null) {
                sameNameSet.remove(toolId);
                if (sameNameSet.isEmpty()) {
                    nameIndex.remove(removed.getName());
                }
            }
        }
        log.info("Removed {} tools for provider [{}]", ids.size(), providerId);
    }

    /**
     * Resolve tool names for an Agent.
     * Supports:
     * 1) fully qualified id: providerId:toolName
     * 2) raw name: toolName (if unique)
     */
    public List<ToolCallback> getTools(List<String> toolNames) {
        if (toolNames == null || toolNames.isEmpty()) {
            return Collections.emptyList();
        }

        List<ToolCallback> activeTools = new ArrayList<>();
        for (String requested : toolNames) {
            ToolEntry entry = findEntry(requested);
            if (entry == null) {
                continue;
            }
            if (!entry.enabled) {
                log.debug("Requested tool [{}] exists but disabled", requested);
                continue;
            }
            activeTools.add(entry.callback);
        }
        return activeTools;
    }

    public void enableTool(String toolNameOrId) {
        ToolEntry entry = findEntry(toolNameOrId);
        if (entry == null) {
            log.warn("Cannot enable tool [{}]: not found.", toolNameOrId);
            return;
        }
        entry.enabled = true;
        log.info("Tool [{}] has been enabled.", entry.getToolId());
    }

    public void disableTool(String toolNameOrId) {
        ToolEntry entry = findEntry(toolNameOrId);
        if (entry == null) {
            log.warn("Cannot disable tool [{}]: not found.", toolNameOrId);
            return;
        }
        entry.enabled = false;
        log.info("Tool [{}] has been disabled.", entry.getToolId());
    }

    public List<ToolInfo> getAllToolsInfo() {
        return toolRegistry.values()
                .stream()
                .sorted(Comparator.comparing(ToolEntry::getToolId))
                .map(entry -> new ToolInfo(
                        entry.getToolId(),
                        entry.getName(),
                        entry.callback.getToolDefinition().description(),
                        entry.callback.getToolDefinition().inputSchema(),
                        entry.providerId,
                        entry.providerType,
                        entry.enabled
                ))
                .collect(Collectors.toList());
    }

    public List<ToolInfo> getToolsByProvider(String providerId) {
        Set<String> ids = providerIndex.getOrDefault(providerId, Collections.emptySet());
        return ids.stream()
                .map(toolRegistry::get)
                .filter(Objects::nonNull)
                .sorted(Comparator.comparing(ToolEntry::getToolId))
                .map(entry -> new ToolInfo(
                        entry.getToolId(),
                        entry.getName(),
                        entry.callback.getToolDefinition().description(),
                        entry.callback.getToolDefinition().inputSchema(),
                        entry.providerId,
                        entry.providerType,
                        entry.enabled
                ))
                .collect(Collectors.toList());
    }

    public Collection<String> getProviderIds() {
        return providerIndex.keySet();
    }

    public Set<String> getProviderToolIds(String providerId) {
        return providerIndex.getOrDefault(providerId, Collections.emptySet());
    }

    private void registerTool(@Nullable String providerId, String providerType, ToolCallback tool, boolean enabled) {
        String sourceProvider = providerId == null ? LOCAL_PROVIDER : providerId;
        String name = tool.getToolDefinition().name();
        String toolId = buildToolId(sourceProvider, name);

        ToolEntry newEntry = new ToolEntry(toolId, name, sourceProvider, providerType, tool, enabled);
        ToolEntry old = toolRegistry.put(toolId, newEntry);

        if (old != null) {
            removeFromIndexes(old);
            log.warn("Tool [{}] overwritten", toolId);
        }

        nameIndex.computeIfAbsent(name, k -> ConcurrentHashMap.newKeySet()).add(toolId);
        providerIndex.computeIfAbsent(sourceProvider, k -> ConcurrentHashMap.newKeySet()).add(toolId);

        log.info("Tool registered: [{}] [{}] {}", sourceProvider, name, tool.getToolDefinition().description());
    }

    private void removeFromIndexes(ToolEntry entry) {
        Set<String> nameSet = nameIndex.get(entry.name);
        if (nameSet != null) {
            nameSet.remove(entry.toolId);
            if (nameSet.isEmpty()) {
                nameIndex.remove(entry.name);
            }
        }
        Set<String> providerSet = providerIndex.get(entry.providerId);
        if (providerSet != null) {
            providerSet.remove(entry.toolId);
            if (providerSet.isEmpty()) {
                providerIndex.remove(entry.providerId);
            }
        }
    }

    private ToolEntry findEntry(String toolNameOrId) {
        ToolEntry direct = toolRegistry.get(toolNameOrId);
        if (direct != null) {
            return direct;
        }
        Set<String> ids = nameIndex.get(toolNameOrId);
        if (ids == null || ids.isEmpty()) {
            log.debug("Requested tool [{}] not found in registry.", toolNameOrId);
            return null;
        }
        if (ids.size() == 1) {
            return toolRegistry.get(ids.iterator().next());
        }

        List<ToolEntry> entries = ids.stream()
                .map(toolRegistry::get)
                .filter(Objects::nonNull)
                .sorted(Comparator.comparing(ToolEntry::getToolId))
                .collect(Collectors.toList());
        for (ToolEntry entry : entries) {
            if (LOCAL_PROVIDER.equals(entry.providerId)) {
                log.warn("Tool name [{}] is ambiguous, resolved to local tool [{}]", toolNameOrId, entry.toolId);
                return entry;
            }
        }
        for (ToolEntry entry : entries) {
            if (entry.enabled) {
                log.warn("Tool name [{}] is ambiguous, resolved to first enabled tool [{}]", toolNameOrId, entry.toolId);
                return entry;
            }
        }
        log.warn("Tool name [{}] is ambiguous and all candidates are disabled", toolNameOrId);
        return entries.get(0);
    }

    public static String buildToolId(String providerId, String toolName) {
        return providerId + ":" + toolName;
    }

    @Getter
    @AllArgsConstructor
    private static class ToolEntry {
        private String toolId;
        private String name;
        private String providerId;
        private String providerType;
        private ToolCallback callback;
        private volatile boolean enabled;
    }

}
