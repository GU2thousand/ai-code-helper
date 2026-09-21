package com.aicodehelper.ai.provider;

import com.aicodehelper.ai.local.LocalStreamingChatModel;
import com.aicodehelper.observability.AiTelemetry;
import dev.langchain4j.model.ModelProvider;
import dev.langchain4j.model.chat.Capability;
import dev.langchain4j.model.chat.ChatRequestOptions;
import dev.langchain4j.model.chat.StreamingChatModel;
import dev.langchain4j.model.chat.listener.ChatModelListener;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.request.ChatRequestParameters;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.chat.response.CompleteToolCall;
import dev.langchain4j.model.chat.response.PartialResponse;
import dev.langchain4j.model.chat.response.PartialResponseContext;
import dev.langchain4j.model.chat.response.PartialThinking;
import dev.langchain4j.model.chat.response.PartialThinkingContext;
import dev.langchain4j.model.chat.response.PartialToolCall;
import dev.langchain4j.model.chat.response.PartialToolCallContext;
import dev.langchain4j.model.chat.response.StreamingChatResponseHandler;
import dev.langchain4j.model.chat.response.StreamingHandle;

import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Future;
import java.util.concurrent.ScheduledFuture;
import java.util.function.Consumer;

/**
 * Gates every callback before it reaches AiServices (and its memory writes).
 * A servlet timeout alone cannot prevent a misbehaving provider's late response
 * from modifying memory. This guard also supplies a cancellation handle when a
 * provider only implements the legacy partial-text callback.
 */
public final class DeadlineStreamingChatModel implements StreamingChatModel {
    private final StreamingChatModel delegate;
    private final ProviderCallExecutor executor;
    private final Duration firstTokenTimeout;
    private final Duration streamTimeout;

    public DeadlineStreamingChatModel(StreamingChatModel delegate, ProviderCallExecutor executor,
                                      Duration firstTokenTimeout, Duration streamTimeout) {
        this.delegate = delegate;
        this.executor = executor;
        this.firstTokenTimeout = firstTokenTimeout;
        this.streamTimeout = streamTimeout;
    }

    public StreamingChatModel delegate() { return delegate; }
    @Override public void chat(ChatRequest request, StreamingChatResponseHandler handler) {
        start(handler, guarded -> delegate.chat(request, guarded));
    }
    @Override public void chat(ChatRequest request, ChatRequestOptions options, StreamingChatResponseHandler handler) {
        start(handler, guarded -> delegate.chat(request, options, guarded));
    }
    @Override public void doChat(ChatRequest request, StreamingChatResponseHandler handler) {
        start(handler, guarded -> delegate.doChat(request, guarded));
    }
    @Override public ChatRequestParameters defaultRequestParameters() { return delegate.defaultRequestParameters(); }
    @Override public List<ChatModelListener> listeners() { return delegate.listeners(); }
    @Override public ModelProvider provider() { return delegate.provider(); }
    @Override public Set<Capability> supportedCapabilities() { return delegate.supportedCapabilities(); }

    private void start(StreamingChatResponseHandler handler, Consumer<StreamingChatResponseHandler> operation) {
        final Guard guard;
        try { guard = new Guard(handler, executor.acquire()); }
        catch (RuntimeException error) { handler.onError(error); return; }
        try {
            guard.registerCancellation();
            guard.startTimers();
            guard.setWorker(executor.submit(() -> {
                if (!guard.providerStarted()) return null;
                try { operation.accept(guard); }
                catch (Throwable error) { guard.onError(error); }
                return null;
            }, guard::workerFinished));
        } catch (RuntimeException error) {
            guard.workerFinished();
            guard.onError(error);
        }
    }

    private final class Guard implements StreamingChatResponseHandler, StreamingHandle {
        private final StreamingChatResponseHandler downstream;
        private final ProviderCallExecutor.Permit permit;
        private final AiTelemetry.RequestObservation request = executor.currentRequest();
        private final AiTelemetry.StageTimer stage = delegate instanceof LocalStreamingChatModel ? executor.llmStage() : null;
        private final Runnable cancellationHook = this::cancel;
        private boolean terminal;
        private volatile boolean cancelled;
        private boolean providerFinished;
        private boolean providerStarted;
        private boolean workerFinished;
        private StreamingHandle providerHandle;
        private StreamingHandle cancellationRequested;
        private volatile Future<?> worker;
        private ScheduledFuture<?> firstTokenTimer;
        private ScheduledFuture<?> totalTimer;

        private Guard(StreamingChatResponseHandler downstream, ProviderCallExecutor.Permit permit) {
            this.downstream = downstream;
            this.permit = permit;
        }

        private void registerCancellation() { executor.registerCancellation(request, cancellationHook); }
        private void unregisterCancellation() { executor.unregisterCancellation(request, cancellationHook); }

        private synchronized void startTimers() {
            if (terminal) return;
            firstTokenTimer = executor.schedule(firstTokenTimeout, this::timeout);
            totalTimer = executor.schedule(streamTimeout, this::timeout);
        }

