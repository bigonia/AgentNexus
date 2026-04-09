package com.zwbd.agentnexus.ai.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * @Author: wnli
 * @Date: 2026/1/6 17:04
 * @Desc:
 */
@NoArgsConstructor
@AllArgsConstructor
@Data
public class ToolInfo {

    /**
     * Logical tool id. For MCP tools it is providerId:toolName.
     * For legacy local tools, it is equal to name.
     */
    private String toolId;

    /**
     * Raw tool name exposed by provider.
     */
    private String name;

    private String description;
    private String inputSchema;

    /**
     * Source provider id, null means local/internal tool.
     */
    private String providerId;

    /**
     * source type, e.g. LOCAL / MCP_STDIO / MCP_SSE.
     */
    private String providerType;

    private boolean enabled;

}
