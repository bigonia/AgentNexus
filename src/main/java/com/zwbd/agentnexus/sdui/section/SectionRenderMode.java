package com.zwbd.agentnexus.sdui.section;

/**
 * Section rendering mode determined by device screen size.
 *
 * <p>Mirrors the dual-mode rendering model defined in SECTION_SCHEMA.md §2:
 * <ul>
 *   <li>{@link #RICH} — large screen (≥160px width), full fields, generous limits</li>
 *   <li>{@link #COMPACT} — small screen (&lt;160px), reduced fields, compact layout</li>
 * </ul>
 */
public enum SectionRenderMode {

    RICH,
    COMPACT;

    /**
     * Resolve render mode from the device-reported size class.
     * {@code "small"} maps to COMPACT; everything else (including null) defaults to RICH.
     */
    public static SectionRenderMode fromSizeClass(String sizeClass) {
        if (sizeClass == null) return RICH;
        return "small".equalsIgnoreCase(sizeClass) ? COMPACT : RICH;
    }

    /**
     * Resolve render mode from screen pixel width.
     * width &lt; 160 → COMPACT, width ≥ 160 → RICH (per §2.3).
     */
    public static SectionRenderMode fromScreenWidth(int widthPx) {
        return widthPx < 160 ? COMPACT : RICH;
    }
}
