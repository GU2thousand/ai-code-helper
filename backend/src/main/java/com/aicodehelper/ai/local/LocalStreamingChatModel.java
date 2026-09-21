package com.aicodehelper.ai.local;

import dev.langchain4j.model.ModelProvider;
import dev.langchain4j.model.chat.StreamingChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.chat.response.PartialResponse;
import dev.langchain4j.model.chat.response.PartialResponseContext;
import dev.langchain4j.model.chat.response.StreamingHandle;
import dev.langchain4j.model.chat.response.StreamingChatResponseHandler;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.CancellationException;
import java.util.concurrent.atomic.AtomicBoolean;

public final class LocalStreamingChatModel implements StreamingChatModel {

    private static final int CHUNK_CODE_POINTS = 10;
    private final ExecutorService executor;
    private final LocalChatModel delegate = new LocalChatModel();

    public LocalStreamingChatModel(ExecutorService executor) {
        this.executor = executor;
    }

    @Override
    public void doChat(ChatRequest request, StreamingChatResponseHandler handler) {
        LocalStreamingHandle handle = new LocalStreamingHandle();
        executor.execute(() -> {
            try {
                ChatResponse response = delegate.doChat(request);
                stream(response.aiMessage().text(), handler, handle);
                if (handle.isCancelled()) {
                    // Cancellation stops generation, but the wrapper still needs a terminal
                    // acknowledgement to release capacity once generation has actually stopped.
                    // Its callback gate suppresses this signal after a user cancellation.
                    handler.onError(new CancellationException("Local stream cancelled"));
                } else {
                    handler.onCompleteResponse(response);
                }
            } catch (Throwable error) {
                handler.onError(error);
            }
        });
    }

    private void stream(String text, StreamingChatResponseHandler handler, LocalStreamingHandle handle) {
        int index = 0;
        while (index < text.length() && !handle.isCancelled()) {
            int remaining = text.codePointCount(index, text.length());
            int count = Math.min(CHUNK_CODE_POINTS, remaining);
            int end = text.offsetByCodePoints(index, count);
            handler.onPartialResponse(
                    new PartialResponse(text.substring(index, end)),
                    new PartialResponseContext(handle)
            );
            index = end;
        }
    }

    @Override
    public ModelProvider provider() {
        return ModelProvider.OTHER;
    }

    private static final class LocalStreamingHandle implements StreamingHandle {
        private final AtomicBoolean cancelled = new AtomicBoolean();

        @Override
        public void cancel() {
            cancelled.set(true);
        }

        @Override
        public boolean isCancelled() {
            return cancelled.get();
        }
    }
}
