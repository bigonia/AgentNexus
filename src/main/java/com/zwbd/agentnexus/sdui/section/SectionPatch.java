package com.zwbd.agentnexus.sdui.section;

import java.util.List;

public record SectionPatch(
        String pageId,
        List<PatchEntry> patches
) {
    public record PatchEntry(String sectionId, String op, SectionData data) {}
}
