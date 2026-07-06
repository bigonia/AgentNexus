package com.zwbd.agentnexus.sdui.section;

import java.util.List;
import java.util.Map;

/**
 * Three-level tree exposing interactive elements inside page sections
 * for wiring section.trigger workflow nodes.
 *
 * <pre>
 *   Page
 *   └── Section (e.g. sec_2_action_section, toggle1)
 *       └── Element (e.g. btn_confirm, option_a)
 *           └── Event (e.g. action.click, toggle.change)
 * </pre>
 *
 * The frontend uses this catalog to populate the configuration panel
 * after a user selects a section.trigger node and binds a UI template.
 */
public record SectionTriggerCatalog(
        String pageId,
        String deviceId,
        String board,
        boolean online,
        List<SectionEntry> sections
) {

    /** A single interactive section on the page. */
    public record SectionEntry(
            String sectionId,
            String sectionType,
            String sectionDisplayName,
            /** Whether this section has child interactive elements (buttons, toggles, etc.). */
            boolean hasChildElements,
            /** The child interactive elements, each with its supported events. */
            List<ElementEntry> elements
    ) {}

    /** One interactive child element (button, toggle option, list item, nav tab, overlay). */
    public record ElementEntry(
            /** Instance-defined ID from the section fields (e.g. "btn_confirm", "option_a"). */
            String elementId,
            /** Instance-defined display label (e.g. "确认", "选项A"). */
            String elementLabel,
            /**
             * Element kind for frontend icon / colour selection:
             * {@code button | toggle_option | list_item | nav_tab | overlay}
             */
            String elementType,
            /** Remaining instance fields as-is (tone, enabled, active, …). */
            Map<String, Object> elementMeta,
            /** Interaction events this element can fire. */
            List<EventEntry> events
    ) {}

    /** One interaction event that an element supports. */
    public record EventEntry(
            /** Short event ID (e.g. "action.click"). */
            String eventId,
            /** Namespaced event ID (e.g. "ui:action.click"). */
            String namespacedEventId,
            /** Human-readable description. */
            String displayName,
            /** Longer help text. */
            String description
    ) {}
}
