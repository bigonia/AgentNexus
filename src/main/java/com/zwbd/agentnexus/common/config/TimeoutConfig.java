package com.zwbd.agentnexus.common.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * Centralized timeout configuration for database operations.
 * Provides configurable timeout values for different types of database operations.
 * 
 * @Author: wnli
 * @Date: 2025/9/19 16:55
 * @Desc: Timeout configuration management
 */
@Data
@Configuration
@ConfigurationProperties(prefix = "app.database.timeout")
public class TimeoutConfig {

    /**
     * Connection timeout in seconds for establishing database connections
     */
    private int connectionTimeout = 30;

    /**
     * Socket timeout in seconds for network operations
     */
    private int socketTimeout = 60;

    /**
     * Query timeout in seconds for connection test operations
     */
    private int connectionTestTimeout = 10;

    /**
     * Query timeout in seconds for table metadata queries
     */
    private int metadataQueryTimeout = 60;

    /**
     * Query timeout in seconds for metrics calculation queries
     */
    private int metricsCalculationTimeout = 120;

    /**
     * Overall task timeout in minutes for metadata collection tasks
     */
    private int taskTimeoutMinutes = 60;

    /**
     * Maximum concurrent tasks for parallel processing
     */
    private int maxConcurrentTasks = 5;

}