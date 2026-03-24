package com.zwbd.agentnexus.document.repository;

import com.zwbd.agentnexus.document.entity.DomainDocument;
import com.zwbd.agentnexus.document.entity.DomainDocumentStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.util.List;

/**
 * @Author: wnli
 * @Date: 2025/11/25 10:32
 * @Desc:
 */
@Repository
public interface DomainDocumentRepository extends JpaRepository<DomainDocument, Long> {

    List<DomainDocument> findBySourceId(String sourceId);

    List<DomainDocument> findByStatus(DomainDocumentStatus status);

    @Modifying
    @Query("UPDATE DomainDocument d SET d.status = :status WHERE d.id = :id")
    void updateStatus(Long id, DomainDocumentStatus status, String error);

    // 查找特定状态的文档用于定时任务处理
    List<DomainDocument> findTop10ByStatusOrderByCreatedAtAsc(DomainDocumentStatus status);
}
