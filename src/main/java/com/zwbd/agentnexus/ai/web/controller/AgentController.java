package com.zwbd.agentnexus.ai.web.controller;

import com.zwbd.agentnexus.ai.dto.ChatRequest;
import com.zwbd.agentnexus.ai.dto.StreamEvent;
import com.zwbd.agentnexus.ai.dto.ToolInfo;
import com.zwbd.agentnexus.ai.dto.model.OpenAiModelConfig;
import com.zwbd.agentnexus.ai.entity.AgentEntity;
import com.zwbd.agentnexus.ai.service.AgentFactory;
import com.zwbd.agentnexus.ai.service.ChatClientService;
import com.zwbd.agentnexus.ai.service.ModelRegistry;
import com.zwbd.agentnexus.ai.tools.GlobalToolManager;
import com.zwbd.agentnexus.common.web.ApiResponse;
import jakarta.validation.Valid;
import org.springframework.ai.chat.client.advisor.api.Advisor;
import org.springframework.ai.chat.messages.Message;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Flux;

import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * @Author: wnli
 * @Date: 2026/1/7 16:53
 * @Desc:
 */
@RestController
@RequestMapping("/api/v1/agent")
public class AgentController {

    @Autowired
    private GlobalToolManager toolManager;
    @Autowired
    private AgentFactory agentFactory;
    @Autowired
    private Map<String, Advisor> advisorRegistry;
    @Autowired
    private ModelRegistry modelRegistry;

    @Autowired
    private ChatClientService chatClientService;

    // ==========================================
    // 1. 资源查询接口 (用于前端下拉框数据源)
    // ==========================================

    @PostMapping(value = "/chat", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<StreamEvent> chatStream(String agentId, @Valid @RequestBody ChatRequest request) {
        return chatClientService.agentChat(agentId,request);
    }

    @GetMapping(value = "/history/{id}")
    public ApiResponse<List<Message>> history(@PathVariable String id) {
        List<Message> historyChat = chatClientService.getHistoryChat(id);
        return ApiResponse.success(historyChat);
    }

    /**
     * 获取所有可用工具的详细信息
     */
    @GetMapping("/tools")
    public ApiResponse<List<ToolInfo>> getAllTools() {
        return ApiResponse.ok(toolManager.getAllToolsInfo());
    }

    /**
     * 获取所有可用 Advisor 的名称列表
     */
    @GetMapping("/advisors")
    public ApiResponse<Set<String>> getAllAdvisors() {
        // 返回注册表中所有的 Key
        return ApiResponse.ok(advisorRegistry.keySet());
    }

    /**
     * 获取所有可用模型的名称
     */
    @GetMapping("/models")
    public ApiResponse<Set<String>> getAllModels() {
        return ApiResponse.ok(modelRegistry.getAllModels());
    }

    /**
     * 动态注册新的 OpenAI 兼容模型
     */
    @PostMapping("/model")
    public ApiResponse<String> registerModel(@RequestBody OpenAiModelConfig config) {
        // 简单校验
        if (config.getRegistrationName() == null || config.getApiKey() == null || config.getModelName() == null) {
            return ApiResponse.error("Missing required fields: registrationName, apiKey, or modelName.");
        }
        try {
            modelRegistry.registerOpenAiModel(config);
            return ApiResponse.ok("Model registered successfully: " + config.getRegistrationName());
        } catch (Exception e) {
            return ApiResponse.error("Failed to register model: " + e.getMessage());
        }
    }

    /**
     * 删除模型
     */
    @DeleteMapping("/model/{name}")
    public ApiResponse<Void> removeModel(@PathVariable String name) {
        if (!modelRegistry.containsModel(name)) {
            return ApiResponse.error("");
        }
        modelRegistry.deleteModel(name);
        return ApiResponse.success();
    }

    // ==========================================
    // 2. Agent 管理接口
    // ==========================================

    /**
     * 查询 Agent 详情
     */
    @GetMapping("/agents/{id}")
    public ApiResponse<AgentEntity> getAgent(@PathVariable String id) {
        return agentFactory.getAgentDefinition(id)
                .map(ApiResponse::ok)
                .orElse(ApiResponse.error("No such agent"));
    }

    @GetMapping("/agents/list")
    public ApiResponse<List<AgentEntity>> getAgents() {
        return ApiResponse.ok(agentFactory.getAgents());
    }

    /**
     * 创建新的 Agent
     */
    @PostMapping("/agents")
    public ApiResponse<AgentEntity> createAgent(@RequestBody AgentEntity agent) {
        try {
            AgentEntity created = agentFactory.createAgent(agent);
            return ApiResponse.ok(created);
        } catch (IllegalArgumentException e) {
            return ApiResponse.error("Invalid agent");
        }
    }

    /**
     * 更新 Agent
     */
    @PutMapping("/agents/{id}")
    public ApiResponse<AgentEntity> updateAgent(@PathVariable String id, @RequestBody AgentEntity agent) {
        if (!id.equals(agent.getId())) {
            return ApiResponse.error("Invalid agent");
        }
        // 检查是否存在
        if (agentFactory.getAgentDefinition(id).isEmpty()) {
            return ApiResponse.error("No such agent");
        }
        return ApiResponse.ok(agentFactory.saveAgent(agent));
    }

    /**
     * 删除 Agent
     */
    @DeleteMapping("/agents/{id}")
    public ApiResponse<Void> deleteAgent(@PathVariable String id) {
        agentFactory.deleteAgent(id);
        return ApiResponse.success();
    }
}