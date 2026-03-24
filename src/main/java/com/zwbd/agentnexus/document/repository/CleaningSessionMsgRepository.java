package com.zwbd.agentnexus.document.repository;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

/**
 * @Author: wnli
 * @Date: 2025/12/9 10:58
 * @Desc:
 */
public interface CleaningSessionMsgRepository extends JpaRepository<CleaningSessionMsg, Long> {
    List<CleaningSessionMsg> findByJobIdOrderByCreatedAtAsc(Long jobId);
}