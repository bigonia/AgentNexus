package com.zwbd.agentnexus.sdui.section;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;

@Slf4j
@Service
@RequiredArgsConstructor
public class PageService {

    private final PageRepository pageRepository;
    private final ObjectMapper objectMapper;

    @Transactional
    public SduiPageEntity create(String name, String layout, List<Map<String, Object>> sections) {
        return create(null, name, layout, false, 0, sections);
    }

    @Transactional
    public SduiPageEntity create(String name, String layout, boolean autoScroll, int autoScrollMs,
                                  List<Map<String, Object>> sections) {
        return create(null, name, layout, autoScroll, autoScrollMs, sections);
    }

    /**
     * @param pageId optional explicit pageId; if null or blank, a random one is generated.
     */
    @Transactional
    public SduiPageEntity create(String pageId, String name, String layout, boolean autoScroll,
                                  int autoScrollMs, List<Map<String, Object>> sections) {
        SduiPageEntity entity = new SduiPageEntity();
        entity.setPageId(pageId != null && !pageId.isBlank() ? pageId : SduiPageEntity.generatePageId());
        entity.setName(name != null && !name.isBlank() ? name : entity.getPageId());
        entity.setLayout(layout != null && !layout.isBlank() ? layout : "vertical_scroll");
        entity.setAutoScroll(autoScroll);
        entity.setAutoScrollMs(autoScrollMs);
        entity.setSectionsJson(toSectionsJson(sections));
        entity.setStatus("active");
        return pageRepository.save(entity);
    }

    @Transactional
    public SduiPageEntity update(String pageId, String name, String layout, boolean autoScroll,
                                  int autoScrollMs, List<Map<String, Object>> sections) {
        SduiPageEntity entity = requireByPageId(pageId);
        if (name != null && !name.isBlank()) entity.setName(name);
        if (layout != null && !layout.isBlank()) entity.setLayout(layout);
        entity.setAutoScroll(autoScroll);
        entity.setAutoScrollMs(autoScrollMs);
        if (sections != null) entity.setSectionsJson(toSectionsJson(sections));
        return pageRepository.save(entity);
    }

    @Transactional
    public void delete(String pageId) {
        pageRepository.deleteByPageId(pageId);
    }

    public SduiPageEntity requireByPageId(String pageId) {
        return pageRepository.findByPageId(pageId)
                .orElseThrow(() -> new IllegalArgumentException("page not found: " + pageId));
    }

    public Optional<SduiPageEntity> findByPageId(String pageId) {
        return pageRepository.findByPageId(pageId);
    }

    /** Build a SectionPageDefinition from a persisted PageEntity. */
    public SectionPageDefinition toPageDefinition(SduiPageEntity entity) {
        Map<String, SectionPageDefinition.SectionDef> sectionDefs = new LinkedHashMap<>();
        List<Map<String, Object>> sections = parseSections(entity.getSectionsJson());
        if (sections != null) {
            for (Map<String, Object> sec : sections) {
                String sectionId = string(sec.get("sectionId"));
                String sectionType = string(sec.get("sectionType"));
                if (sectionId.isBlank() || sectionType.isBlank()) continue;
                @SuppressWarnings("unchecked")
                Map<String, Object> fields = (Map<String, Object>) sec.getOrDefault("fields", Map.of());
                sectionDefs.put(sectionId, new SectionPageDefinition.SectionDef(sectionId, sectionType, fields));
            }
        }
        SectionLayout sectionLayout = SectionLayout.fromWireName(entity.getLayout());
        return new SectionPageDefinition(entity.getPageId(), sectionLayout,
                entity.isAutoScroll(), entity.getAutoScrollMs(), new LinkedHashMap<>(sectionDefs));
    }

    // ── JSON helpers ──

    private String toSectionsJson(List<Map<String, Object>> sections) {
        if (sections == null || sections.isEmpty()) return "[]";
        try {
            return objectMapper.writeValueAsString(sections);
        } catch (Exception e) {
            throw new IllegalArgumentException("failed to serialize sections: " + e.getMessage(), e);
        }
    }

    private List<Map<String, Object>> parseSections(String json) {
        if (json == null || json.isBlank()) return List.of();
        try {
            return objectMapper.readValue(json, new TypeReference<List<Map<String, Object>>>() {});
        } catch (Exception e) {
            log.warn("failed to parse sections json: {}", e.getMessage());
            return List.of();
        }
    }

    private String string(Object value) {
        return value == null ? "" : String.valueOf(value);
    }
}
