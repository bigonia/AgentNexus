package com.zwbd.agentnexus.ai.tools;

import com.zwbd.agentnexus.ai.dto.ToolInfo;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.mcp.SyncMcpToolCallbackProvider;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * @Author: wnli
 * @Date: 2026/1/6 17:01
 * @Desc: 全局工具管理，控制工具状态，查看全部工具
 */
@Slf4j
@Service
public class GlobalToolManager {


    // 物理存储：ToolName -> ToolCallback 实例
    private final Map<String, ToolCallback> toolRegistry = new ConcurrentHashMap<>();

    // 状态管理：ToolName -> IsEnabled (true: 启用, false: 禁用)
    private final Map<String, Boolean> toolStatus = new ConcurrentHashMap<>();

    @Autowired
    public void setSyncMcpToolCallbackProvider(SyncMcpToolCallbackProvider toolCallbackProvider){
        register(toolCallbackProvider.getToolCallbacks());
    }

    /**
     * 构造函数：启动时自动装配 Spring 容器中已有的 ToolCallback
     */
    public GlobalToolManager(List<ToolCallback> initialTools) {
        if (initialTools != null) {
            initialTools.forEach(this::register);
        }
        log.info("GlobalToolManager initialized. tools: {}", toolRegistry.values());
    }

    public void register(ToolCallback[] tool){
        for (ToolCallback toolCallback : tool) {
            ToolDefinition toolDefinition = toolCallback.getToolDefinition();
//            toolCallback.getToolMetadata()
            register(toolCallback);
        }
    }

    /**
     * 注册工具
     * 如果存在同名工具，将被覆盖
     */
    public void register(ToolCallback tool) {
        String name = tool.getToolDefinition().name();

        if (toolRegistry.containsKey(name)) {
            log.warn("Tool [{}] is being overwritten by a new registration.", name);
        }

        toolRegistry.put(name, tool);
        // 默认注册即启用，除非之前显式禁用过（保留状态），这里简化为默认 true
        toolStatus.putIfAbsent(name, true);

        log.info("Tool registered: [{}] - {}", name, tool.getToolDefinition().description());
    }

    /**
     * 核心方法：根据名称列表获取可用的工具实例
     * 通常由 AgentFactory 在组装 ChatClient 时调用
     *
     * @param toolNames Agent 申请使用的工具名称列表
     * @return 过滤后的、可用的 ToolCallback 列表
     */
    public List<ToolCallback> getTools(List<String> toolNames) {
        if (toolNames == null || toolNames.isEmpty()) {
            return Collections.emptyList();
        }

        List<ToolCallback> activeTools = new ArrayList<>();

        for (String name : toolNames) {
            ToolCallback tool = toolRegistry.get(name);
            Boolean isEnabled = toolStatus.getOrDefault(name, false);

            if (tool != null && isEnabled) {
                activeTools.add(tool);
            } else {
                if (tool == null) {
                    log.debug("Requested tool [{}] not found in registry.", name);
                } else {
                    log.debug("Requested tool [{}] is currently disabled.", name);
                }
            }
        }
        return activeTools;
    }

    /**
     * 管理功能：启用工具
     */
    public void enableTool(String toolName) {
        if (toolRegistry.containsKey(toolName)) {
            toolStatus.put(toolName, true);
            log.info("Tool [{}] has been enabled.", toolName);
        } else {
            log.warn("Cannot enable tool [{}]: Tool not found.", toolName);
        }
    }

    /**
     * 管理功能：禁用工具
     */
    public void disableTool(String toolName) {
        if (toolRegistry.containsKey(toolName)) {
            toolStatus.put(toolName, false);
            log.info("Tool [{}] has been disabled.", toolName);
        } else {
            log.warn("Cannot disable tool [{}]: Tool not found.", toolName);
        }
    }

    /**
     * 观测功能：获取所有工具的当前状态信息
     * 用于管理后台 UI 展示
     */
    public List<ToolInfo> getAllToolsInfo() {
        return toolRegistry.values().stream()
                .map(tool -> {
                    String name = tool.getToolDefinition().name();
                    return new ToolInfo(
                            name,
                            tool.getToolDefinition().description(),
                            tool.getToolDefinition().inputSchema()
//                            ,toolStatus.getOrDefault(name, false)
                    );
                })
                .collect(Collectors.toList());
    }

}
