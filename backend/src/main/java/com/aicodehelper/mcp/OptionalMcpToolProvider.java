package com.aicodehelper.mcp;

import com.aicodehelper.config.AppProperties;
import dev.langchain4j.mcp.McpToolProvider;
import dev.langchain4j.mcp.client.DefaultMcpClient;
import dev.langchain4j.mcp.client.McpClient;
import dev.langchain4j.mcp.client.transport.McpTransport;
import dev.langchain4j.mcp.client.transport.http.StreamableHttpMcpTransport;
import dev.langchain4j.service.tool.ToolProvider;
import dev.langchain4j.service.tool.ToolProviderRequest;
import dev.langchain4j.service.tool.ToolProviderResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.time.Clock;
import java.time.Instant;
import java.util.Map;

@Component
public class OptionalMcpToolProvider implements ToolProvider, AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(OptionalMcpToolProvider.class);

    private final AppProperties.Mcp config;
    private final Clock clock;
    private volatile McpToolProvider delegate;
    private volatile McpClient client;
    private volatile Instant retryAfter = Instant.EPOCH;

    public OptionalMcpToolProvider(AppProperties properties, Clock clock) {
        this.config = properties.getMcp();
        this.clock = clock;
        if (isConfigured()) {
            log.info("BigModel MCP is enabled and will connect lazily on first tool-enabled request");
        } else {
            log.info("BigModel MCP is disabled or has no API key; startup remains offline");
        }
    }

    @Override
    public ToolProviderResult provideTools(ToolProviderRequest request) {
        if (!isConfigured()) {
            return ToolProviderResult.builder().build();
        }
        if (clock.instant().isBefore(retryAfter)) {
            return ToolProviderResult.builder().build();
        }
        try {
            return provider().provideTools(request);
        } catch (RuntimeException error) {
            markFailed();
            log.warn("BigModel MCP tool discovery failed errorType={} retryBackoffSeconds={}",
                    error.getClass().getSimpleName(), config.getRetryBackoff().toSeconds());
            return ToolProviderResult.builder().build();
        }
    }

    public boolean isConfigured() {
        return config.isEnabled()
                && StringUtils.hasText(config.getApiKey())
                && StringUtils.hasText(config.getEndpoint());
    }

    public boolean isConnected() {
        return delegate != null;
    }

    private McpToolProvider provider() {
        McpToolProvider current = delegate;
        if (current != null) {
            return current;
        }
        synchronized (this) {
            if (delegate == null) {
                McpTransport transport = StreamableHttpMcpTransport.builder()
                        .url(config.getEndpoint())
                        .customHeaders(Map.of("Authorization", bearer(config.getApiKey())))
                        .timeout(config.getTimeout())
                        .logRequests(false)
                        .logResponses(false)
                        .build();
                client = DefaultMcpClient.builder()
                        .key("bigmodel-web-search")
                        .clientName("ai-code-helper")
                        .transport(transport)
                        .initializationTimeout(config.getTimeout())
                        .toolExecutionTimeout(config.getTimeout())
                        .autoHealthCheck(false)
                        .build();
                delegate = McpToolProvider.builder()
                        .mcpClients(client)
                        .failIfOneServerFails(false)
                        .filterToolNames(config.getAllowedToolNames())
                        .build();
            }
            return delegate;
        }
    }

    private String bearer(String apiKey) {
        return apiKey.regionMatches(true, 0, "Bearer ", 0, 7) ? apiKey : "Bearer " + apiKey;
    }

    private synchronized void markFailed() {
        retryAfter = clock.instant().plus(config.getRetryBackoff());
        closeClient();
        delegate = null;
        client = null;
    }

    @Override
    public synchronized void close() {
        closeClient();
        delegate = null;
        client = null;
    }

    private void closeClient() {
        McpClient current = client;
        if (current != null) {
            try {
                current.close();
            } catch (Exception error) {
                log.warn("BigModel MCP close failed errorType={}", error.getClass().getSimpleName());
            }
        }
    }
}
