package com.zwbd.agentnexus.sdui.event;

import com.zwbd.agentnexus.sdui.protocol.BinaryProtocolCodec;
import com.zwbd.agentnexus.sdui.protocol.TlvBuilder;
import com.zwbd.agentnexus.sdui.section.SectionDataCodec;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EventPayloadTest {

    @Test
    void binaryPayloadKeepsRawEventNameBeforeRegistryNormalization() {
        TlvBuilder tlv = new TlvBuilder();
        tlv.addU8(BinaryProtocolCodec.TLV_EVENT_KIND, 4);
        tlv.addString(BinaryProtocolCodec.TLV_NODE_ID, "pwr");
        tlv.addString(BinaryProtocolCodec.TLV_EVENT_NAME, "short_press");
        tlv.addU32(BinaryProtocolCodec.TLV_TS, 896567L);

        byte[] frame = BinaryProtocolCodec.encode(9, 1, tlv.build());
        var decoded = BinaryProtocolCodec.decode(frame);

        EventPayload payload = EventPayload.fromBinaryInput("1051DB398BD0", decoded);

        assertEquals("short_press", payload.eventId());
        assertEquals("pwr", payload.nodeId());
        assertEquals("short_press", payload.rawFields().get("eventName"));
    }

    @Test
    void registryNormalizesConfiguredSectionInteractionEvent() {
        TlvBuilder tlv = new TlvBuilder();
        tlv.addU8(BinaryProtocolCodec.TLV_EVENT_KIND, 1);
        tlv.addString(BinaryProtocolCodec.TLV_EVENT_NAME, "action.click");
        tlv.addString(BinaryProtocolCodec.TLV_SECTION_ID, "actions_1");
        tlv.addString(BinaryProtocolCodec.TLV_PAGE_ID, "main");

        byte[] frame = BinaryProtocolCodec.encode(9, 2, tlv.build());
        var decoded = BinaryProtocolCodec.decode(frame);

        EventRegistry registry = registry();
        EventPayload payload = registry.normalizePayload(EventPayload.fromBinaryInput("device-1", decoded));

        assertEquals("ui:action.click", payload.eventId());
        assertEquals("actions_1", payload.sectionId());
        assertEquals("main", payload.pageId());
    }

    @Test
    void validatesConfiguredPayloadConstraints() {
        EventRegistry registry = registry();

        EventRegistry.ValidationResult invalid = registry.validateCommand("rgb.effect.set", Map.of(
                "r", 300,
                "g", 0,
                "b", 0
        ));
        EventRegistry.ValidationResult valid = registry.validateCommand("rgb.effect.set", Map.of(
                "r", 255,
                "g", 0,
                "b", 0
        ));

        assertTrue(invalid.errors().stream().anyMatch(error -> error.message().contains("<= 255")));
        assertTrue(invalid.errors().stream().anyMatch(error -> "VALUE_OUT_OF_RANGE".equals(error.code())));
        assertTrue(valid.valid());
    }

    private EventRegistry registry() {
        EventCatalogLoader loader = new EventCatalogLoader(new SectionDataCodec());
        loader.load();
        return new EventRegistry(loader);
    }
}
