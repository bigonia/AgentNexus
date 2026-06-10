package com.zwbd.agentnexus.sdui.service;

import com.zwbd.agentnexus.sdui.repo.SduiDeviceTelemetryRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

@Slf4j
@Service
@RequiredArgsConstructor
public class TelemetryRetentionService {

    private final SduiDeviceTelemetryRepository telemetryRepository;

    @Value("${sdui.telemetry.retention-days:7}")
    private int retentionDays;

    @Transactional
    @Scheduled(cron = "${sdui.telemetry.retention-cron:0 15 3 * * *}")
    public void purgeExpiredTelemetry() {
        LocalDateTime cutoff = LocalDateTime.now().minusDays(Math.max(retentionDays, 1));
        long deleted = telemetryRepository.deleteByCreatedAtBefore(cutoff);
        if (deleted > 0) {
            log.info("Purged {} telemetry rows older than {}", deleted, cutoff);
        }
    }
}
