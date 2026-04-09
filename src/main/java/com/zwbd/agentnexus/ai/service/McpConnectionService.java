package com.zwbd.agentnexus.ai.service;

import com.zwbd.agentnexus.ai.dto.ToolInfo;
import com.zwbd.agentnexus.ai.dto.mcp.McpConnectionRequest;
import com.zwbd.agentnexus.ai.dto.mcp.McpConnectionView;
import com.zwbd.agentnexus.ai.entity.McpConnectionEntity;
import com.zwbd.agentnexus.ai.enums.McpTransportType;
import com.zwbd.agentnexus.ai.repository.McpConnectionRepository;
import com.zwbd.agentnexus.ai.tools.GlobalToolManager;
import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.client.transport.HttpClientSseClientTransport;
import io.modelcontextprotocol.client.transport.ServerParameters;
import io.modelcontextprotocol.client.transport.StdioClientTransport;
import io.modelcontextprotocol.spec.McpClientTransport;
import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.mcp.SyncMcpToolCallbackProvider;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import java.net.http.HttpRequest;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

@Slf4j
@Service
public class McpConnectionService {

    private static final String PROVIDER_PREFIX = "mcp";

    private final McpConnectionRepository repository;
    private final GlobalToolManager toolManager;
    private final AgentFactory agentFactory;

    private final Map<String, RuntimeConnection> runtimeConnections = new ConcurrentHashMap<>();

    public McpConnectionService(McpConnectionRepository repository,
                                GlobalToolManager toolManager,
                                AgentFactory agentFactory) {
        this.repository = repository;
        this.toolManager = toolManager;
        this.agentFactory = agentFactory;
    }

    @PostConstruct
    public void loadEnabledConnections() {
        List<McpConnectionEntity> enabledConnections = repository.findByEnabledTrue();
        for (McpConnectionEntity entity : enabledConnections) {
            try {
                activateConnection(entity);
            } catch (Exception e) {
                log.error("Failed to activate MCP connection on startup: {}", entity.getId(), e);
            }
        }
    }

    @PreDestroy
    public void closeAllConnections() {
        new ArrayList<>(runtimeConnections.keySet()).forEach(this::deactivateConnection);
    }

    public List<McpConnectionView> listConnections() {
        return repository.findAll().stream()
                .sorted(Comparator.comparing(McpConnectionEntity::getName))
                .map(this::toView)
                .toList();
    }

