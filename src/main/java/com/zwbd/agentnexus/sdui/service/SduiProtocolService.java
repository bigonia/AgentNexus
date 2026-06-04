package com.zwbd.agentnexus.sdui.service;

import com.zwbd.agentnexus.sdui.DeviceSessionManager;
import com.zwbd.agentnexus.sdui.protocol.BinaryProtocolCodec;
import com.zwbd.agentnexus.sdui.protocol.ProtocolMapper;
import java.util.concurrent.atomic.AtomicInteger;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

@Slf4j
@Service
@RequiredArgsConstructor
public class SduiProtocolService {

    private final DeviceSessionManager sessionManager;
    private final ProtocolMapper protocolMapper;
    private final AtomicInteger audioSeq = new AtomicInteger(0);

    public boolean sendSectionScene(String deviceId, String sceneJson) {
        ProtocolMapper.MappedBinaryMessage msg = protocolMapper.mapSectionScene(deviceId, sceneJson);
        log.info("Sending section scene to device={}, frameSize={}", deviceId, msg.frame().length);
        return sessionManager.sendBinaryFrame(deviceId, msg.frame());
    }

    public boolean sendSectionPatch(String deviceId, String patchJson) {
        ProtocolMapper.MappedBinaryMessage msg = protocolMapper.mapSectionPatch(deviceId, patchJson);
        log.info("Sending section patch to device={}, frameSize={}", deviceId, msg.frame().length);
        return sessionManager.sendBinaryFrame(deviceId, msg.frame());
    }

    public boolean sendControlCommand(String deviceId, String topic, String paramsJson) {
        String message = "{\"topic\":\"" + topic + "\",\"payload\":" + paramsJson + "}";
        log.info("Sending control command to device={}: {}", deviceId, message);
        return sessionManager.sendMessage(deviceId, message);
    }

    /**
     * Send a raw string payload to a topic (payload is JSON-string-escaped).
     */
    public boolean sendRawPayload(String deviceId, String topic, String rawPayload) {
        String escaped = rawPayload
                .replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "\\r");
        String message = "{\"topic\":\"" + topic + "\",\"payload\":\"" + escaped + "\"}";
        log.info("Sending raw payload to device={} topic={} len={}", deviceId, topic, rawPayload.length());
        return sessionManager.sendMessage(deviceId, message);
    }

    /**
     * Send raw PCM audio as a binary frame (msgType=17, no JSON/base64 overhead).
     */
    public boolean sendAudioPcm(String deviceId, byte[] pcm) {
        byte[] frame = BinaryProtocolCodec.encode(
                BinaryProtocolCodec.MSG_TYPE_AUDIO_PCM, audioSeq.incrementAndGet(), pcm);
        log.info("Sending audio PCM to device={}, samples={}, frameSize={}",
                deviceId, pcm.length / 2, frame.length);
        return sessionManager.sendBinaryFrame(deviceId, frame);
    }
}
