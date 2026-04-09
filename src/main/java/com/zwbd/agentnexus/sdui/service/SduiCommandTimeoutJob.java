package com.zwbd.agentnexus.sdui.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class SduiCommandTimeoutJob {

    private final SduiDeviceService deviceService;

    @Scheduled(fixedDelayString = "${sdui.command.timeout-scan-ms:3000}")
    public void scanTimeoutCommands() {
        int timedOut = deviceService.markTimedOutCommands();
        if (timedOut > 0) {
            log.info("Marked {} command(s) as TIMEOUT", timedOut);
        }
    }
}

