package com.zwbd.agentnexus.sdui.section;

import java.util.List;

public record SectionPatch(
        String pageId,
        List<PatchEntry> patches,
        SectionRenderMode renderMode
) {
    /** Convenience constructor: no explicit render mode (defaults to RICH at serialization time). */
    public SectionPatch(String pageId, List<PatchEntry> patches) {
        this(pageId, patches, null);
    }

    /** Effective render mode, defaulting to RICH when not set. */
    public SectionRenderMode effectiveRenderMode() {
        return renderMode != null ? renderMode : SectionRenderMode.RICH;
    }

    public record PatchEntry(String sectionId, String op, String type, SectionData data) {}
}
