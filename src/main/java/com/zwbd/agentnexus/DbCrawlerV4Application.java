package com.zwbd.agentnexus;

import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.core.env.Environment;
import org.springframework.data.jpa.repository.config.EnableJpaAuditing;
import org.springframework.scheduling.annotation.EnableAsync;

import java.net.InetAddress;
import java.net.UnknownHostException;

@Slf4j
@SpringBootApplication
@EnableJpaAuditing
@EnableAsync
public class  DbCrawlerV4Application {

    public static void main(String[] args) {
        ConfigurableApplicationContext context = SpringApplication.run(DbCrawlerV4Application.class, args);
        logApplicationStartup(context.getEnvironment());
    }

    /**
     * Log application startup information
     */
    private static void logApplicationStartup(Environment env) {
        String protocol = "http";
        if (env.getProperty("server.ssl.key-store") != null) {
            protocol = "https";
        }

        String serverPort = env.getProperty("server.port", "8080");
        String contextPath = env.getProperty("server.servlet.context-path", "");
        String hostAddress = "localhost";

        try {
            hostAddress = InetAddress.getLocalHost().getHostAddress();
        } catch (UnknownHostException e) {
            log.warn("The host name could not be determined, using `localhost` as fallback");
        }

        String applicationName = env.getProperty("spring.application.name", "Data Profiler");
        String activeProfiles = String.join(", ", env.getActiveProfiles());
        if (activeProfiles.isEmpty()) {
            activeProfiles = "default";
        }

        log.info("\n----------------------------------------------------------\n\t" +
                        "Application '{}' is running! Access URLs:\n\t" +
                        "Local: \t\t{}://localhost:{}{}\n\t" +
                        "External: \t{}://{}:{}{}\n\t" +
                        "Profile(s): \t{}\n\t" +
                        "API Docs: \t{}://localhost:{}{}/swagger-ui.html\n" +
                        "----------------------------------------------------------",
                applicationName,
                protocol, serverPort, contextPath,
                protocol, hostAddress, serverPort, contextPath,
                activeProfiles,
                protocol, serverPort, contextPath);

        // Log important configuration
        logConfiguration(env);
    }

    /**
     * Log important application configuration
     */
    private static void logConfiguration(Environment env) {
        log.info("Configuration Summary:");

        // Database configuration
        String datasourceUrl = env.getProperty("spring.datasource.url", "Not configured");
        String datasourceDriver = env.getProperty("spring.datasource.driver-class-name", "Not configured");
        log.info("  Database URL: {}", datasourceUrl);
        log.info("  Database Driver: {}", datasourceDriver);

        // JPA configuration
        String ddlAuto = env.getProperty("spring.jpa.hibernate.ddl-auto", "update");
        String showSql = env.getProperty("spring.jpa.show-sql", "false");
        log.info("  JPA DDL Auto: {}", ddlAuto);
        log.info("  JPA Show SQL: {}", showSql);

        // Application specific configuration
        String maxConcurrentTasks = env.getProperty("app.profiling.max-concurrent-tasks", "5");
        String taskTimeout = env.getProperty("app.profiling.task-timeout-minutes", "60");
        log.info("  Max Concurrent Tasks: {}", maxConcurrentTasks);
        log.info("  Task Timeout (minutes): {}", taskTimeout);

        // Thread pool configuration
        String corePoolSize = env.getProperty("app.profiling.thread-pool.core-size", "5");
        String maxPoolSize = env.getProperty("app.profiling.thread-pool.max-size", "20");
        log.info("  Thread Pool Core Size: {}", corePoolSize);
        log.info("  Thread Pool Max Size: {}", maxPoolSize);

        log.info("----------------------------------------------------------");
    }

}
