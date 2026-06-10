package com.zwbd.agentnexus.sdui.workflow;

import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Component
public class WorkflowDefinitionNormalizer {

    private static final String PAGE_ID_PREFIX = "workflow_page_";
    private static final String SECTION_ID_PREFIX = "workflow_section_";

    public WorkflowDefinition normalize(WorkflowDefinition definition) {
        if (definition == null) {
            return null;
        }

        List<PageDef> normalizedPages = normalizePages(definition.pages());
        return new WorkflowDefinition(
                definition.id(),
                definition.name(),
                definition.icon(),
                normalizedPages,
                definition.triggers(),
                definition.actions(),
                definition.edges(),
                definition.virtualInputs(),
                definition.virtualOutputs()
        );
    }

    private List<PageDef> normalizePages(List<PageDef> pages) {
        if (pages == null || pages.isEmpty()) {
            return pages;
        }

        List<PageDef> normalized = new ArrayList<>();
        Set<String> usedPageIds = new LinkedHashSet<>();
        for (int pageIndex = 0; pageIndex < pages.size(); pageIndex++) {
            PageDef page = pages.get(pageIndex);
            String pageId = normalizeId(page != null ? page.id() : null, PAGE_ID_PREFIX + (pageIndex + 1), usedPageIds);
            List<SectionBindDef> sections = page != null ? normalizeSections(page.sections(), pageId) : List.of();
            normalized.add(new PageDef(
                    pageId,
                    page != null ? page.layout() : null,
                    page != null && page.autoScroll(),
                    page != null ? page.autoScrollMs() : 0,
                    sections
            ));
        }
        return normalized;
    }

    private List<SectionBindDef> normalizeSections(List<SectionBindDef> sections, String pageId) {
        if (sections == null || sections.isEmpty()) {
            return sections;
        }

        List<SectionBindDef> normalized = new ArrayList<>();
        Set<String> usedSectionIds = new LinkedHashSet<>();
        for (int sectionIndex = 0; sectionIndex < sections.size(); sectionIndex++) {
            SectionBindDef section = sections.get(sectionIndex);
            String sectionId = normalizeId(
                    section != null ? section.id() : null,
                    SECTION_ID_PREFIX + pageId + "_" + (sectionIndex + 1),
                    usedSectionIds
            );
            normalized.add(new SectionBindDef(
                    sectionId,
                    section != null ? section.type() : null,
                    section != null && section.bind() != null ? new LinkedHashMap<>(section.bind()) : Map.of()
            ));
        }
        return normalized;
    }

    private String normalizeId(String candidate, String fallbackBase, Set<String> usedIds) {
        String value = candidate != null ? candidate.trim() : "";
        if (value.isBlank()) {
            value = fallbackBase;
        }

        String unique = value;
        int suffix = 2;
        while (!usedIds.add(unique)) {
            unique = value + "_" + suffix++;
        }
        return unique;
    }
}
