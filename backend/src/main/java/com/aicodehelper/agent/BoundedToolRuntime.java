package com.aicodehelper.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.DeserializationFeature;
import jakarta.annotation.PreDestroy;
import org.jsoup.HttpStatusException;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.Callable;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.FutureTask;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

import static com.aicodehelper.agent.ToolError.Code.*;

/**
 * The executor boundary used by the actual LangChain4j tool loop. Budgets belong to one request,
 * never to a thread or a whole conversation. Captured Scope objects survive async callbacks safely.
 */
@Component
public class BoundedToolRuntime implements AutoCloseable {
    private static final int MAX_PAYLOAD_BYTES = 131_072;
    private final AgentProperties config;
    private final ObjectMapper mapper;
    private final List<ToolRuntimeObserver> observers;
    private final ConcurrentHashMap<String, Scope> scopes = new ConcurrentHashMap<>();
    private final ExecutorService workers = Executors.newVirtualThreadPerTaskExecutor();
    // Physical calls retain their permit until the delegate actually exits, even after timeout/cancel.
    private final Semaphore physicalCalls = new Semaphore(64);

    public BoundedToolRuntime(AgentProperties config, ObjectMapper mapper, List<ToolRuntimeObserver> observers) {
        config.validate();
        this.config = config;
        this.mapper = mapper;
        this.observers = List.copyOf(observers);
    }

    public Scope begin(String conversationKey) {
        if (conversationKey == null || conversationKey.isBlank()) throw new IllegalArgumentException("Conversation key is required");
        List<Consumer<ToolResult>> callbacks = new ArrayList<>();
        for (ToolRuntimeObserver observer : observers) {
            try {
                Consumer<ToolResult> callback = observer.forRequest(conversationKey);
                if (callback != null) callbacks.add(callback);
            } catch (RuntimeException ignored) { /* Observability must never reject a request. */ }
        }
        Scope scope = new Scope(conversationKey, List.copyOf(callbacks));
        if (scopes.putIfAbsent(conversationKey, scope) != null) {
            throw new IllegalStateException("A tool request is already active for this conversation");
        }
        return scope;
    }

    public Scope scope(Object memoryId) { return memoryId == null ? null : scopes.get(memoryId.toString()); }
    public int activeScopes() { return scopes.size(); }

    public ToolResult execute(Scope scope, ToolRequest request, ToolPolicy policy, Callable<Object> action) {
        return execute(scope, request, policy, action, false);
    }

    /** Discovery has no model-visible tool name and no selection step, but consumes the same time budget. */
    public ToolResult discover(Scope scope, Callable<Object> action) {
        return execute(scope, new ToolRequest("mcp_discovery", "{}"),
                config.policy("mcp_discovery", false), action, true);
    }

    private ToolResult execute(Scope scope, ToolRequest request, ToolPolicy policy, Callable<Object> action, boolean discovery) {
        Instant started = Instant.now();
        long startNanos = System.nanoTime();
        if (scope == null) return result(null, request, started, startNanos, null, REQUEST_SCOPE_MISSING, 0);
        // Tools in the same request share a cumulative execution budget, including retries.
        synchronized (scope.executionLock) {
            ToolError.Code denied = discovery ? null : reserve(scope, request, policy);
            if (denied != null) return result(scope, request, started, startNanos, null, denied, 0);
            int retries = 0;
            while (true) {
                if (scope.cancelled.get() || scope.closed.get()) return result(scope, request, started, startNanos, null, USER_CANCELLED, retries);
                long remaining = config.getMaxTotalToolTime().toNanos() - scope.toolNanos;
                if (remaining <= 0) return result(scope, request, started, startNanos, null, TOTAL_TOOL_BUDGET_EXCEEDED, retries);
                long timeout = Math.min(Math.min(policy.timeout().toNanos(), config.getToolTimeout().toNanos()), remaining);
                long attemptStart = System.nanoTime();
                Future<Object> future = submit(action);
                if (future == null) return result(scope, request, started, startNanos, null, PROVIDER_UNAVAILABLE, retries);
                scope.running.set(future);
                if (scope.cancelled.get() || scope.closed.get()) future.cancel(true);
                ToolError.Code failure;
                try {
                    Object payload = validatePayload(future.get(timeout, TimeUnit.NANOSECONDS), policy.jsonResponse());
                    if (scope.cancelled.get() || scope.closed.get()) return result(scope, request, started, startNanos, null, USER_CANCELLED, retries);
                    return result(scope, request, started, startNanos, payload, null, retries);
                } catch (TimeoutException error) {
                    future.cancel(true);
                    failure = timeout == remaining ? TOTAL_TOOL_BUDGET_EXCEEDED : TOOL_TIMEOUT;
                } catch (InterruptedException error) {
                    future.cancel(true);
                    Thread.currentThread().interrupt();
                    failure = scope.cancelled.get() ? USER_CANCELLED : INTERRUPTED;
                } catch (CancellationException error) {
                    failure = USER_CANCELLED;
                } catch (ExecutionException error) {
                    failure = classify(error.getCause());
                } catch (RuntimeException error) {
                    failure = classify(error);
                } finally {
                    scope.toolNanos += System.nanoTime() - attemptStart;
                    scope.running.compareAndSet(future, null);
                }
                if (scope.cancelled.get() || scope.closed.get()) failure = USER_CANCELLED;
                if (discovery || !ToolError.of(failure).retryable() || retries >= config.getMaxToolRetries()) {
                    return result(scope, request, started, startNanos, null, failure, retries);
                }
                if (scope.toolNanos >= config.getMaxTotalToolTime().toNanos()) {
                    return result(scope, request, started, startNanos, null, TOTAL_TOOL_BUDGET_EXCEEDED, retries);
                }
                retries++;
            }
        }
    }

