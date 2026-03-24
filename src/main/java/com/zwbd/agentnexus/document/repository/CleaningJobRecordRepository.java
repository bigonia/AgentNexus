package com.zwbd.agentnexus.document.repository;

import com.zwbd.agentnexus.document.dto.CleaningRecordDiffDto;
import com.zwbd.agentnexus.document.entity.CleaningJobRecord;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/**
 * @Author: wnli
 * @Date: 2025/12/9 10:55
 * @Desc:
 */
@Repository
public interface CleaningJobRecordRepository extends JpaRepository<CleaningJobRecord, Long> {
    // 全量重跑时的清理逻辑
    @Modifying
    @Query("DELETE FROM CleaningJobRecord r WHERE r.jobId = :jobId")
    void deleteByJobId(Long jobId);

    /**
     * 高效联表查询：直接构造 DiffDto
     * 避免 N+1 问题，一次性取出 Original 和 Cleaned
     */
    @Transactional(readOnly = true)
    @Query("SELECT new com.zwbd.agentnexus.document.dto.CleaningRecordDiffDto(" +
            "r.id, r.sourceSegmentId, s.content, r.resultContent, r.status, r.errorMessage) " +
            "FROM CleaningJobRecord r " +
            "JOIN DomainDocumentSegment s ON r.sourceSegmentId = s.id " +
            "WHERE r.jobId = :jobId " +
            "ORDER BY r.id ASC") // 保证顺序
    Page<CleaningRecordDiffDto> findDiffsByJobId(Long jobId, Pageable pageable);

}
