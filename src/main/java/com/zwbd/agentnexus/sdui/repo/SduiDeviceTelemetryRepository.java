package com.zwbd.agentnexus.sdui.repo;

import com.zwbd.agentnexus.sdui.model.SduiDeviceTelemetry;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.List;

public interface SduiDeviceTelemetryRepository extends JpaRepository<SduiDeviceTelemetry, Long> {
    List<SduiDeviceTelemetry> findTop50ByDeviceIdOrderByCreatedAtDesc(String deviceId);


    List<SduiDeviceTelemetry> findByDeviceIdAndCreatedAtBetweenOrderByCreatedAtAsc(
            String deviceId, LocalDateTime start, LocalDateTime end);

    SduiDeviceTelemetry findFirstByDeviceIdOrderByCreatedAtDesc(String deviceId);

    void deleteByDeviceId(String deviceId);

    /** Hourly bucket aggregation returning (bucket, count, avgWifiRssi, avgTemperature,
     *  avgFreeHeapInternal, avgFreeHeapTotal, avgFragInternalPct, avgBatteryPct). */
    @Query(value = """
        SELECT
            date_trunc('hour', created_at) AS bucket,
            COUNT(*) AS cnt,
            AVG(wifi_rssi) AS avg_rssi,
            AVG(temperature) AS avg_temp,
            AVG(free_heap_internal) AS avg_heap_int,
            AVG(free_heap_total) AS avg_heap_total,
            AVG(COALESCE(frag_internal_pct, 0)) AS avg_frag_int,
            AVG(COALESCE(frag_dma_pct, 0)) AS avg_frag_dma,
            AVG(COALESCE(battery_pct, 0)) AS avg_bat
        FROM sdui_device_telemetry
        WHERE device_id = :deviceId AND created_at BETWEEN :start AND :end
        GROUP BY bucket ORDER BY bucket ASC""", nativeQuery = true)
    List<Object[]> aggregateHourly(@Param("deviceId") String deviceId,
                                    @Param("start") LocalDateTime start,
                                    @Param("end") LocalDateTime end);

    /** Custom-bucket aggregation using epoch truncation.
     *  bucketSeconds: 60=1min, 300=5min, 600=10min, 3600=1h, 21600=6h */
    @Query(value = """
        SELECT
            to_timestamp(EXTRACT(EPOCH FROM created_at)::BIGINT / :bucketSec * :bucketSec) AS bucket,
            COUNT(*) AS cnt,
            AVG(wifi_rssi) AS avg_rssi,
            MIN(wifi_rssi) AS min_rssi,
            MAX(wifi_rssi) AS max_rssi,
            AVG(temperature) AS avg_temp,
            MIN(temperature) AS min_temp,
            MAX(temperature) AS max_temp,
            AVG(free_heap_internal) AS avg_heap_int,
            MIN(free_heap_internal) AS min_heap_int,
            MAX(free_heap_internal) AS max_heap_int,
            AVG(free_heap_total) AS avg_heap_total,
            AVG(COALESCE(free_heap_dma, 0)) AS avg_heap_dma,
            AVG(COALESCE(frag_internal_pct, 0)) AS avg_frag_int,
            MIN(COALESCE(frag_internal_pct, 0)) AS min_frag_int,
            MAX(COALESCE(frag_internal_pct, 0)) AS max_frag_int,
            AVG(COALESCE(frag_dma_pct, 0)) AS avg_frag_dma,
            AVG(COALESCE(battery_pct, 0)) AS avg_bat,
            MIN(COALESCE(battery_pct, 0)) AS min_bat,
            MAX(COALESCE(battery_pct, 0)) AS max_bat,
            AVG(COALESCE(battery_mv, 0)) AS avg_bat_mv
        FROM sdui_device_telemetry
        WHERE device_id = :deviceId AND created_at BETWEEN :start AND :end
        GROUP BY bucket ORDER BY bucket ASC""", nativeQuery = true)
    List<Object[]> aggregateByBucket(@Param("deviceId") String deviceId,
                                      @Param("start") LocalDateTime start,
                                      @Param("end") LocalDateTime end,
                                      @Param("bucketSec") int bucketSeconds);
}

