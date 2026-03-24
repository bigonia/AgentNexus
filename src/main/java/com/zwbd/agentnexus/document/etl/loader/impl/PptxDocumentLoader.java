package com.zwbd.agentnexus.document.etl.loader.impl;

import com.zwbd.agentnexus.document.etl.loader.DocumentLoader;
import com.zwbd.agentnexus.ai.dto.document.metadata.BaseMetadata;
import com.zwbd.agentnexus.ai.dto.document.metadata.DocumentType;
import com.zwbd.agentnexus.ai.dto.document.metadata.FileUploadMetadata;
import lombok.extern.slf4j.Slf4j;
import org.apache.poi.sl.usermodel.Placeholder;
import org.apache.poi.xslf.usermodel.*;
import org.springframework.ai.document.Document;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.Resource;
import org.springframework.core.io.ResourceLoader;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Paths;
import java.util.*;

/**
 * @Author: wnli
 * @Date: 2025/11/20 10:33
 * @Desc:
 */
@Slf4j
@Component
public class PptxDocumentLoader implements DocumentLoader {

    @Autowired
    private ResourceLoader resourceLoader;

    @Override
    public List<Document> load(BaseMetadata metadata) {
        FileUploadMetadata fileUploadMetadata = (FileUploadMetadata) metadata;
        String filePathStr = fileUploadMetadata.getFilePath();
        String fileUri = Paths.get(filePathStr).toUri().toString();
        Resource resource = this.resourceLoader.getResource(fileUri);
        //基于apache poi处理PPT
        List<Document> documents = new ArrayList<>();

        try (InputStream is = resource.getInputStream();
             XMLSlideShow ppt = new XMLSlideShow(is)) {

            List<XSLFSlide> slides = ppt.getSlides();
            log.info("开始解析 PPT: {}, 共 {} 页", resource.getFilename(), slides.size());

            for (int i = 0; i < slides.size(); i++) {
                XSLFSlide slide = slides.get(i);
                int pageNumber = i + 1;

                // 1. 提取当前页的所有文本内容
                StringBuilder pageContent = new StringBuilder();

                // 提取标题 (PPT的标题占位符)
                String title = slide.getTitle();
                if (title != null && !title.isBlank()) {
                    pageContent.append("【标题】: ").append(title.trim()).append("\n");
                }

                // 递归提取形状中的文本 (处理文本框、表格、组合图形)
                extractTextFromShapes(slide.getShapes(), pageContent);

                // 2. (可选) 提取演讲者备注
                XSLFNotes notes = slide.getNotes();
                if (notes != null) {
                    StringBuilder notesText = new StringBuilder();
                    // 备注页也有 Shape 结构
                    extractTextFromShapes(notes.getShapes(), notesText);
                    if (notesText.length() > 0) {
                        pageContent.append("\n【演讲者备注】:\n").append(notesText);
                    }
                }

                String finalText = pageContent.toString().trim();

                // 3. 只有当页面有实质内容时才生成 Document
                if (!finalText.isEmpty()) {
                    Map<String, Object> map = fileUploadMetadata.toMap();
                    map.put("page_number", pageNumber);
                    map.put("total_pages", slides.size());

                    Document doc = new Document(finalText, map);
                    documents.add(doc);
                }
            }

        } catch (IOException e) {
            log.error("解析 PPTX 文件失败: {}", resource.getFilename(), e);
            throw new RuntimeException("Failed to parse PPTX file: " + resource.getFilename(), e);
        }
        return documents;
    }

    /**
     * 递归处理形状列表（支持 GroupShape）
     */
    private void extractTextFromShapes(List<XSLFShape> shapes, StringBuilder sb) {
        for (XSLFShape shape : shapes) {
            if (shape instanceof XSLFTextShape) {
                // 1. 处理普通文本框 (TextBox, AutoShape)
                XSLFTextShape textShape = (XSLFTextShape) shape;
                String text = textShape.getText();
                // 忽略占位符标题，因为前面已经单独提取过 title 了，避免重复
                if (text != null && !text.isBlank() && !isTitleShape(textShape)) {
                    sb.append(text.trim()).append("\n");
                }
            } else if (shape instanceof XSLFTable) {
                // 2. 处理表格 (Table)
                XSLFTable table = (XSLFTable) shape;
                extractTextFromTable(table, sb);
            } else if (shape instanceof XSLFGroupShape) {
                // 3. 处理组合形状 (SmartArt 或 组合图) - 递归调用
                XSLFGroupShape groupShape = (XSLFGroupShape) shape;
                extractTextFromShapes(groupShape.getShapes(), sb);
            }
        }
    }

    /**
     * 专门处理表格数据，将其转换为类 Markdown 格式以便 LLM 理解
     */
    private void extractTextFromTable(XSLFTable table, StringBuilder sb) {
        sb.append("\n[表格数据]:\n");
        for (XSLFTableRow row : table.getRows()) {
            List<String> rowData = new ArrayList<>();
            for (XSLFTableCell cell : row.getCells()) {
                String cellText = cell.getText();
                rowData.add(cellText != null ? cellText.trim() : "");
            }
            // 使用 | 分隔符模拟 Markdown 表格
            sb.append("| ").append(String.join(" | ", rowData)).append(" |\n");
        }
        sb.append("\n");
    }

    /**
     * 判断是否为标题形状 (简单的启发式判断)
     */
    /**
     * 判断是否为标题形状 (简单的启发式判断)
     */
    private boolean isTitleShape(XSLFTextShape shape) {
        Placeholder type = shape.getTextType();
        // POI 5.x 使用 Placeholder.TITLE 和 Placeholder.CENTERED_TITLE
        return type == Placeholder.TITLE || type == Placeholder.CENTERED_TITLE;
    }

    @Override
    public Set<DocumentType> getSourceType() {
        return Set.of(DocumentType.PPT, DocumentType.PPTX);
    }
}
