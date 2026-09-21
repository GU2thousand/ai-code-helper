package com.aicodehelper.ai.provider;

import com.aicodehelper.ai.local.LocalChatModel;
import com.aicodehelper.observability.AiTelemetry;
import dev.langchain4j.model.ModelProvider;
import dev.langchain4j.model.chat.Capability;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.ChatRequestOptions;
import dev.langchain4j.model.chat.listener.ChatModelListener;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.request.ChatRequestParameters;
import dev.langchain4j.model.chat.response.ChatResponse;

import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Callable;

/** Time out before AiServices receives a response, so late answers cannot write memory. */
public final class DeadlineChatModel implements ChatModel {
    private final ChatModel delegate;
    private final ProviderCallExecutor executor;
    private final Duration timeout;

    public DeadlineChatModel(ChatModel delegate, ProviderCallExecutor executor, Duration timeout) {
        this.delegate = delegate;
        this.executor = executor;
        this.timeout = timeout;
    }

    public ChatModel delegate() { return delegate; }
    @Override public ChatResponse chat(ChatRequest request) {
        return call(() -> delegate.chat(request));
    }
    @Override public ChatResponse chat(ChatRequest request, ChatRequestOptions options) {
        return call(() -> delegate.chat(request, options));
    }
    @Override public ChatResponse doChat(ChatRequest request) {
        return call(() -> delegate.doChat(request));
    }
    private ChatResponse call(Callable<ChatResponse> operation) {
        // Remote models are measured by their existing listener. Local usage is unknown.
        try (AiTelemetry.StageTimer stage = delegate instanceof LocalChatModel ? executor.llmStage() : null) {
            try { return executor.call(timeout, operation); }
            catch (RuntimeException error) { if (stage != null) stage.failure(error); throw error; }
        }
    }
    @Override public ChatRequestParameters defaultRequestParameters() { return delegate.defaultRequestParameters(); }
    @Override public List<ChatModelListener> listeners() { return delegate.listeners(); }
    @Override public ModelProvider provider() { return delegate.provider(); }
    @Override public Set<Capability> supportedCapabilities() { return delegate.supportedCapabilities(); }
}
