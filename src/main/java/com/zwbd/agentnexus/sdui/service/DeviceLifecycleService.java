package com.zwbd.agentnexus.sdui.service;

import com.zwbd.agentnexus.sdui.model.SduiDevice;
import com.zwbd.agentnexus.sdui.model.SduiDeviceTelemetry;
import com.zwbd.agentnexus.sdui.protocol.BinaryProtocolCodec.DecodedFrame;
import com.zwbd.agentnexus.sdui.repo.SduiDeviceRepository;
import com.zwbd.agentnexus.sdui.repo.SduiDeviceTelemetryRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

@Slf4j
@Service
@RequiredArgsConstructor
public class DeviceLifecycleService {

    private final SduiDeviceRepository deviceRepository;
    private final SduiDeviceTelemetryRepository telemetryRepository;

    @Transactional
    public void onHello(String deviceId, DecodedFrame frame) {
        SduiDevice device = deviceRepository.findById(deviceId).orElseGet(() -> {
            SduiDevice d = new SduiDevice();
            d.setDeviceId(deviceId);
            d.setName("device-" + deviceId);
            d.setOwnerSpaceId("");
            d.setRegistrationStatus("UNCLAIMED");
            return d;
        });
        device.setStatus("ONLINE");
        device.setLastSeenAt(LocalDateTime.now());
        deviceRepository.save(device);
    }
}