    public McpConnectionView getConnection(String id) {
        McpConnectionEntity entity = repository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("MCP connection not found: " + id));
        return toView(entity);
    }

    @Transactional
    public McpConnectionView createConnection(McpConnectionRequest request) {
        validateRequest(request);
        McpConnectionEntity entity = new McpConnectionEntity();
        copyRequest(entity, request);
        McpConnectionEntity saved = repository.save(entity);

        if (saved.isEnabled()) {
            activateConnection(saved);
        }
        return toView(saved);
    }

    @Transactional
    public McpConnectionView updateConnection(String id, McpConnectionRequest request) {
        validateRequest(request);
        McpConnectionEntity entity = repository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("MCP connection not found: " + id));

        deactivateConnection(id);
        copyRequest(entity, request);
        McpConnectionEntity saved = repository.save(entity);
        if (saved.isEnabled()) {
            activateConnection(saved);
        }
        return toView(saved);
    }

    @Transactional
    public void enableConnection(String id) {
        McpConnectionEntity entity = repository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("MCP connection not found: " + id));
        if (!entity.isEnabled()) {
            entity.setEnabled(true);
            repository.save(entity);
        }
        activateConnection(entity);
    }

    @Transactional
    public void disableConnection(String id) {
        McpConnectionEntity entity = repository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("MCP connection not found: " + id));
        if (entity.isEnabled()) {
            entity.setEnabled(false);
            repository.save(entity);
        }
        deactivateConnection(id);
    }

    @Transactional
    public void deleteConnection(String id) {
        deactivateConnection(id);
        repository.deleteById(id);
    }

    public McpConnectionView refreshConnection(String id) {
        McpConnectionEntity entity = repository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("MCP connection not found: " + id));
        deactivateConnection(id);
        if (entity.isEnabled()) {
            activateConnection(entity);
        }
        return toView(entity);
    }

    public String testConnection(String id) {
        McpConnectionEntity entity = repository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("MCP connection not found: " + id));
        try (McpSyncClient client = buildClient(entity)) {
            client.initialize();
            int toolCount = client.listTools().tools().size();
            return "Connection test passed, tools=" + toolCount;
        } catch (Exception e) {
            throw new RuntimeException("Connection test failed: " + e.getMessage(), e);
        }
    }

    public List<ToolInfo> listTools(String id) {
        ensureConnectionExists(id);
        return toolManager.getToolsByProvider(providerId(id));
    }

    private void ensureConnectionExists(String id) {
        if (!repository.existsById(id)) {
            throw new IllegalArgumentException("MCP connection not found: " + id);
        }
    }

    private void validateRequest(McpConnectionRequest request) {
        if (request.getName() == null || request.getName().isBlank()) {
            throw new IllegalArgumentException("name is required");
        }
        if (request.getTransportType() == McpTransportType.STDIO) {
            if (request.getCommand() == null || request.getCommand().isBlank()) {
                throw new IllegalArgumentException("command is required for STDIO transport");
            }
        }
        if (request.getTransportType() == McpTransportType.SSE) {
            if (request.getBaseUrl() == null || request.getBaseUrl().isBlank()) {
                throw new IllegalArgumentException("baseUrl is required for SSE transport");
            }
        }
    }

    private void copyRequest(McpConnectionEntity entity, McpConnectionRequest request) {
        entity.setName(request.getName());
        entity.setTransportType(request.getTransportType());
        entity.setEnabled(request.isEnabled());
        entity.setRequestTimeoutSeconds(defaultTimeout(request.getRequestTimeoutSeconds()));

        entity.setCommand(request.getCommand());
        entity.setArgs(request.getArgs() == null ? new ArrayList<>() : new ArrayList<>(request.getArgs()));
        entity.setEnvVars(request.getEnvVars() == null ? new HashMap<>() : new HashMap<>(request.getEnvVars()));

        entity.setBaseUrl(request.getBaseUrl());
        entity.setSseEndpoint(request.getSseEndpoint());
        entity.setHeaders(request.getHeaders() == null ? new HashMap<>() : new HashMap<>(request.getHeaders()));
    }

    private void activateConnection(McpConnectionEntity entity) {
        String id = entity.getId();
        String providerId = providerId(id);

        deactivateConnection(id);
        RuntimeConnection runtime = new RuntimeConnection();
        runtime.running = false;
        runtime.lastError = null;
        runtime.toolCount = 0;

        try {
            McpSyncClient client = buildClient(entity);
            client.initialize();

            SyncMcpToolCallbackProvider callbackProvider = new SyncMcpToolCallbackProvider(client);
            toolManager.registerMcpTools(providerId, providerType(entity), callbackProvider.getToolCallbacks());

            runtime.client = client;
            runtime.toolCallbackProvider = callbackProvider;
            runtime.running = true;
            runtime.toolCount = toolManager.getProviderToolIds(providerId).size();
            runtimeConnections.put(id, runtime);

            agentFactory.evictAllClients();
            log.info("MCP connection activated: id={}, provider={}, tools={}", id, providerId, runtime.toolCount);
        } catch (Exception e) {
            runtime.lastError = e.getMessage();
            runtimeConnections.put(id, runtime);
            toolManager.removeByProvider(providerId);
            agentFactory.evictAllClients();
            log.error("Failed to activate MCP connection [{}]", id, e);
            throw new RuntimeException("Failed to activate MCP connection: " + e.getMessage(), e);
        }
    }

    private void deactivateConnection(String connectionId) {
        RuntimeConnection runtime = runtimeConnections.remove(connectionId);
        String providerId = providerId(connectionId);
        toolManager.removeByProvider(providerId);
        agentFactory.evictAllClients();

        if (runtime != null && runtime.client != null) {
            try {
                runtime.client.closeGracefully();
            } catch (Exception e) {
                log.warn("Error while closing MCP connection [{}]", connectionId, e);
            }
        }
        log.info("MCP connection deactivated: {}", connectionId);
    }

    private McpSyncClient buildClient(McpConnectionEntity entity) {
        McpClientTransport transport = buildTransport(entity);
        return McpClient.sync(transport)
                .requestTimeout(Duration.ofSeconds(defaultTimeout(entity.getRequestTimeoutSeconds())))
                .build();
    }

    private McpClientTransport buildTransport(McpConnectionEntity entity) {
        if (entity.getTransportType() == McpTransportType.STDIO) {
            ServerParameters.Builder builder = ServerParameters.builder(entity.getCommand());
            if (entity.getArgs() != null && !entity.getArgs().isEmpty()) {
                builder.args(entity.getArgs());
            }
            if (entity.getEnvVars() != null && !entity.getEnvVars().isEmpty()) {
                builder.env(entity.getEnvVars());
            }
            return new StdioClientTransport(builder.build());
        }

        HttpClientSseClientTransport.Builder builder = HttpClientSseClientTransport.builder(entity.getBaseUrl());
        if (entity.getSseEndpoint() != null && !entity.getSseEndpoint().isBlank()) {
            builder.sseEndpoint(entity.getSseEndpoint());
        }
        if (entity.getHeaders() != null && !entity.getHeaders().isEmpty()) {
            builder.customizeRequest(httpRequestBuilder ->
                    entity.getHeaders().forEach(httpRequestBuilder::header));
        }
        return builder.build();
    }

    private Integer defaultTimeout(Integer timeoutSeconds) {
        if (timeoutSeconds == null || timeoutSeconds <= 0) {
            return 30;
        }
        return timeoutSeconds;
    }

    private McpConnectionView toView(McpConnectionEntity entity) {
        RuntimeConnection runtime = runtimeConnections.get(entity.getId());
        return McpConnectionView.builder()
                .id(entity.getId())
                .name(entity.getName())
                .transportType(entity.getTransportType())
                .enabled(entity.isEnabled())
                .requestTimeoutSeconds(entity.getRequestTimeoutSeconds())
                .command(entity.getCommand())
                .baseUrl(entity.getBaseUrl())
                .sseEndpoint(entity.getSseEndpoint())
                .providerId(providerId(entity.getId()))
                .running(runtime != null && runtime.running)
                .toolCount(runtime == null ? 0 : runtime.toolCount)
                .lastError(runtime == null ? null : runtime.lastError)
                .build();
    }

    private String providerId(String connectionId) {
        return PROVIDER_PREFIX + "-" + connectionId;
    }

    private String providerType(McpConnectionEntity entity) {
        return "MCP_" + entity.getTransportType().name();
    }

    @AllArgsConstructor
    private static class RuntimeConnection {
        private McpSyncClient client;
        private SyncMcpToolCallbackProvider toolCallbackProvider;
        private boolean running;
        private int toolCount;
        private String lastError;

        private RuntimeConnection() {
        }
    }
}

