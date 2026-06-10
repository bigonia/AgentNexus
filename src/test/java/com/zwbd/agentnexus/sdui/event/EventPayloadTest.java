package com.zwbd.agentnexus.sdui.event;

import com.zwbd.agentnexus.sdui.protocol.BinaryProtocolCodec;
import com.zwbd.agentnexus.sdui.protocol.TlvBuilder;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class EventPayloadTest {

    @Test
    void normalizesShortPressButtonEventToNamespacedWorkflowEvent() {
        TlvBuilder tlv = new TlvBuilder();
        tlv.addU8(BinaryProtocolCodec.TLV_EVENT_KIND, 4);
        tlv.addString(BinaryProtocolCodec.TLV_NODE_ID, "pwr");
        tlv.addString(BinaryProtocolCodec.TLV_EVENT_NAME, "short_press");
        tlv.addU32(BinaryProtocolCodec.TLV_TS, 896567L);

        byte[] frame = BinaryProtocolCodec.encode(9, 1, tlv.build());
        var decoded = BinaryProtocolCodec.decode(frame);

        EventPayload payload = EventPayload.fromBinaryInput("1051DB398BD0", decoded);

        assertEquals("input:buttons.pwr.single_click", payload.eventId());
        assertEquals("pwr", payload.nodeId());
        assertEquals("short_press", payload.rawFields().get("eventName"));
    }

    @Test
    void keepsDifferentButtonNodeIdsDistinct() {
        TlvBuilder tlv = new TlvBuilder();
        tlv.addU8(BinaryProtocolCodec.TLV_EVENT_KIND, 4);
        tlv.addString(BinaryProtocolCodec.TLV_NODE_ID, "plus");
        tlv.addString(BinaryProtocolCodec.TLV_EVENT_NAME, "short_press");

        byte[] frame = BinaryProtocolCodec.encode(9, 2, tlv.build());
        var decoded = BinaryProtocolCodec.decode(frame);

        EventPayload payload = EventPayload.fromBinaryInput("1051DB398BD0", decoded);

        assertEquals("input:buttons.plus.single_click", payload.eventId());
    }

    @Test
    void normalizesLegacyBootNodeIdToPwr() {
        TlvBuilder tlv = new TlvBuilder();
        tlv.addU8(BinaryProtocolCodec.TLV_EVENT_KIND, 4);
        tlv.addString(BinaryProtocolCodec.TLV_NODE_ID, "boot");
        tlv.addString(BinaryProtocolCodec.TLV_EVENT_NAME, "short_press");

        byte[] frame = BinaryProtocolCodec.encode(9, 3, tlv.build());
        var decoded = BinaryProtocolCodec.decode(frame);

        EventPayload payload = EventPayload.fromBinaryInput("1051DB398BD0", decoded);

        assertEquals("input:buttons.pwr.single_click", payload.eventId());
    }
}
