package com.zwbd.agentnexus.sdui.service;

import com.zwbd.agentnexus.sdui.DeviceSessionManager;
import com.zwbd.agentnexus.sdui.protocol.ProtocolMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

@Slf4j
@Service
@RequiredArgsConstructor
public class SduiProtocolService {

    private final DeviceSessionManager sessionManager;
    private final ProtocolMapper protocolMapper;

    public boolean sendSectionScene(String deviceId, String sceneJson) {
        ProtocolMapper.MappedBinaryMessage msg = protocolMapper.mapSectionScene(deviceId, sceneJson);
        return sessionManager.sendBinaryFrame(deviceId, msg.frame());
    }

    public boolean sendSectionPatch(String deviceId, String patchJson) {
        ProtocolMapper.MappedBinaryMessage msg = protocolMapper.mapSectionPatch(deviceId, patchJson);
        return sessionManager.sendBinaryFrame(deviceId, msg.frame());
    }

    public boolean sendControlCommand(String deviceId, String topic, String paramsJson) {
        String message = "{\"topic\":\"" + topic + "\",\"payload\":" + paramsJson + "}";
        return sessionManager.sendMessage(deviceId, message);
    }
}
