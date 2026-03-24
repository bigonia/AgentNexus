package com.zwbd.agentnexus.file;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

/**
 * @Author: wnli
 * @Date: 2025/11/13 14:58
 * @Desc:
 */
@Repository
public interface KnowledgeFileRepository extends JpaRepository<KnowledgeFile, Long> {
}
