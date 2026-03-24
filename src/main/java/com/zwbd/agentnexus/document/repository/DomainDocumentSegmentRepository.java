package com.zwbd.agentnexus.document.repository;

import com.zwbd.agentnexus.document.entity.DomainDocumentSegment;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * @Author: wnli
 * @Date: 2025/12/8 17:19
 * @Desc:
 */
@Repository
public interface DomainDocumentSegmentRepository extends JpaRepository<DomainDocumentSegment, Long> {

    // 1. 批量查询：根据 docId 查出所有切片，并按顺序排好
    List<DomainDocumentSegment> findByDocumentIdOrderBySequenceAsc(Long documentId);

    // 2. 批量删除：根据 docId 删除该文档所有切片
    // 注意：加 @Modifying 和 @Transactional 才能执行 delete 语句
    @Modifying
    @Transactional
    void deleteByDocumentId(Long documentId);

    // 分页查询接口
    // 命名规则：findBy + 字段名 + OrderBy + 排序字段 + 排序方向
    // 这里的 OrderBySequenceAsc 非常重要，保证分页数据的顺序是连贯的
    Page<DomainDocumentSegment> findByDocumentIdOrderBySequenceAsc(Long documentId, Pageable pageable);

    // 核心功能：断点续传读取
    // 语义：从 sequence > X 的位置开始，取接下来的 N 条
    Page<DomainDocumentSegment> findByDocumentIdAndSequenceGreaterThanOrderBySequenceAsc(
            Long documentId, Integer lastSequence, Pageable pageable
    );
}
