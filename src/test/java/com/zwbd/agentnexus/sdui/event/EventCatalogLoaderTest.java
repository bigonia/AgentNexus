package com.zwbd.agentnexus.sdui.event;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EventCatalogLoaderTest {

    @Test
    void loadsCommandAndSectionEventsFromConfiguration() {
        EventCatalogLoader loader = new EventCatalogLoader();
        loader.load();

        assertTrue(loader.validationErrors().isEmpty());
        assertTrue(loader.commandEvents().stream().anyMatch(event -> "rgb.effect.set".equals(event.eventId())));
        assertTrue(loader.commandEvents().stream().anyMatch(event -> "command.ack".equals(event.eventId())));
        assertTrue(loader.sectionEvents().stream().anyMatch(event -> "ui:action.click".equals(event.eventId())));
        assertFalse(loader.sectionTypes().isEmpty());
    }
}
