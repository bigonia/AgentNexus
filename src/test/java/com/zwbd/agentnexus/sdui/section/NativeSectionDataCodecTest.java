package com.zwbd.agentnexus.sdui.section;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class NativeSectionDataCodecTest {

    private SectionDataCodec codec() {
        SectionTypeCatalog catalog = mock(SectionTypeCatalog.class);
        when(catalog.get("dashboard_section")).thenReturn(Optional.empty());
        when(catalog.get("speak_section")).thenReturn(Optional.empty());
        return new SectionDataCodec(catalog);
    }

    @Test
    void dashboardRoundTrips() {
        SectionData data = codec().buildSectionData("dashboard_section", Map.of(
                "title", "Operations",
                "primaryMetricId", "health",
                "metrics", List.of(Map.of(
                        "id", "health", "label", "Health", "value", "82",
                        "unit", "%", "tone", "success", "detail", "Stable", "progress", 82)),
                "alerts", List.of(Map.of(
                        "id", "a1", "title", "CPU hot", "level", "warning",
                        "time", "10:30", "acknowledged", false))
        ), "dash");

        assertInstanceOf(SectionData.DashboardData.class, data);
        Map<String, Object> encoded = codec().toFieldMap(data);
        assertEquals("Operations", encoded.get("title"));
        assertEquals(1, ((List<?>) encoded.get("metrics")).size());
        assertEquals(1, ((List<?>) encoded.get("alerts")).size());
    }

    @Test
    void speakRoundTrips() {
        SectionData data = codec().buildSectionData("speak_section", Map.of(
                "title", "Ask", "hint", "Hold", "sessionId", "s1",
                "maxDurationMs", 15000, "status", "result", "transcript", "hello"
        ), "speak");

        assertInstanceOf(SectionData.SpeakData.class, data);
        Map<String, Object> encoded = codec().toFieldMap(data);
        assertEquals("s1", encoded.get("sessionId"));
        assertEquals("hello", encoded.get("transcript"));
    }
}
