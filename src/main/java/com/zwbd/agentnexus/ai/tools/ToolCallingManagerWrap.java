package com.zwbd.agentnexus.ai.tools;

import com.zwbd.agentnexus.ai.dto.StreamEvent;
import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.metadata.Usage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import org.springframework.ai.model.tool.ToolCallingManager;
import org.springframework.ai.model.tool.ToolExecutionResult;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.util.CollectionUtils;

import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/**
 * @Author: wnli
 * @Date: 2025/10/24 15:05
 * @Desc: 自定义工具管理类，记录工具执行记录
 */
@Slf4j
@AllArgsConstructor
public class ToolCallingManagerWrap implements ToolCallingManager {

    private final ToolCallingManager toolCallingManager;

    @Override
    public List<ToolDefinition> resolveToolDefinitions(ToolCallingChatOptions chatOptions) {
        List<ToolDefinition> toolDefinitions = toolCallingManager.resolveToolDefinitions(chatOptions);
        log.info("toolCallingManager resolve toolDefinitions: {}", toolDefinitions);
        return toolDefinitions;
    }

    @Override
    public ToolExecutionResult executeToolCalls(Prompt prompt, ChatResponse chatResponse) {
        Consumer<StreamEvent> consumer = extractConsumer(prompt);
        Usage usage = chatResponse.getMetadata().getUsage();
        chatResponse.getMetadata().getModel();
        log.info("token cost {}",usage.getTotalTokens());
        long t = System.currentTimeMillis();
        ToolExecutionResult result = toolCallingManager.executeToolCalls(prompt, chatResponse);
        long cost = System.currentTimeMillis() - t;
        List<Message> conversationHistory = result.conversationHistory();
        if (conversationHistory.get(conversationHistory.size() - 1) instanceof ToolResponseMessage toolResponseMessage) {
            toolResponseMessage.getResponses().forEach(response -> {
                if (consumer != null) {
                    StreamEvent toolEvent = new StreamEvent(
                            StreamEvent.EventType.TOOL_EXECUTION,
                            Map.of(
                                    "toolId", response.id(),
                                    "toolName", response.name(),
                                    "toolResult", response.responseData(),
                                    "toolCost", cost
                            )
                    );
                    consumer.accept(toolEvent);
                }
            });
        }
        return result;
    }

    /**
     * 核心逻辑：从 Prompt 中提取 Conversation ID
     * 上层调用时需使用: chatClient.prompt().toolContext(Map.of("conversationId", "xxx")).call()
     */
    private String extractConversationId(Prompt prompt) {
        if (prompt.getOptions() instanceof ToolCallingChatOptions options) {
            Map<String, Object> context = options.getToolContext();
            if (!CollectionUtils.isEmpty(context) && context.containsKey("conversationId")) {
                return String.valueOf(context.get("conversationId"));
            }
        }
        return "unknown"; // 或者 null
    }

    private Consumer<StreamEvent> extractConsumer(Prompt prompt) {
        if (prompt.getOptions() instanceof ToolCallingChatOptions options) {
            Map<String, Object> context = options.getToolContext();
            if (!CollectionUtils.isEmpty(context) && context.containsKey("TOOL_LOG_CONSUMER")) {
                return (Consumer<StreamEvent>) context.get("TOOL_LOG_CONSUMER");
            }
        }
        return null;
    }

}
