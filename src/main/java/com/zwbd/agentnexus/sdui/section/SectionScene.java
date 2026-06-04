package com.zwbd.agentnexus.sdui.section;

import java.util.List;

public record SectionScene(
        String pageId,
        SectionLayout layout,
        boolean autoScroll,
        int autoScrollMs,
        List<SectionEntry> sections,
        SectionRenderMode renderMode
) {
    /** Convenience constructor: no explicit render mode (defaults to RICH at serialization time). */
    public SectionScene(String pageId, SectionLayout layout, boolean autoScroll,
                        int autoScrollMs, List<SectionEntry> sections) {
        this(pageId, layout, autoScroll, autoScrollMs, sections, null);
    }

    /** Effective render mode, defaulting to RICH when not set. */
    public SectionRenderMode effectiveRenderMode() {
        return renderMode != null ? renderMode : SectionRenderMode.RICH;
    }
}
