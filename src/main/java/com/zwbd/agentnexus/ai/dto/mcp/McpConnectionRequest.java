package com.zwbd.agentnexus.ai.dto.mcp;

import com.zwbd.agentnexus.ai.enums.McpTransportType;
import lombok.Data;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Data
public class McpConnectionRequest {

    private String name;
    private McpTransportType transportType = McpTransportType.STDIO;
    private boolean enabled = true;
    private Integer requestTimeoutSeconds = 30;

    // STDIO
    private String command;
    private List<String> args = new ArrayList<>();
    private Map<String, String> envVars = new HashMap<>();

    // SSE
    private String baseUrl;
    private String sseEndpoint;
    private Map<String, String> headers = new HashMap<>();
}

