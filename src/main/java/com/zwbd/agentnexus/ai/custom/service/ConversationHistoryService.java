package com.zwbd.agentnexus.ai.custom.service;

import com.zwbd.agentnexus.ai.custom.model.ChatMessageEntity;
import com.zwbd.agentnexus.ai.custom.respository.ChatMessageRepository;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

/**
 * @Author: wnli
 * @Date: 2025/9/18 15:12
 * @Desc:
 */
@Service
public class ConversationHistoryService {

    private final ChatMessageRepository chatMessageRepository;

    public ConversationHistoryService(ChatMessageRepository chatMessageRepository) {
        this.chatMessageRepository = chatMessageRepository;
    }

    /**
     * 从数据库中根据会话ID获取历史记录。
     *
     * @param sessionId 会话ID
     * @return 消息列表
     */
    @Transactional(readOnly = true)
    public List<Message> getHistory(String sessionId) {
        List<ChatMessageEntity> entities = chatMessageRepository.findBySessionIdOrderByMessageOrderAsc(sessionId);
        return entities.stream()
                .map(this::toMessage)
                .collect(Collectors.toList());
    }

    /**
     * 将一轮新的对话记录追加到数据库中。
     *
     * @param sessionId   会话ID
     * @param newMessages 要添加的消息列表
     */
    @Transactional
    public void addHistory(String sessionId, List<Message> newMessages) {
        Integer maxOrder = chatMessageRepository.findMaxMessageOrderBySessionId(sessionId);
        int currentOrder = (maxOrder != null) ? maxOrder : 0;

        List<ChatMessageEntity> entitiesToSave = new ArrayList<>();
        for (Message message : newMessages) {
            currentOrder++;
            entitiesToSave.add(toEntity(sessionId, message, currentOrder));
        }

        chatMessageRepository.saveAll(entitiesToSave);
    }

    /**
     * 【新增】根据会话ID删除整个对话历史。
     *
     * @param sessionId 要删除的会话ID
     */
    @Transactional
    public void deleteHistory(String sessionId) {
        chatMessageRepository.deleteBySessionId(sessionId);
    }

    /**
     * 【新增】获取所有历史会话的ID列表。
     *
     * @return 所有唯一的会话ID列表
     */
    @Transactional(readOnly = true)
    public List<String> listConversations() {
        return chatMessageRepository.findDistinctSessionIds();
    }


    /**
     * 将 ChatMessageEntity 转换为 Spring AI 的 Message 对象。
     */
    private Message toMessage(ChatMessageEntity entity) {
        return switch (entity.getRole().toLowerCase()) {
            case "user" -> new UserMessage(entity.getContent());
            case "assistant" -> new AssistantMessage(entity.getContent());
            default -> throw new IllegalArgumentException("Unknown role: " + entity.getRole());
        };
    }

    /**
     * 将 Spring AI 的 Message 对象转换为用于持久化的 ChatMessageEntity。
     */
    private ChatMessageEntity toEntity(String sessionId, Message message, int order) {
        ChatMessageEntity entity = new ChatMessageEntity();
        entity.setSessionId(sessionId);
        entity.setContent(message.getText());
        entity.setRole(message.getMessageType().getValue());
        entity.setMessageOrder(order);
        return entity;
    }
}