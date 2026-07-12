package com.aicodehelper.ai;

import dev.langchain4j.model.chat.listener.ChatModelErrorContext;
import dev.langchain4j.model.chat.listener.ChatModelListener;
import dev.langchain4j.model.chat.listener.ChatModelRequestContext;
import dev.langchain4j.model.chat.listener.ChatModelResponseContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.TimeUnit;

public final class SafeChatModelListener implements ChatModelListener {

    private static final Logger log = LoggerFactory.getLogger(SafeChatModelListener.class);
    private static final String START_NANOS = SafeChatModelListener.class.getName() + ".startNanos";

    @Override
    public void onRequest(ChatModelRequestContext context) {
        context.attributes().put(START_NANOS, System.nanoTime());
        log.info("AI request started provider={} model={} messageCount={}",
                context.modelProvider(),
                context.chatRequest().modelName(),
                context.chatRequest().messages().size());
    }

    @Override
    public void onResponse(ChatModelResponseContext context) {
        log.info("AI request completed provider={} model={} durationMs={} finishReason={} totalTokens={}",
                context.modelProvider(),
                context.chatResponse().modelName(),
                durationMillis(context.attributes().get(START_NANOS)),
                context.chatResponse().finishReason(),
                context.chatResponse().tokenUsage() == null ? null : context.chatResponse().tokenUsage().totalTokenCount());
    }

    @Override
    public void onError(ChatModelErrorContext context) {
        log.warn("AI request failed provider={} durationMs={} errorType={}",
                context.modelProvider(),
                durationMillis(context.attributes().get(START_NANOS)),
                context.error().getClass().getSimpleName());
    }

    private long durationMillis(Object startNanos) {
        if (startNanos instanceof Long start) {
            return TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);
        }
        return -1;
    }
}
