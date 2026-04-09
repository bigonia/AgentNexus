package com.zwbd.agentnexus.ai.entity;

import com.zwbd.agentnexus.ai.enums.McpTransportType;
import com.zwbd.agentnexus.common.converter.MapToJsonConverter;
import com.zwbd.agentnexus.utils.StringListConverter;
import jakarta.persistence.*;
import lombok.Data;
import org.hibernate.annotations.TenantId;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Data
@Entity
@Table(name = "ai_mcp_connections")
public class McpConnectionEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "connection_id", nullable = false, unique = true)
    private String id;

    @TenantId
    @Column(name = "space_id", nullable = false, updatable = false)
    private String spaceId;

    @Column(name = "name", nullable = false)
    private String name;

    @Enumerated(EnumType.STRING)
    @Column(name = "transport_type", nullable = false, length = 16)
    private McpTransportType transportType = McpTransportType.STDIO;

    @Column(name = "enabled", nullable = false)
    private boolean enabled = true;

    @Column(name = "request_timeout_seconds")
    private Integer requestTimeoutSeconds = 30;

    // STDIO fields
    @Column(name = "command")
    private String command;

    @Convert(converter = StringListConverter.class)
    @Column(name = "args", columnDefinition = "TEXT")
    private List<String> args = new ArrayList<>();

    @Convert(converter = MapToJsonConverter.class)
    @Column(name = "env_vars", columnDefinition = "TEXT")
    private Map<String, String> envVars = new HashMap<>();

    // SSE fields
    @Column(name = "base_url", length = 2048)
    private String baseUrl;

    @Column(name = "sse_endpoint", length = 2048)
    private String sseEndpoint;

    @Convert(converter = MapToJsonConverter.class)
    @Column(name = "headers", columnDefinition = "TEXT")
    private Map<String, String> headers = new HashMap<>();
}

