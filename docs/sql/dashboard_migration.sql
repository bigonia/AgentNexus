-- ============================================================================
-- Device Dashboard API: Database Migration
-- Extends telemetry, adds connection tracking, adds connection log table
-- Run against PostgreSQL 15+ with pgvector
-- ============================================================================

-- 1. Extend sdui_device_telemetry with all heartbeat fields
ALTER TABLE sdui_device_telemetry
  ADD COLUMN IF NOT EXISTS ip VARCHAR(45),
  ADD COLUMN IF NOT EXISTS largest_heap_internal INT,
  ADD COLUMN IF NOT EXISTS free_heap_dma INT,
  ADD COLUMN IF NOT EXISTS largest_heap_dma INT,
  ADD COLUMN IF NOT EXISTS free_heap_psram INT,
  ADD COLUMN IF NOT EXISTS largest_heap_psram INT,
  ADD COLUMN IF NOT EXISTS frag_internal_pct INT,
  ADD COLUMN IF NOT EXISTS frag_dma_pct INT,
  ADD COLUMN IF NOT EXISTS frag_psram_pct INT,
  ADD COLUMN IF NOT EXISTS power_supported BOOLEAN,
  ADD COLUMN IF NOT EXISTS battery_mv INT,
  ADD COLUMN IF NOT EXISTS battery_pct INT,
  ADD COLUMN IF NOT EXISTS charging BOOLEAN,
  ADD COLUMN IF NOT EXISTS ext_power_present BOOLEAN,
  ADD COLUMN IF NOT EXISTS ext_power_ctrl BOOLEAN,
  ADD COLUMN IF NOT EXISTS ext_power_on BOOLEAN;

CREATE INDEX IF NOT EXISTS idx_tel_device_created_at
  ON sdui_device_telemetry(device_id, created_at DESC);

CREATE INDEX IF NOT EXISTS idx_tel_created_at
  ON sdui_device_telemetry(created_at);

-- 2. Extend sdui_device with connection tracking fields
ALTER TABLE sdui_device
  ADD COLUMN IF NOT EXISTS connected_at TIMESTAMP,
  ADD COLUMN IF NOT EXISTS session_id VARCHAR(128),
  ADD COLUMN IF NOT EXISTS connection_count INT DEFAULT 0,
  ADD COLUMN IF NOT EXISTS total_uptime_s BIGINT DEFAULT 0;

-- 3. Create connection log table
CREATE TABLE IF NOT EXISTS sdui_device_connection_log (
  id BIGSERIAL PRIMARY KEY,
  space_id VARCHAR(64) NOT NULL DEFAULT '',
  device_id VARCHAR(64) NOT NULL,
  event_type VARCHAR(16) NOT NULL,
  session_id VARCHAR(128),
  ip_address VARCHAR(45),
  disconnect_reason VARCHAR(64),
  event_at TIMESTAMP NOT NULL,
  created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX IF NOT EXISTS idx_conn_log_device
  ON sdui_device_connection_log(device_id, event_at DESC);

CREATE INDEX IF NOT EXISTS idx_conn_log_space
  ON sdui_device_connection_log(space_id);
