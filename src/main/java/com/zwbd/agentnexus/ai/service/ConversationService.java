package com.zwbd.agentnexus.ai.service;

import com.zwbd.agentnexus.ai.entity.Conversation;
import com.zwbd.agentnexus.ai.repository.ConversationRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.messages.Message;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

/**
 * @Author: wnli
 * @Date: 2025/11/24 16:33
 * @Desc: 用户对话信息管理
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ConversationService {

    private final ConversationRepository conversationRepository;
    private final ChatMemory chatMemory;

    /**
     * 创建或初始化一个新会话
     */
    public Conversation createConversation(String title) {
        String conversationId = UUID.randomUUID().toString();
        Conversation conversation = Conversation.builder()
                .id(conversationId)
                .title(title == null || title.isEmpty() ? "新对话" : title)
                .build();
        return conversationRepository.save(conversation);
    }

    /**
     * 获取用户的会话列表
     */
    public List<Conversation> getUserConversations() {
        return conversationRepository.findAll(Sort.by("updatedAt").descending());
    }

    /**
     * 获取特定会话的消息历史 (从 ChatMemory 读取)
     */
    public List<Message> getConversationMessages(String conversationId) {
        return chatMemory.get(conversationId);
    }

    /**
     * 更新会话标题（通常在第一轮对话后由 AI 自动生成并回调更新）
     */
    public void updateTitle(String conversationId, String newTitle) {
        conversationRepository.findById(conversationId).ifPresent(c -> {
            c.setTitle(newTitle);
            conversationRepository.save(c);
        });
    }

    /**
     * 删除会话
     * 关键点：同时删除 业务表记录 和 AI记忆
     */
    @Transactional
    public void deleteConversation(String conversationId) {
        // 1. 删除 Spring AI ChatMemory 中的消息记录
        chatMemory.clear(conversationId);
        log.info("Cleared chat memory for session: {}", conversationId);

        // 2. 删除业务元数据
        conversationRepository.deleteById(conversationId);
    }
}