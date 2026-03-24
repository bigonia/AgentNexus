package com.zwbd.agentnexus.ai.service;

import com.zwbd.agentnexus.ai.entity.AgentEntity;
import com.zwbd.agentnexus.ai.repository.AgentRepository;
import com.zwbd.agentnexus.ai.tools.GlobalToolManager;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.api.Advisor;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * @Author: wnli
 * @Date: 2026/1/7 15:20
 * @Desc:
 */
@Slf4j
@Service
public class AgentFactory {


    private final AgentRepository agentRepository;
    private final GlobalToolManager toolManager;
    private final ModelRegistry modelRegistry;
    // 注入 Advisor 注册表 (BeanName -> Advisor Instance)
    private final Map<String, Advisor> advisorRegistry;

    // 如果需要默认的 ChatClient.Builder 原型
    private final ChatClient.Builder defaultBuilder;

    // 缓存池：存储已构建的 ChatClient 实例
    private final Map<String, ChatClient> clientCache = new ConcurrentHashMap<>();

    public AgentFactory(AgentRepository agentRepository,
                        GlobalToolManager toolManager,
                        ModelRegistry modelRegistry,
                        Map<String, Advisor> advisorRegistry,
                        ChatClient.Builder defaultBuilder) {
        this.agentRepository = agentRepository;
        this.toolManager = toolManager;
        this.modelRegistry = modelRegistry;
        this.advisorRegistry = advisorRegistry;
        this.defaultBuilder = defaultBuilder;
    }

    public List<AgentEntity> getAgents() {
        return agentRepository.findAll();
    }

    /**
     * 核心接口：获取 Agent 对应的 ChatClient (带缓存)
     * 如果缓存中存在则直接返回，否则重新构建
     */
    public ChatClient getAgentClient(String agentId) {
        return clientCache.computeIfAbsent(agentId, this::buildAgentClient);
    }

    /**
     * 根据 Agent ID 创建可执行的 ChatClient
     */
    @Transactional(readOnly = true)
    public ChatClient buildAgentClient(String agentId) {
        // 1. 获取 Agent 定义
        AgentEntity agentEntity = agentRepository.findById(agentId)
                .orElseThrow(() -> new IllegalArgumentException("Agent definition not found for ID: " + agentId));

        // 2. 获取对应的 ChatModel
        String modelName = agentEntity.getModelName();
        ChatModel chatModel = modelRegistry.getModel(modelName);

        // 如果注册中心没有指定模型，尝试使用默认模型或抛出异常
        if (chatModel == null) {
            throw new IllegalStateException("ChatModel not found in registry: " + modelName);
        }

        // 3. 获取工具列表
        List<String> toolNames = agentEntity.getToolNames();
        List<ToolCallback> tools = toolManager.getTools(toolNames);

        // 4. 获取 Advisors (增强组件)
        List<String> advisorNames = agentEntity.getAdvisors();
        List<Advisor> advisors = new ArrayList<>();
        if (advisorNames != null && !advisorNames.isEmpty()) {
            advisors = advisorNames.stream()
                    .map(name -> {
                        Advisor advisor = advisorRegistry.get(name);
                        if (advisor == null) {
                            log.warn("Advisor [{}] not found in registry, skipping.", name);
                        }
                        return advisor;
                    })
                    .filter(Objects::nonNull)
                    .collect(Collectors.toList());
        }

        log.info("Building Agent [{}]: Model={}, Tools={}, Advisors={}",
                agentEntity.getName(), modelName, toolNames, advisorNames);

        // 5. 组装 ChatClient
        // 注意：ChatClient.builder(chatModel) 会创建一个新的 Builder 绑定到该模型
        return ChatClient.builder(chatModel)
                .defaultSystem(agentEntity.getSystemPrompt())
                .defaultToolCallbacks(tools)
                .defaultAdvisors(advisors) // 注入选择的增强组件
                .build();
    }

    /**
     * 明确的创建接口：创建新的 Agent 定义
     * 包含查重逻辑，避免意外覆盖
     */
    @Transactional
    public AgentEntity createAgent(AgentEntity agent) {
        return saveAgent(agent);
    }

    /**
     * 管理接口：更新或保存 Agent 定义
     */
    @Transactional
    public AgentEntity saveAgent(AgentEntity agent) {
        // 简单校验
        if (!modelRegistry.containsModel(agent.getModelName())) {
            log.warn("Warning: Saving agent with unknown model name: {}", agent.getModelName());
        }
        // 校验 Advisors 是否存在
        if (agent.getAdvisors() != null) {
            for (String advisorName : agent.getAdvisors()) {
                if (!advisorRegistry.containsKey(advisorName)) {
                    log.warn("Warning: Agent references unknown advisor: {}", advisorName);
                }
            }
        }
        return agentRepository.save(agent);
    }

    /**
     * 管理接口：删除 Agent
     */
    @Transactional
    public void deleteAgent(String agentId) {
        agentRepository.deleteById(agentId);
    }

    /**
     * 查询接口：获取 Agent 定义
     */
    public Optional<AgentEntity> getAgentDefinition(String agentId) {
        return agentRepository.findById(agentId);
    }

}
