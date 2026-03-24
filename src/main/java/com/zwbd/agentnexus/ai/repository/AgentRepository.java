package com.zwbd.agentnexus.ai.repository;

import com.zwbd.agentnexus.ai.entity.AgentEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

/**
 * @Author: wnli
 * @Date: 2026/1/7 15:19
 * @Desc:
 */
@Repository
public interface AgentRepository extends JpaRepository<AgentEntity, String> {
}
