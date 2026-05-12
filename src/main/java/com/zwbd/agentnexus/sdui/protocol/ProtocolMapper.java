package com.zwbd.agentnexus.sdui.protocol;

import java.util.concurrent.atomic.AtomicInteger;
import org.springframework.stereotype.Component;

@Component
public class ProtocolMapper {

    private final AtomicInteger seq = new AtomicInteger(0);

    public MappedBinaryMessage mapSectionScene(String deviceId, String sceneJson) {
        TlvBuilder tlv = new TlvBuilder()
                .addString(31, "section")
                .addJson(32, sceneJson);
        return encode(15, deviceId, tlv);
    }

    public MappedBinaryMessage mapSectionPatch(String deviceId, String patchJson) {
        TlvBuilder tlv = new TlvBuilder()
                .addString(31, "section")
                .addJson(32, patchJson);
        return encode(16, deviceId, tlv);
    }

    public MappedBinaryMessage mapTemplateScene(String deviceId, String templateId, String payloadJson) {
        TlvBuilder tlv = new TlvBuilder()
                .addString(31, templateId)
                .addJson(32, payloadJson);
        return encode(13, deviceId, tlv);
    }

    public MappedBinaryMessage mapTemplatePatch(String deviceId, String templateId, String patchJson) {
        TlvBuilder tlv = new TlvBuilder()
                .addString(31, templateId)
                .addJson(32, patchJson);
        return encode(14, deviceId, tlv);
    }

    public MappedBinaryMessage mapActuatorCmd(String deviceId, String action, String paramsJson) {
        TlvBuilder tlv = new TlvBuilder()
                .addString(31, action)
                .addJson(32, paramsJson);
        return encode(12, deviceId, tlv);
    }

    private MappedBinaryMessage encode(int msgType, String deviceId, TlvBuilder tlv) {
        byte[] frame = BinaryProtocolCodec.encode(msgType, seq.incrementAndGet(), tlv.build());
        return new MappedBinaryMessage(deviceId, frame);
    }

    public record MappedBinaryMessage(String deviceId, byte[] frame) {}
}
