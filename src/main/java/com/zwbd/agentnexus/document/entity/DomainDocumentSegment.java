package com.zwbd.agentnexus.document.entity;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.persistence.*;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.io.IOException;
import java.util.HashMap;
import java.util.Map;

/**
 * @Author: wnli
 * @Date: 2025/12/8 16:47
 * @Desc:
 */
@Slf4j
@Data
@Entity
@Table(name = "ai_domain_document_segment", indexes = {
        // 加上索引，保证查询和删除的性能
        @Index(name = "idx_doc_id", columnList = "documentId")
})
public class DomainDocumentSegment {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY) // 最简单的自增策略
    private Long id;

    @Column(nullable = false)
    private Long documentId;

    @Column(nullable = false)
    private Long sequence;

    @Column(columnDefinition = "TEXT")
    private String content;

    // PGSQL 推荐用 jsonb，如果不想太复杂，用 TEXT 存 JSON 字符串也完全没问题
//    @Convert(converter = MapJsonConverter.class)
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb")
    private Map<String, Object> metadata = new HashMap<>();

    public DocumentContext toDocumentContext() {
        return new DocumentContext(content,metadata);
    }

    @Converter
    public static class MapJsonConverter implements AttributeConverter<Map<String, Object>, String> {
        private static final ObjectMapper mapper = new ObjectMapper();

        @Override
        public String convertToDatabaseColumn(Map<String, Object> attribute) {
            try {
                return attribute == null ? "{}" : mapper.writeValueAsString(attribute);
            } catch (IOException e) {
                log.error("Error converting map to JSON", e);
                return "{}";
            }
        }

        @Override
        public Map<String, Object> convertToEntityAttribute(String dbData) {
            try {
                return dbData == null ? new HashMap<>() : mapper.readValue(dbData, new TypeReference<>() {
                });
            } catch (IOException e) {
                log.error("Error converting JSON to map", e);
                return new HashMap<>();
            }
        }
    }

}
