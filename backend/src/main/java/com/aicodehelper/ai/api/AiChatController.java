package com.aicodehelper.ai.api;

import com.aicodehelper.ai.AiChatService;
import com.aicodehelper.ai.AiExecutionRegistry;
import com.aicodehelper.ai.ChatStreamTicketService;
import com.aicodehelper.ai.ModelRuntimeInfo;
import com.aicodehelper.agent.BoundedToolRuntime;
import com.aicodehelper.observability.AiTelemetry;
import com.aicodehelper.ai.provider.ProviderCallExecutor;
import com.aicodehelper.retrieval.RetrievalProperties;
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
import java.util.concurrent.ExecutorService;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

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
    private final BoundedToolRuntime tools;
    private final AiTelemetry telemetry;
    private final ProviderCallExecutor providers;
    private final RetrievalProperties retrieval;
    private final ExecutorService aiStreamExecutor;

    public AiChatController(
            AiChatService ai,
            ClientIdentityService identities,
            ChatStreamTicketService tickets,
            AiExecutionRegistry executions,
            ConversationMemoryManager memoryManager,
            ConversationMemoryRegistry memories,
            ModelRuntimeInfo modelInfo,
            AppProperties properties,
            BoundedToolRuntime tools,
            AiTelemetry telemetry,
            ProviderCallExecutor providers,
            RetrievalProperties retrieval,
            ExecutorService aiStreamExecutor
    ) {
        this.ai = ai;
        this.identities = identities;
        this.tickets = tickets;
        this.executions = executions;
        this.memoryManager = memoryManager;
        this.memories = memories;
        this.modelInfo = modelInfo;
        this.properties = properties;
        this.tools = tools;
        this.telemetry = telemetry;
        this.providers = providers;
        this.retrieval = retrieval;
        this.aiStreamExecutor = aiStreamExecutor;
    }

    @PostMapping("/chat")
    public ChatResponse chat(
            @Valid @RequestBody ChatRequest body,
            HttpServletRequest request,
            HttpServletResponse response
    ) {
        return observed("chat", () -> {
            ai.validateMessage(body.message());
            String key = conversationKey(body.userId(), body.memoryId(), request, response);
            try (AiExecutionRegistry.Lease ignored = executions.acquire(key);
                 BoundedToolRuntime.Scope toolScope = tools.begin(key)) {
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
        });
    }

    @PostMapping("/rag")
    public RagResponse rag(
            @Valid @RequestBody ChatRequest body,
            HttpServletRequest request,
            HttpServletResponse response
    ) {
        return observed("rag", () -> {
            String key = conversationKey(body.userId(), body.memoryId(), request, response);
            try (AiExecutionRegistry.Lease ignored = executions.acquire(key);
                 BoundedToolRuntime.Scope toolScope = tools.begin(key)) {
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
        });
    }

    @PostMapping("/report")
    public LearningReport report(
            @Valid @RequestBody ChatRequest body,
            HttpServletRequest request,
            HttpServletResponse response
    ) {
        return observed("report", () -> {
            String key = conversationKey(body.userId(), body.memoryId(), request, response);
            try (AiExecutionRegistry.Lease ignored = executions.acquire(key);
                 BoundedToolRuntime.Scope toolScope = tools.begin(key)) {
                return ai.report(key, body.memoryId(), body.message());
            }
        });
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
        AiTelemetry.RequestObservation observation = startObservation("sse");
        try (AiTelemetry.Scope activation = observation.activate()) {
        ChatStreamTicketService.TicketDescriptor descriptor = tickets.describe(streamId);
        String key = conversationKey(null, descriptor.memoryId(), request, response);
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
                    lease,
                    observation
            );
        } catch (RuntimeException error) {
            lease.close();
            throw error;
        }
        } catch (RuntimeException error) {
            observation.finish(status(error));
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
        AiTelemetry.RequestObservation observation = startObservation("sse");
        try (AiTelemetry.Scope activation = observation.activate()) {
        rejectCrossSiteLegacyRequest(request);
        ai.validateMessage(message);
        response.setHeader("Deprecation", "true");
        response.setHeader("Sunset", DateTimeFormatter.RFC_1123_DATE_TIME.format(
                ZonedDateTime.ofInstant(Instant.parse("2027-01-01T00:00:00Z"), ZoneOffset.UTC)));
        response.setHeader(HttpHeaders.WARNING, "299 - \"Use POST /api/ai/chat/streams\"");
        String key = conversationKey(userId, memoryId, request, response);
        AiExecutionRegistry.Lease lease = executions.acquire(key);
        try {
            memoryManager.prepare(key);
            ConversationMemoryRegistry.RewindSnapshot snapshot = memories.snapshot(key);
            return stream(key, memoryId, message, snapshot, lease, observation);
        } catch (RuntimeException error) {
            lease.close();
            throw error;
        }
        } catch (RuntimeException error) {
            observation.finish(status(error));
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
            AiExecutionRegistry.Lease lease,
            AiTelemetry.RequestObservation observation
    ) {
        SseEmitter emitter = new SseEmitter(properties.getAi().getStreamTimeout().toMillis());
        BoundedToolRuntime.Scope toolScope = tools.begin(conversationKey);
        StreamLifecycle lifecycle = new StreamLifecycle(emitter, conversationKey, memorySnapshot, lease,
                toolScope, observation);
        observation.streamOpened();

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
                        lifecycle.fail(error instanceof ApiException apiError ? apiError.code() : "AI_STREAM_ERROR");
                    })
                    .start();
        } catch (RuntimeException | IOException error) {
            log.warn("Unable to start SSE response errorType={}", error.getClass().getSimpleName());
            lifecycle.fail(error instanceof ApiException apiError ? apiError.code() : "AI_STREAM_ERROR");
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
        private final BoundedToolRuntime.Scope toolScope;
        private final AiTelemetry.RequestObservation observation;
        private final AiTelemetry.StageTimer streamTimer;
        private final AtomicReference<StreamingHandle> currentHandle = new AtomicReference<>();
        private final AtomicBoolean terminal = new AtomicBoolean();

        private StreamLifecycle(
                SseEmitter emitter,
                String conversationKey,
                ConversationMemoryRegistry.RewindSnapshot memorySnapshot,
                AiExecutionRegistry.Lease lease,
                BoundedToolRuntime.Scope toolScope,
                AiTelemetry.RequestObservation observation
        ) {
            this.emitter = emitter;
            this.conversationKey = conversationKey;
            this.memorySnapshot = memorySnapshot;
            this.lease = lease;
            this.toolScope = toolScope;
            this.observation = observation;
            this.streamTimer = observation.stage("stream");
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
                try {
                    memories.restore(conversationKey, memorySnapshot);
                    emitter.send(SseEmitter.event().name("error").data("MEMORY_SAVE_FAILED"));
                }
                catch (IOException ignored) { }
                finally { finish("error"); }
                return;
            }
            try {
                emitter.send(SseEmitter.event().name("done").data("[DONE]"));
            } catch (IOException ignored) {
                // Generation completed; a closed browser does not roll back a completed model turn.
            } finally {
                finish("success");
            }
        }

        private void fail(String code) {
            terminate(code.contains("TIMEOUT") ? "timeout" : "error", code);
        }

        private void disconnect() {
            terminate("cancelled", null);
        }

        private void terminate(String status, String errorCode) {
            if (!terminal.compareAndSet(false, true)) return;
            // Spring can invoke these callbacks while holding an emitter or servlet
            // lock. A provider callback holds its own guard while sending SSE data.
            // Claim termination now, but fence callbacks on another worker so the
            // framework callback can return and release its locks without waiting.
            Runnable cleanup = () -> {
                try (AiTelemetry.Scope ignored = observation.activate()) {
                    // A guard may be delivering a tool call. Interrupt the tool before
                    // waiting for the provider callback fence, then restore memory.
                    toolScope.cancel();
                    providers.cancelRequest(observation);
                    cancelHandle(currentHandle.get());
                    memories.restore(conversationKey, memorySnapshot);
                    if (errorCode != null) {
                        try { emitter.send(SseEmitter.event().name("error").data(errorCode)); }
                        catch (IOException | IllegalStateException disconnected) { /* Client already left. */ }
                    }
                } catch (RuntimeException error) {
                    log.warn("SSE terminal cleanup failed errorType={}", error.getClass().getSimpleName());
                } finally {
                    finish(status);
                }
            };
            try { aiStreamExecutor.execute(cleanup); }
            catch (RejectedExecutionException shuttingDown) {
                // Shutdown must not strand the lease or run cleanup inline under
                // framework locks. At most one cleanup exists per admitted stream.
                Thread.ofVirtual().name("ai-stream-terminal-cleanup").start(cleanup);
            }
        }

        private void finish(String status) {
            try (AiTelemetry.Scope ignored = observation.activate()) {
                try { toolScope.close(); }
                finally {
                    lease.close();
                    try {
                        if (!"success".equals(status)) streamTimer.failure(new IllegalStateException(status));
                        streamTimer.close();
                        observation.finish(status);
                    } finally { emitter.complete(); }
                }
            }
        }
    }

    private String conversationKey(UUID userId, String memoryId, HttpServletRequest request,
                                   HttpServletResponse response) {
        try (AiTelemetry.StageTimer stage = telemetry.stage("auth.session")) {
            try {
                return identities.conversationKey(userId, memoryId, request, response);
            } catch (RuntimeException error) {
                stage.failure(error);
                throw error;
            }
        }
    }

    private <T> T observed(String mode, Supplier<T> action) {
        AiTelemetry.RequestObservation observation = startObservation(mode);
        try (AiTelemetry.Scope activation = observation.activate()) {
            try {
                T result = action.get();
                observation.finish("success");
                return result;
            } catch (RuntimeException error) {
                observation.finish(status(error));
                throw error;
            }
        }
    }

    private String status(Throwable error) {
        if (error instanceof ApiException apiError) {
            if (apiError.code().contains("TIMEOUT")) return "timeout";
            if (apiError.status() == HttpStatus.TOO_MANY_REQUESTS
                    || apiError.status() == HttpStatus.CONFLICT) return "rejected";
        }
        return "error";
    }

    private AiTelemetry.RequestObservation startObservation(String mode) {
        return telemetry.startRequest(mode).describe(modelInfo.chatModel(), retrieval.getMode());
    }
}
