package com.aicodehelper.ai.api;

import com.aicodehelper.ai.AiChatService;
import com.aicodehelper.ai.AiExecutionRegistry;
import com.aicodehelper.ai.ChatStreamTicketService;
import com.aicodehelper.ai.ModelRuntimeInfo;
import com.aicodehelper.config.AppProperties;
import com.aicodehelper.error.ApiException;
import com.aicodehelper.memory.ConversationMemoryRegistry;
import com.aicodehelper.memory.ConversationMemoryManager;
import com.aicodehelper.user.ClientIdentityService;
import dev.langchain4j.model.chat.response.StreamingHandle;
import dev.langchain4j.service.TokenStream;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

@RestController
@Validated
@RequestMapping("/api/ai")
public class AiChatController {

    private static final Logger log = LoggerFactory.getLogger(AiChatController.class);

    private final AiChatService ai;
    private final ClientIdentityService identities;
    private final ChatStreamTicketService tickets;
    private final AiExecutionRegistry executions;
    private final ConversationMemoryManager memoryManager;
    private final ConversationMemoryRegistry memories;
    private final ModelRuntimeInfo modelInfo;
    private final AppProperties properties;

    public AiChatController(
            AiChatService ai,
            ClientIdentityService identities,
            ChatStreamTicketService tickets,
            AiExecutionRegistry executions,
            ConversationMemoryManager memoryManager,
            ConversationMemoryRegistry memories,
            ModelRuntimeInfo modelInfo,
            AppProperties properties
    ) {
        this.ai = ai;
        this.identities = identities;
        this.tickets = tickets;
        this.executions = executions;
        this.memoryManager = memoryManager;
        this.memories = memories;
        this.modelInfo = modelInfo;
        this.properties = properties;
    }

    @PostMapping("/chat")
    public ChatResponse chat(
            @Valid @RequestBody ChatRequest body,
            HttpServletRequest request,
            HttpServletResponse response
    ) {
        ai.validateMessage(body.message());
        String key = identities.conversationKey(body.userId(), body.memoryId(), request, response);
        try (AiExecutionRegistry.Lease ignored = executions.acquire(key)) {
            memoryManager.prepare(key);
            ConversationMemoryRegistry.RewindSnapshot snapshot = Boolean.TRUE.equals(body.regenerate())
                    ? memories.rewindLastTurnWithSnapshot(key)
                    : memories.snapshot(key);
            try {
                ChatResponse result = ai.chat(key, body.memoryId(), body.message());
                memories.commit(key);
                return result;
            } catch (RuntimeException error) {
                memories.restore(key, snapshot);
                throw error;
            }
        }
    }

    @PostMapping("/rag")
    public RagResponse rag(
            @Valid @RequestBody ChatRequest body,
            HttpServletRequest request,
            HttpServletResponse response
    ) {
        String key = identities.conversationKey(body.userId(), body.memoryId(), request, response);
        try (AiExecutionRegistry.Lease ignored = executions.acquire(key)) {
            memoryManager.prepare(key);
            ConversationMemoryRegistry.RewindSnapshot snapshot = memories.snapshot(key);
            try {
                RagResponse result = ai.rag(key, body.memoryId(), body.message());
                memories.commit(key);
                return result;
            } catch (RuntimeException error) {
                memories.restore(key, snapshot);
                throw error;
            }
        }
    }

    @PostMapping("/report")
    public LearningReport report(
            @Valid @RequestBody ChatRequest body,
            HttpServletRequest request,
            HttpServletResponse response
    ) {
        String key = identities.conversationKey(body.userId(), body.memoryId(), request, response);
        try (AiExecutionRegistry.Lease ignored = executions.acquire(key)) {
            return ai.report(key, body.memoryId(), body.message());
        }
    }

    @PostMapping("/chat/streams")
    @ResponseStatus(HttpStatus.CREATED)
    public StreamTicketResponse createStream(
            @Valid @RequestBody ChatRequest body,
            HttpServletRequest request,
            HttpServletResponse response
    ) {
        ai.validateMessage(body.message());
        String key = identities.conversationKey(body.userId(), body.memoryId(), request, response);
        ChatStreamTicketService.StreamTicket ticket = tickets.create(
                key,
                body.memoryId(),
                body.message(),
                Boolean.TRUE.equals(body.regenerate())
        );
        return new StreamTicketResponse(
                ticket.id(),
                "/api/ai/chat/streams/" + ticket.id(),
                ticket.expiresAt()
        );
    }

