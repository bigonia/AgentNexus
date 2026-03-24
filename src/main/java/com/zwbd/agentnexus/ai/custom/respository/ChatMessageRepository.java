package com.zwbd.agentnexus.ai.custom.respository;

import com.zwbd.agentnexus.ai.custom.model.ChatMessageEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

/**
 * @Author: wnli
 * @Date: 2025/9/18 15:49
 * @Desc:
 */
@Repository
public interface ChatMessageRepository extends JpaRepository<ChatMessageEntity, UUID> {

    /**
     * 根据会话ID查找所有消息，并按顺序排序。
     * @param sessionId 会话ID
     * @return 消息实体列表
     */
    List<ChatMessageEntity> findBySessionIdOrderByMessageOrderAsc(String sessionId);

    /**
     * 查找指定会话中最大的消息顺序号。
     * @param sessionId 会话ID
     * @return 最大的 messageOrder，如果会话不存在则返回 null
     */
    @Query("SELECT MAX(c.messageOrder) FROM ChatMessageEntity c WHERE c.sessionId = :sessionId")
    Integer findMaxMessageOrderBySessionId(String sessionId);

    /**
     * 【新增】根据会话ID删除所有相关的聊天记录。
     * @param sessionId 要删除的会话ID
     */
    @Transactional
    void deleteBySessionId(String sessionId);

    /**
     * 【新增】查询所有不重复的会话ID列表。
     * @return session_id 列表
     */
    @Query("SELECT DISTINCT c.sessionId FROM ChatMessageEntity c ORDER BY c.sessionId ASC")
    List<String> findDistinctSessionIds();
}
