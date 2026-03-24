package com.zwbd.agentnexus.document.entity;

import com.fasterxml.jackson.annotation.JsonAlias;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.ai.document.Document;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * @Author: wnli
 * @Date: 2025/12/8 16:55
 * @Desc:
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class DocumentContext {

    private String id;
    @JsonAlias("text")
    private String text;
    private Map<String, Object> metadata = new HashMap<>();

    public DocumentContext(String id, String text) {
        this.id = id;
        this.text = text;
    }

    public DocumentContext(String text, Map<String, Object> metadata) {
        this.text = text;
        this.metadata = metadata;
    }

    public static List<DocumentContext> build(List<Document> documents) {
        return documents.stream().map(document -> new DocumentContext(document.getId(), document.getText(), document.getMetadata())).toList();
    }

}