    private Future<Object> submit(Callable<Object> action) {
        if (!physicalCalls.tryAcquire()) return null;
        FutureTask<Object> task = new FutureTask<>(action);
        try {
            workers.execute(() -> {
                try { task.run(); }
                finally { physicalCalls.release(); }
            });
            return task;
        } catch (RejectedExecutionException unavailable) {
            physicalCalls.release();
            return null;
        }
    }

    public int inFlightActions() { return 64 - physicalCalls.availablePermits(); }

    private ToolError.Code reserve(Scope scope, ToolRequest request, ToolPolicy policy) {
        if (scope.cancelled.get() || scope.closed.get()) return USER_CANCELLED;
        if (scope.steps >= config.getMaxSteps()) return AGENT_STEP_LIMIT;
        scope.steps++;
        if (request == null || request.toolName() == null || policy == null
                || !request.toolName().equals(policy.toolName()) || !config.getAllowedTools().contains(request.toolName())) return UNAUTHORIZED_TOOL;
        if (policy.riskLevel() != ToolPolicy.RiskLevel.READ_ONLY) return RISK_DENIED;
        if (policy.timeout() == null || policy.timeout().isNegative() || policy.timeout().isZero()) return TOOL_TIMEOUT;
        String canonical;
        try {
            String arguments = request.arguments();
            if (arguments == null || arguments.length() > 32_768) return MALFORMED_ARGUMENTS;
            JsonNode node = mapper.reader().with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).readTree(arguments);
            if (node == null || !node.isObject()) return MALFORMED_ARGUMENTS;
            canonical = request.toolName() + ":" + mapper.writeValueAsString(sorted(node));
        } catch (IOException | IllegalArgumentException error) { return MALFORMED_ARGUMENTS; }
        if (!scope.signatures.add(canonical)) return REPEATED_TOOL_CALL;
        int calls = scope.calls.getOrDefault(request.toolName(), 0);
        if (calls >= Math.min(policy.maxCalls(), config.getMaxCallsPerTool())) return TOOL_CALL_LIMIT;
        scope.calls.put(request.toolName(), calls + 1);
        return null;
    }

    private Object sorted(JsonNode node) {
        if (node.isObject()) {
            Map<String, Object> object = new TreeMap<>();
            node.fields().forEachRemaining(entry -> object.put(entry.getKey(), sorted(entry.getValue())));
            return object;
        }
        if (node.isArray()) {
            List<Object> array = new ArrayList<>();
            node.forEach(child -> array.add(sorted(child)));
            return array;
        }
        return node;
    }

    private Object validatePayload(Object value, boolean jsonResponse) {
        if (value == null) throw new ToolFailureException(EMPTY_RESPONSE);
        try {
            if (value instanceof String text) {
                String stripped = text.strip();
                if (stripped.isEmpty()) throw new ToolFailureException(EMPTY_RESPONSE);
                if (stripped.getBytes(StandardCharsets.UTF_8).length > MAX_PAYLOAD_BYTES) throw new ToolFailureException(MALFORMED_RESPONSE);
                if (jsonResponse || stripped.startsWith("{") || stripped.startsWith("[")) {
                    value = mapper.reader().with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).readTree(stripped);
                }
            }
            JsonNode node = mapper.valueToTree(value);
            if (node == null || node.isNull() || ((node.isArray() || node.isObject()) && node.isEmpty())
                    || (node.isTextual() && node.asText().isBlank())) throw new ToolFailureException(EMPTY_RESPONSE);
            if (mapper.writeValueAsBytes(value).length > MAX_PAYLOAD_BYTES) throw new ToolFailureException(MALFORMED_RESPONSE);
            return value;
        } catch (IOException | IllegalArgumentException error) { throw new ToolFailureException(MALFORMED_RESPONSE); }
    }

    public static ToolError.Code classify(Throwable error) {
        for (Throwable current = error; current != null; current = current.getCause()) {
            if (current instanceof ToolFailureException typed) return typed.code();
            if (current instanceof SocketTimeoutException || current instanceof TimeoutException) return TOOL_TIMEOUT;
            if (current instanceof dev.langchain4j.exception.TimeoutException) return TOOL_TIMEOUT;
            if (current instanceof HttpStatusException http) return http.getStatusCode() >= 500 ? HTTP_5XX : TOOL_FAILED;
            if (current instanceof dev.langchain4j.exception.HttpException http) return http.statusCode() >= 500 ? HTTP_5XX : TOOL_FAILED;
            if (current instanceof dev.langchain4j.exception.InternalServerException) return HTTP_5XX;
            if (current instanceof InterruptedException) return INTERRUPTED;
            if (current instanceof IOException) return PROVIDER_UNAVAILABLE;
            String type = current.getClass().getSimpleName();
            if (type.equals("ToolArgumentsException") || type.equals("JsonProcessingException")) return MALFORMED_ARGUMENTS;
        }
        return TOOL_FAILED;
    }

    private ToolResult result(Scope scope, ToolRequest request, Instant started, long startNanos,
                              Object payload, ToolError.Code code, int retries) {
        int bytes = 0;
        if (payload != null) {
            try { bytes = mapper.writeValueAsBytes(payload).length; }
            catch (IOException ignored) { code = MALFORMED_RESPONSE; payload = null; }
        }
        ToolResult result = new ToolResult(request, code == null, payload, code == null ? null : ToolError.of(code),
                new ToolMetadata(started, Instant.now(), Duration.ofNanos(System.nanoTime() - startNanos).toMillis(), retries, bytes));
        if (scope != null) {
            synchronized (scope.results) { scope.results.add(result); }
            for (Consumer<ToolResult> observer : scope.observers) {
                try { observer.accept(result); } catch (RuntimeException ignored) { /* Telemetry cannot change tool outcomes. */ }
            }
        }
        return result;
    }

    public String json(ToolResult result) {
        // Arguments may contain private user text: only the result contract, not raw arguments, reaches persisted tool memory.
        Map<String, Object> safe = new HashMap<>();
        safe.put("toolName", result.request() == null ? "unknown" : result.request().toolName());
        safe.put("success", result.success());
        safe.put("payload", result.payload());
        safe.put("error", result.error());
        safe.put("metadata", result.metadata());
        try { return mapper.writeValueAsString(safe); }
        catch (IOException impossible) { return "{\"success\":false,\"error\":{\"code\":\"MALFORMED_RESPONSE\"}}"; }
    }

    @PreDestroy
    @Override public void close() {
        scopes.values().forEach(Scope::close);
        workers.shutdownNow();
    }

    public final class Scope implements AutoCloseable {
        private final String key;
        private final Object executionLock = new Object();
        private final AtomicBoolean cancelled = new AtomicBoolean();
        private final AtomicBoolean closed = new AtomicBoolean();
        private final AtomicReference<Future<?>> running = new AtomicReference<>();
        private final Set<String> signatures = new HashSet<>();
        private final Map<String, Integer> calls = new HashMap<>();
        private final List<ToolResult> results = new ArrayList<>();
        private final List<Consumer<ToolResult>> observers;
        private volatile int steps;
        private long toolNanos;

        private Scope(String key, List<Consumer<ToolResult>> observers) { this.key = key; this.observers = observers; }
        public int steps() { return steps; }
        public List<ToolResult> results() { synchronized (results) { return List.copyOf(results); } }
        public void cancel() {
            cancelled.set(true);
            Future<?> future = running.get();
            if (future != null) future.cancel(true);
        }
        @Override public void close() {
            if (closed.compareAndSet(false, true)) {
                cancel();
                scopes.remove(key, this);
            }
        }
    }
}
