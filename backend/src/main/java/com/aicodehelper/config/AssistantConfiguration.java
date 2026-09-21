package com.aicodehelper.config;

import com.aicodehelper.ai.CoreAssistant;
import com.aicodehelper.ai.RagAssistant;
import com.aicodehelper.ai.ReportAssistant;
import com.aicodehelper.guardrail.SafeInputGuardrail;
import com.aicodehelper.agent.AgentProperties;
import com.aicodehelper.agent.BoundedToolProvider;
import com.aicodehelper.memory.ConversationMemoryRegistry;
import com.aicodehelper.rag.KnowledgeBaseRetriever;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.StreamingChatModel;
import dev.langchain4j.service.AiServices;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class AssistantConfiguration {

    @Bean
    CoreAssistant coreAssistant(
            ChatModel chatModel,
            StreamingChatModel streamingChatModel,
            ConversationMemoryRegistry memories,
            SafeInputGuardrail guardrail,
            BoundedToolProvider toolProvider,
            AgentProperties agentProperties,
            KnowledgeBaseRetriever contentRetriever
    ) {
        return AiServices.builder(CoreAssistant.class)
                .chatModel(chatModel)
                .streamingChatModel(streamingChatModel)
                .chatMemoryProvider(memories)
                .inputGuardrails(guardrail)
                .toolProvider(toolProvider)
                .beforeToolExecution(toolProvider::beforeToolExecution)
                .hallucinatedToolNameStrategy(toolProvider::unauthorized)
                .toolExecutionErrorHandler((error, context) -> toolProvider.sanitizedError(error))
                .toolArgumentsErrorHandler((error, context) -> toolProvider.sanitizedError(error))
                .contentRetriever(contentRetriever)
                .storeRetrievedContentInChatMemory(false)
                .maxToolCallingRoundTrips(agentProperties.getMaxSteps())
                .build();
    }

    @Bean
    RagAssistant ragAssistant(
            ChatModel chatModel,
            ConversationMemoryRegistry memories,
            SafeInputGuardrail guardrail,
            BoundedToolProvider toolProvider,
            AgentProperties agentProperties,
            KnowledgeBaseRetriever contentRetriever
    ) {
        return AiServices.builder(RagAssistant.class)
                .chatModel(chatModel)
                .chatMemoryProvider(memories)
                .inputGuardrails(guardrail)
                .toolProvider(toolProvider)
                .beforeToolExecution(toolProvider::beforeToolExecution)
                .hallucinatedToolNameStrategy(toolProvider::unauthorized)
                .toolExecutionErrorHandler((error, context) -> toolProvider.sanitizedError(error))
                .toolArgumentsErrorHandler((error, context) -> toolProvider.sanitizedError(error))
                .contentRetriever(contentRetriever)
                .storeRetrievedContentInChatMemory(false)
                .maxToolCallingRoundTrips(agentProperties.getMaxSteps())
                .build();
    }

    @Bean
    ReportAssistant reportAssistant(ChatModel chatModel, SafeInputGuardrail guardrail) {
        return AiServices.builder(ReportAssistant.class)
                .chatModel(chatModel)
                .inputGuardrails(guardrail)
                .build();
    }
}
