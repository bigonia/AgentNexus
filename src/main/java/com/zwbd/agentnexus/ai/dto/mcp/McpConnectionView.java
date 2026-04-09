package com.zwbd.agentnexus.ai.dto.mcp;

import com.zwbd.agentnexus.ai.enums.McpTransportType;
import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class McpConnectionView {

    private String id;
    private String name;
    private McpTransportType transportType;
    private boolean enabled;
    private Integer requestTimeoutSeconds;

    private String command;
    private String baseUrl;
    private String sseEndpoint;

    private String providerId;

    private boolean running;
    private Integer toolCount;
    private String lastError;
}

