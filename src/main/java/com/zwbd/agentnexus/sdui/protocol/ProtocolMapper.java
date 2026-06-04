package com.zwbd.agentnexus.sdui.protocol;

import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicInteger;
import org.springframework.stereotype.Component;

@Component
public class ProtocolMapper {

    private final AtomicInteger seq = new AtomicInteger(0);

    public MappedBinaryMessage mapSectionScene(String deviceId, String sceneJson) {
        byte[] payload = sceneJson.getBytes(StandardCharsets.UTF_8);
        byte[] frame = BinaryProtocolCodec.encode(
                BinaryProtocolCodec.MSG_TYPE_SECTION_SCENE, seq.incrementAndGet(), payload);
        return new MappedBinaryMessage(deviceId, frame);
    }

    public MappedBinaryMessage mapSectionPatch(String deviceId, String patchJson) {
        byte[] payload = patchJson.getBytes(StandardCharsets.UTF_8);
        byte[] frame = BinaryProtocolCodec.encode(
                BinaryProtocolCodec.MSG_TYPE_SECTION_PATCH, seq.incrementAndGet(), payload);
        return new MappedBinaryMessage(deviceId, frame);
    }

    public record MappedBinaryMessage(String deviceId, byte[] frame) {}
}
