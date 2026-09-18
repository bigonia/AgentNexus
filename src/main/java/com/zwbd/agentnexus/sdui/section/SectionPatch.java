package com.zwbd.agentnexus.sdui.section;

import java.util.List;

// LCD_085 refactor (2026-09-18): legacy protocol path, scheduled for removal.
// Replaced by: display.section 全量替换
// Kept only so un-migrated devices keep working; delete once the terminal rolls over to v2.
// See docs/sdui/lcd085-refactor/2026-09-18/10_PLATFORM_UPGRADE.md section 10.
@Deprecated(since = "0.10.0")
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
