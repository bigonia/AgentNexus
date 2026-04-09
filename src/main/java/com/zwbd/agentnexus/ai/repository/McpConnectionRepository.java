package com.zwbd.agentnexus.ai.repository;

import com.zwbd.agentnexus.ai.entity.McpConnectionEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface McpConnectionRepository extends JpaRepository<McpConnectionEntity, String> {

    List<McpConnectionEntity> findByEnabledTrue();

}

