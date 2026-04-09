package com.zwbd.agentnexus.ai.web.controller;

import com.zwbd.agentnexus.ai.dto.ToolInfo;
import com.zwbd.agentnexus.ai.dto.mcp.McpConnectionRequest;
import com.zwbd.agentnexus.ai.dto.mcp.McpConnectionView;
import com.zwbd.agentnexus.ai.service.McpConnectionService;
import com.zwbd.agentnexus.common.web.ApiResponse;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/v1/mcp")
public class McpController {

    private final McpConnectionService mcpConnectionService;

    public McpController(McpConnectionService mcpConnectionService) {
        this.mcpConnectionService = mcpConnectionService;
    }

    @GetMapping("/connections")
    public ApiResponse<List<McpConnectionView>> listConnections() {
        return ApiResponse.ok(mcpConnectionService.listConnections());
    }

    @GetMapping("/connections/{id}")
    public ApiResponse<McpConnectionView> getConnection(@PathVariable String id) {
        return ApiResponse.ok(mcpConnectionService.getConnection(id));
    }

    @PostMapping("/connections")
    public ApiResponse<McpConnectionView> createConnection(@RequestBody McpConnectionRequest request) {
        return ApiResponse.ok(mcpConnectionService.createConnection(request));
    }

    @PutMapping("/connections/{id}")
    public ApiResponse<McpConnectionView> updateConnection(@PathVariable String id,
                                                           @RequestBody McpConnectionRequest request) {
        return ApiResponse.ok(mcpConnectionService.updateConnection(id, request));
    }

    @PostMapping("/connections/{id}/enable")
    public ApiResponse<Void> enableConnection(@PathVariable String id) {
        mcpConnectionService.enableConnection(id);
        return ApiResponse.success();
    }

    @PostMapping("/connections/{id}/disable")
    public ApiResponse<Void> disableConnection(@PathVariable String id) {
        mcpConnectionService.disableConnection(id);
        return ApiResponse.success();
    }

    @PostMapping("/connections/{id}/refresh")
    public ApiResponse<McpConnectionView> refreshConnection(@PathVariable String id) {
        return ApiResponse.ok(mcpConnectionService.refreshConnection(id));
    }

    @PostMapping("/connections/{id}/test")
    public ApiResponse<String> testConnection(@PathVariable String id) {
        return ApiResponse.ok(mcpConnectionService.testConnection(id));
    }

    @DeleteMapping("/connections/{id}")
    public ApiResponse<Void> deleteConnection(@PathVariable String id) {
        mcpConnectionService.deleteConnection(id);
        return ApiResponse.success();
    }

    @GetMapping("/connections/{id}/tools")
    public ApiResponse<List<ToolInfo>> listConnectionTools(@PathVariable String id) {
        return ApiResponse.ok(mcpConnectionService.listTools(id));
    }
}

