package com.zwbd.agentnexus.sdui.section;

import java.util.List;

public record SectionEntry(
        SectionType type,
        String sectionId,
        SectionData data
) {}
