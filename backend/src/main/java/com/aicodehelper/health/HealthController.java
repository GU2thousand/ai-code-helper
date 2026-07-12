package com.aicodehelper.health;

import com.aicodehelper.ai.ModelRuntimeInfo;
import com.aicodehelper.mcp.OptionalMcpToolProvider;
import com.aicodehelper.rag.KnowledgeBaseRetriever;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;

@RestController
@RequestMapping("/api")
public class HealthController {

    private final ModelRuntimeInfo modelInfo;
    private final OptionalMcpToolProvider mcp;
    private final KnowledgeBaseRetriever knowledgeBase;

    public HealthController(
            ModelRuntimeInfo modelInfo,
            OptionalMcpToolProvider mcp,
            KnowledgeBaseRetriever knowledgeBase
    ) {
        this.modelInfo = modelInfo;
        this.mcp = mcp;
        this.knowledgeBase = knowledgeBase;
    }

    @GetMapping("/health")
    public HealthResponse health() {
        return new HealthResponse(
                "UP",
                "ai-code-helper-backend",
                Instant.now(),
                modelInfo.chatProvider(),
                modelInfo.chatModel(),
                modelInfo.embeddingProvider(),
                modelInfo.embeddingModel(),
                mcp.isConfigured(),
                mcp.isConnected(),
                knowledgeBase.segmentCount()
        );
    }

    public record HealthResponse(
            String status,
            String service,
            Instant timestamp,
            String chatProvider,
            String chatModel,
            String embeddingProvider,
            String embeddingModel,
            boolean mcpConfigured,
            boolean mcpConnected,
            int knowledgeSegments
    ) {
    }
}
