package com.zwbd.agentnexus.sdui.section;

import java.util.List;

// 保留（修正 2026-09-18）：本类不是旧协议路径，是 v2 单 Section 收敛点的输入模型之一。
// v2.display.PrimaryViewPublisher / SectionViewResolver 用它承载平台内部既有的类型化补丁，
// 再由 SectionViewResolver 合成为完整 Section，以 display.section 下发。
// v2 协议层面确实没有 Patch（§4.17），要移除的是"把 Patch 直接下发给设备"的旧路径，不是本类。
// 详见 docs/sdui/lcd085-refactor/2026-09-18/12_DESIGN_NOTES.md §4.17 / §4.18 裁决二。
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
