package com.aicodehelper.ai.provider;

import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.model.embedding.listener.EmbeddingModelListener;
import dev.langchain4j.model.output.Response;

import java.time.Duration;
import java.util.List;

public final class DeadlineEmbeddingModel implements EmbeddingModel {
    private final EmbeddingModel delegate;
    private final EmbeddingModel metadataDelegate;
    private final ProviderCallExecutor executor;
    private final Duration timeout;

    public DeadlineEmbeddingModel(EmbeddingModel delegate, ProviderCallExecutor executor, Duration timeout) {
        this(delegate, delegate, executor, timeout);
    }

    private DeadlineEmbeddingModel(EmbeddingModel delegate, EmbeddingModel metadataDelegate,
                                   ProviderCallExecutor executor, Duration timeout) {
        this.delegate = delegate;
        this.metadataDelegate = metadataDelegate;
        this.executor = executor;
        this.timeout = timeout;
    }

    public EmbeddingModel delegate() { return delegate; }
    @Override public Response<Embedding> embed(String text) {
        return executor.call(timeout, () -> delegate.embed(text));
    }
    @Override public Response<Embedding> embed(TextSegment segment) {
        return executor.call(timeout, () -> delegate.embed(segment));
    }
    @Override public Response<List<Embedding>> embedAll(List<TextSegment> segments) {
        return executor.call(timeout, () -> delegate.embedAll(segments));
    }
    @Override public int dimension() { return executor.call(timeout, metadataDelegate::dimension); }
    @Override public String modelName() { return metadataDelegate.modelName(); }
    @Override public EmbeddingModel addListener(EmbeddingModelListener listener) {
        return new DeadlineEmbeddingModel(delegate.addListener(listener), metadataDelegate, executor, timeout);
    }
    @Override public EmbeddingModel addListeners(List<EmbeddingModelListener> listeners) {
        return new DeadlineEmbeddingModel(delegate.addListeners(listeners), metadataDelegate, executor, timeout);
    }
}