        private void setWorker(Future<?> worker) {
            // The submitted provider may already be calling back under this guard.
            // Taking its monitor here can invert with a previous tool-round guard.
            // Volatile publication + the post-publication cancellation check cover
            // cancellation both before and after this assignment without that lock.
            this.worker = worker;
            if (cancelled) worker.cancel(true);
        }

        private synchronized void workerFinished() {
            workerFinished = true;
            if (!providerStarted) providerFinished = true;
            releaseIfFinished();
        }

        private synchronized boolean providerStarted() {
            if (terminal) return false;
            providerStarted = true;
            return true;
        }

        private void releaseIfFinished() {
            if (workerFinished && providerFinished) permit.close();
        }

        private void stopTimers() {
            if (firstTokenTimer != null) firstTokenTimer.cancel(false);
            if (totalTimer != null) totalTimer.cancel(false);
        }

        private void activity(StreamingHandle handle) {
            if (firstTokenTimer != null) firstTokenTimer.cancel(false);
            if (handle != null) {
                providerHandle = handle;
                if (cancelled) cancelProvider();
            }
        }

        private void cancelProvider() {
            // Best effort only. A successful cancel() call is not proof that HTTP has ended.
            if (providerHandle != null && providerHandle != cancellationRequested) {
                StreamingHandle handle = providerHandle;
                cancellationRequested = handle;
                executor.cancelBestEffort(() -> {
                    try { if (!handle.isCancelled()) handle.cancel(); }
                    catch (RuntimeException ignored) { }
                });
            }
            if (worker != null) worker.cancel(true);
        }

        private AiTelemetry.Scope activate() { return request == null ? null : request.activate(); }

        private void finishStage(Throwable error) {
            if (stage == null) return;
            if (error != null) stage.failure(error);
            stage.close();
        }

        private synchronized void timeout() {
            if (terminal) return;
            terminal = true;
            cancelled = true;
            stopTimers();
            ProviderCallException error = ProviderCallException.timeout();
            finishStage(error);
            cancelProvider();
            try (AiTelemetry.Scope ignored = activate()) { downstream.onError(error); }
            finally { unregisterCancellation(); }
        }

        @Override public synchronized void cancel() {
            if (terminal) return;
            terminal = true;
            cancelled = true;
            stopTimers();
            finishStage(ProviderCallException.cancelled());
            cancelProvider();
            unregisterCancellation();
        }

        @Override public synchronized boolean isCancelled() { return cancelled; }

        @Override public void onPartialResponse(String text) {
            onPartialResponse(new PartialResponse(text), null);
        }
        @Override public synchronized void onPartialResponse(PartialResponse partial, PartialResponseContext context) {
            activity(context == null ? null : context.streamingHandle());
            if (terminal) return;
            try (AiTelemetry.Scope ignored = activate()) {
                downstream.onPartialResponse(partial, new PartialResponseContext(this));
            }
        }
        @Override public void onPartialThinking(PartialThinking thinking) { onPartialThinking(thinking, null); }
        @Override public synchronized void onPartialThinking(PartialThinking thinking, PartialThinkingContext context) {
            activity(context == null ? null : context.streamingHandle());
            if (terminal) return;
            try (AiTelemetry.Scope ignored = activate()) {
                downstream.onPartialThinking(thinking, new PartialThinkingContext(this));
            }
        }
        @Override public void onPartialToolCall(PartialToolCall toolCall) { onPartialToolCall(toolCall, null); }
        @Override public synchronized void onPartialToolCall(PartialToolCall toolCall, PartialToolCallContext context) {
            activity(context == null ? null : context.streamingHandle());
            if (terminal) return;
            try (AiTelemetry.Scope ignored = activate()) {
                downstream.onPartialToolCall(toolCall, new PartialToolCallContext(this));
            }
        }
        @Override public synchronized void onCompleteToolCall(CompleteToolCall toolCall) {
            activity(null);
            if (terminal) return;
            try (AiTelemetry.Scope ignored = activate()) { downstream.onCompleteToolCall(toolCall); }
        }
        @Override public synchronized void onUnmappedRawEvent(Object event) {
            if (terminal) return;
            try (AiTelemetry.Scope ignored = activate()) { downstream.onUnmappedRawEvent(event); }
        }

        @Override public synchronized void onCompleteResponse(ChatResponse response) {
            providerFinished = true;
            releaseIfFinished();
            if (terminal) return;
            terminal = true;
            stopTimers();
            finishStage(null);
            try (AiTelemetry.Scope ignored = activate()) { downstream.onCompleteResponse(response); }
            catch (Throwable deliveryError) {
                // AiServices can fail while decoding a result or executing a tool round.
                // The provider is terminal, but its consumer still needs an error to roll back.
                try (AiTelemetry.Scope ignored = activate()) { downstream.onError(deliveryError); }
            }
            finally { unregisterCancellation(); }
        }

        @Override public synchronized void onError(Throwable error) {
            providerFinished = true;
            releaseIfFinished();
            if (terminal) return;
            terminal = true;
            stopTimers();
            finishStage(error);
            try (AiTelemetry.Scope ignored = activate()) { downstream.onError(error); }
            finally { unregisterCancellation(); }
        }
    }
}
