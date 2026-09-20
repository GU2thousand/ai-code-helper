package com.aicodehelper.config;

import com.aicodehelper.ai.CoreAssistant;
import com.aicodehelper.ai.RagAssistant;
import com.aicodehelper.ai.ReportAssistant;
import com.aicodehelper.guardrail.SafeInputGuardrail;
import com.aicodehelper.mcp.OptionalMcpToolProvider;
import com.aicodehelper.memory.ConversationMemoryRegistry;
import com.aicodehelper.rag.KnowledgeBaseRetriever;
import com.aicodehelper.tool.InterviewQuestionTool;
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
            InterviewQuestionTool interviewQuestionTool,
            OptionalMcpToolProvider mcpToolProvider,
            KnowledgeBaseRetriever contentRetriever
    ) {
        return AiServices.builder(CoreAssistant.class)
                .chatModel(chatModel)
                .streamingChatModel(streamingChatModel)
                .chatMemoryProvider(memories)
                .inputGuardrails(guardrail)
                .tools(interviewQuestionTool)
                .toolProvider(mcpToolProvider)
                .contentRetriever(contentRetriever)
                .storeRetrievedContentInChatMemory(false)
                .maxToolCallingRoundTrips(3)
                .build();
    }

    @Bean
    RagAssistant ragAssistant(
            ChatModel chatModel,
            ConversationMemoryRegistry memories,
            SafeInputGuardrail guardrail,
            InterviewQuestionTool interviewQuestionTool,
            OptionalMcpToolProvider mcpToolProvider,
            KnowledgeBaseRetriever contentRetriever
    ) {
        return AiServices.builder(RagAssistant.class)
                .chatModel(chatModel)
                .chatMemoryProvider(memories)
                .inputGuardrails(guardrail)
                .tools(interviewQuestionTool)
                .toolProvider(mcpToolProvider)
                .contentRetriever(contentRetriever)
                .storeRetrievedContentInChatMemory(false)
                .maxToolCallingRoundTrips(3)
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
