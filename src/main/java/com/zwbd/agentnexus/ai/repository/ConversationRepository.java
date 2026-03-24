package com.zwbd.agentnexus.ai.repository;

import com.zwbd.agentnexus.ai.entity.Conversation;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

/**
 * @Author: wnli
 * @Date: 2025/11/24 16:32
 * @Desc:
 */
@Repository
public interface ConversationRepository extends JpaRepository<Conversation, String> {
}