    @GetMapping(value = "/chat/streams/{streamId}", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter consumeStream(
            @PathVariable UUID streamId,
            HttpServletRequest request,
            HttpServletResponse response
    ) {
        ChatStreamTicketService.TicketDescriptor descriptor = tickets.describe(streamId);
        String key = identities.conversationKey(null, descriptor.memoryId(), request, response);
        AiExecutionRegistry.Lease lease = executions.acquire(key);
        try {
            ChatStreamTicketService.StreamTicket ticket = tickets.claim(streamId, key);
            memoryManager.prepare(ticket.conversationKey());
            ConversationMemoryRegistry.RewindSnapshot snapshot = ticket.regenerate()
                    ? memories.rewindLastTurnWithSnapshot(ticket.conversationKey())
                    : memories.snapshot(ticket.conversationKey());
            return stream(
                    ticket.conversationKey(),
                    ticket.memoryId(),
                    ticket.message(),
                    snapshot,
                    lease
            );
        } catch (RuntimeException error) {
            lease.close();
            throw error;
        }
    }

    /**
     * Compatibility endpoint for older clients. Prefer POST /chat/streams followed by the ticket URL.
     */
    @Deprecated(forRemoval = false)
    @GetMapping(value = "/chat", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter legacyStream(
            @RequestParam
            @NotBlank
            @Size(max = 128)
            @Pattern(regexp = "[A-Za-z0-9._-]+") String memoryId,
            @RequestParam
            @NotBlank
            @Size(max = 1_000, message = "兼容 GET 接口最多允许 1000 个字符，请改用流式票据接口") String message,
            @RequestParam(required = false) UUID userId,
            HttpServletRequest request,
            HttpServletResponse response
    ) {
        rejectCrossSiteLegacyRequest(request);
        ai.validateMessage(message);
        response.setHeader("Deprecation", "true");
        response.setHeader("Sunset", DateTimeFormatter.RFC_1123_DATE_TIME.format(
                ZonedDateTime.ofInstant(Instant.parse("2027-01-01T00:00:00Z"), ZoneOffset.UTC)));
        response.setHeader(HttpHeaders.WARNING, "299 - \"Use POST /api/ai/chat/streams\"");
        String key = identities.conversationKey(userId, memoryId, request, response);
        AiExecutionRegistry.Lease lease = executions.acquire(key);
        try {
            memoryManager.prepare(key);
            ConversationMemoryRegistry.RewindSnapshot snapshot = memories.snapshot(key);
            return stream(key, memoryId, message, snapshot, lease);
        } catch (RuntimeException error) {
            lease.close();
            throw error;
        }
    }

    private void rejectCrossSiteLegacyRequest(HttpServletRequest request) {
        if ("cross-site".equalsIgnoreCase(request.getHeader("Sec-Fetch-Site"))) {
            throw new ApiException(
                    HttpStatus.FORBIDDEN,
                    "CROSS_SITE_STREAM_REJECTED",
                    "兼容流接口拒绝跨站请求，请改用流式票据接口"
            );
        }
    }

    private SseEmitter stream(
            String conversationKey,
            String memoryId,
            String message,
            ConversationMemoryRegistry.RewindSnapshot memorySnapshot,
            AiExecutionRegistry.Lease lease
    ) {
        SseEmitter emitter = new SseEmitter(properties.getAi().getStreamTimeout().toMillis());
        StreamLifecycle lifecycle = new StreamLifecycle(emitter, conversationKey, memorySnapshot, lease);

        emitter.onTimeout(() -> lifecycle.fail("STREAM_TIMEOUT"));
        emitter.onError(error -> lifecycle.disconnect());
        emitter.onCompletion(lifecycle::disconnect);

        try {
            emitter.send(SseEmitter.event()
                    .name("meta")
                    .reconnectTime(3_000)
                    .data(Map.of(
                            "memoryId", memoryId,
                            "model", modelInfo.chatModel()
                    )));

            TokenStream stream = ai.stream(conversationKey, message);
            stream.onRetrieved(contents -> lifecycle.sendSources(contents.stream().map(ai::source).toList()))
                    .onPartialResponseWithContext((partial, context) -> {
                        lifecycle.capture(context == null ? null : context.streamingHandle());
                        lifecycle.sendChunk(partial.text());
                    })
                    .onPartialThinkingWithContext((thinking, context) ->
                            lifecycle.capture(context == null ? null : context.streamingHandle()))
                    .onPartialToolCallWithContext((toolCall, context) ->
                            lifecycle.capture(context == null ? null : context.streamingHandle()))
                    .onCompleteResponse(response -> lifecycle.complete())
                    .onError(error -> {
                        log.warn("Streaming AI request failed errorType={}", error.getClass().getSimpleName());
                        lifecycle.fail("AI_STREAM_ERROR");
                    })
                    .start();
        } catch (RuntimeException | IOException error) {
            log.warn("Unable to start SSE response errorType={}", error.getClass().getSimpleName());
            lifecycle.fail("AI_STREAM_ERROR");
        }
        return emitter;
    }

    private void cancelHandle(StreamingHandle handle) {
        if (handle != null && !handle.isCancelled()) {
            try {
                handle.cancel();
            } catch (RuntimeException unsupported) {
                log.debug("Streaming provider does not support cancellation errorType={}",
                        unsupported.getClass().getSimpleName());
            }
        }
    }

    private final class StreamLifecycle {
        private final SseEmitter emitter;
        private final String conversationKey;
        private final ConversationMemoryRegistry.RewindSnapshot memorySnapshot;
        private final AiExecutionRegistry.Lease lease;
        private final AtomicReference<StreamingHandle> currentHandle = new AtomicReference<>();
        private final AtomicBoolean terminal = new AtomicBoolean();

        private StreamLifecycle(
                SseEmitter emitter,
                String conversationKey,
                ConversationMemoryRegistry.RewindSnapshot memorySnapshot,
                AiExecutionRegistry.Lease lease
        ) {
            this.emitter = emitter;
            this.conversationKey = conversationKey;
            this.memorySnapshot = memorySnapshot;
            this.lease = lease;
        }

        private void capture(StreamingHandle handle) {
            if (handle == null) {
                return;
            }
            currentHandle.set(handle);
            if (terminal.get()) {
                cancelHandle(handle);
            }
        }

        private void sendChunk(String chunk) {
            if (terminal.get()) {
                return;
            }
            try {
                emitter.send(SseEmitter.event().name("message").data(Map.of("content", chunk)));
            } catch (IOException error) {
                disconnect();
            }
        }

        private void sendSources(java.util.List<RagSource> sources) {
            if (terminal.get()) return;
            try {
                emitter.send(SseEmitter.event().name("sources").data(sources));
            } catch (IOException error) { disconnect(); }
        }

        private synchronized void complete() {
            if (!terminal.compareAndSet(false, true)) {
                return;
            }
            try {
                memories.commit(conversationKey);
            } catch (RuntimeException error) {
                memories.restore(conversationKey, memorySnapshot);
                try { emitter.send(SseEmitter.event().name("error").data("MEMORY_SAVE_FAILED")); }
                catch (IOException ignored) { }
                finally { lease.close(); emitter.complete(); }
                return;
            }
            try {
                emitter.send(SseEmitter.event().name("done").data("[DONE]"));
            } catch (IOException ignored) {
                // Generation completed; a closed browser does not roll back a completed model turn.
            } finally {
                lease.close();
                emitter.complete();
            }
        }

        private void fail(String code) {
            if (!terminal.compareAndSet(false, true)) {
                return;
            }
            cancelHandle(currentHandle.get());
            memories.restore(conversationKey, memorySnapshot);
            try {
                emitter.send(SseEmitter.event().name("error").data(code));
            } catch (IOException ignored) {
                // The browser may already be disconnected.
            } finally {
                lease.close();
                emitter.complete();
            }
        }

        private void disconnect() {
            if (!terminal.compareAndSet(false, true)) {
                return;
            }
            cancelHandle(currentHandle.get());
            memories.restore(conversationKey, memorySnapshot);
            lease.close();
            emitter.complete();
        }
    }
}
