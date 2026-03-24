package com.zwbd.agentnexus.document.service;

import com.zwbd.agentnexus.document.entity.DocumentContext;
import com.zwbd.agentnexus.document.entity.DomainDocumentSegment;
import com.zwbd.agentnexus.document.repository.DomainDocumentSegmentRepository;
import org.springframework.ai.document.Document;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

/**
 * @Author: wnli
 * @Date: 2025/12/9 8:58
 * @Desc:
 */
@Service
public class DocumentContextService {

    @Autowired
    private DomainDocumentSegmentRepository domainDocumentSegmentRepository;

    /**
     * 业务需求：保存文档切片（批量插入）
     */
    @Transactional
    public void saveDocumentContext(Long docId, List<DocumentContext> contexts) {
        List<DomainDocumentSegment> segments = new ArrayList<>();
        for (int i = 0; i < contexts.size(); i++) {
            DomainDocumentSegment seg = new DomainDocumentSegment();
            seg.setDocumentId(docId);
            seg.setSequence((long)i); // 记录顺序
            seg.setContent(contexts.get(i).getText());
            seg.setMetadata(contexts.get(i).getMetadata());
            segments.add(seg);
        }
        domainDocumentSegmentRepository.saveAll(segments);
    }

    @Transactional
    public void saveDocuments(Long docId, List<Document> contexts) {
        List<DomainDocumentSegment> segments = new ArrayList<>();
        for (int i = 0; i < contexts.size(); i++) {
            DomainDocumentSegment seg = new DomainDocumentSegment();
            seg.setDocumentId(docId);
            seg.setSequence((long)i); // 记录顺序
            seg.setContent(contexts.get(i).getText());
            seg.setMetadata(contexts.get(i).getMetadata());
            segments.add(seg);
        }
        domainDocumentSegmentRepository.saveAll(segments);
    }

    public DomainDocumentSegment getDocSegment(Long contextId) {
        DomainDocumentSegment segment = domainDocumentSegmentRepository.findById(contextId).get();
        return segment;
    }

    /**
     * 业务需求：获取文档内容（批量查询）
     */
    public List<DocumentContext> getDocumentContents(Long docId) {
        return domainDocumentSegmentRepository.findByDocumentIdOrderBySequenceAsc(docId)
                .stream()
                .map(seg -> {
                    // 转回业务对象
                    return new DocumentContext(seg.getContent(), seg.getMetadata());
                })
                .collect(Collectors.toList());
    }

    /**
     * 业务需求：删除文档（级联删除切片）
     */
    @Transactional
    public void deleteDocumentContext(Long docId) {
        domainDocumentSegmentRepository.deleteByDocumentId(docId);
    }

    /**
     * 分页获取文档切片
     *
     * @param docId 文档ID
     * @param page  页码 (建议 0 为第一页)
     * @param size  每页大小
     * @return 包含分页信息和数据列表的 Page 对象
     */
    public Page<DocumentContext> getDocumentContentPage(Long docId, int page, int size) {
        // 1. 构建分页请求对象
        Pageable pageable = PageRequest.of(page, size);
        // 2. 调用 Repository
        Page<DomainDocumentSegment> segmentPage = domainDocumentSegmentRepository.findByDocumentIdOrderBySequenceAsc(docId, pageable);
        // 3. 转换对象 (Entity -> DTO/VO)
        return segmentPage.map(this::convertToContext);
    }

    // 辅助转换方法
    private DocumentContext convertToContext(DomainDocumentSegment seg) {
        DocumentContext ctx = new DocumentContext(seg.getContent(), seg.getMetadata());
        // 如果需要返回该切片在数据库里的ID（用于后续精确修改/删除），也可以在这里set进去
        // ctx.getMetadata().put("segmentId", seg.getId());
        return ctx;
    }

}
