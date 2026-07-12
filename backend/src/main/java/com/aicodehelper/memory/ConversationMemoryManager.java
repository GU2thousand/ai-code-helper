package com.aicodehelper.memory;

import com.aicodehelper.ai.AiExecutionRegistry;
import com.aicodehelper.ai.CoreAssistant;
import com.aicodehelper.ai.RagAssistant;
import com.aicodehelper.error.AiExecutionRejectedException;
import org.springframework.stereotype.Component;

/**
 * Keeps the application registry and both LangChain4j AI-service caches in sync.
 */
@Component
public class ConversationMemoryManager {

    private final ConversationMemoryRegistry registry;
    private final CoreAssistant coreAssistant;
    private final RagAssistant ragAssistant;
    private final AiExecutionRegistry executions;

    public ConversationMemoryManager(
            ConversationMemoryRegistry registry,
            CoreAssistant coreAssistant,
            RagAssistant ragAssistant,
            AiExecutionRegistry executions
    ) {
        this.registry = registry;
        this.coreAssistant = coreAssistant;
        this.ragAssistant = ragAssistant;
        this.executions = executions;
    }

    /**
     * Reserve a stable memory object before an AI service can cache it. When full, evict the
     * least-recently-used inactive conversation from every cache before removing the registry entry.
     */
    public synchronized void prepare(String conversationKey) {
        if (registry.contains(conversationKey)) {
            registry.get(conversationKey);
            return;
        }

        while (registry.isAtCapacity()) {
            Object candidate = registry.leastRecentlyUsed(key ->
                            !executions.isActive(String.valueOf(key)))
                    .orElseThrow(AiExecutionRejectedException::memoryCapacityReached);
            boolean evicted = executions.runIfInactive(String.valueOf(candidate), () -> {
                coreAssistant.evictChatMemory(candidate);
                ragAssistant.evictChatMemory(candidate);
                registry.evictAfterServiceCaches(candidate);
            });
            if (!evicted) {
                continue;
            }
        }
        registry.get(conversationKey);
    }
}
