package com.aicodehelper.ai.provider;

import com.aicodehelper.observability.AiTelemetry;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.FutureTask;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.Semaphore;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Deadlines bound application waiting, not necessarily the SDK HTTP operation.
 * A cancelled task retains its permit until its actual worker exits. Streaming
 * permits additionally wait for a provider terminal callback, preventing an SDK
 * that ignores cancellation from creating an unbounded number of remote calls.
 */
public final class ProviderCallExecutor implements AutoCloseable {
    private final ExecutorService workers = Executors.newThreadPerTaskExecutor(
            Thread.ofVirtual().name("ai-provider-", 0).factory());
    private final ScheduledThreadPoolExecutor timers = new ScheduledThreadPoolExecutor(2,
            Thread.ofPlatform().daemon().name("ai-provider-deadline-", 0).factory());
    private final ThreadPoolExecutor cancellation = new ThreadPoolExecutor(0, 2, 30, TimeUnit.SECONDS,
            new SynchronousQueue<>(), Thread.ofPlatform().daemon().name("ai-provider-cancel-", 0).factory(),
            new ThreadPoolExecutor.DiscardPolicy());
    private final Semaphore capacity;
    private final int maximum;
    private final AiTelemetry telemetry;
    private final Map<AiTelemetry.RequestObservation, RequestCancellation> requests = new WeakHashMap<>();

    public ProviderCallExecutor(ProviderProperties properties, AiTelemetry telemetry) {
        maximum = Math.max(1, properties.getMaxInFlight());
        capacity = new Semaphore(maximum);
        this.telemetry = telemetry;
        timers.setRemoveOnCancelPolicy(true);
    }

    public ProviderCallExecutor(ProviderProperties properties) {
        this(properties, AiTelemetry.noop());
    }

    public <T> T call(Duration timeout, Callable<T> operation) {
        Permit permit = acquire();
        Future<T> result = submit(operation, permit::close);
        try {
            return result.get(timeout.toNanos(), TimeUnit.NANOSECONDS);
        } catch (TimeoutException error) {
            result.cancel(true);
            throw ProviderCallException.timeout();
        } catch (InterruptedException error) {
            result.cancel(true);
            Thread.currentThread().interrupt();
            throw ProviderCallException.cancelled();
        } catch (ExecutionException error) {
            if (error.getCause() instanceof RuntimeException cause) throw cause;
            if (error.getCause() instanceof Error cause) throw cause;
            throw new IllegalStateException("Provider call failed", error.getCause());
        }
    }

    Permit acquire() {
        if (!capacity.tryAcquire()) throw ProviderCallException.capacity();
        return new Permit();
    }

    <T> Future<T> submit(Callable<T> operation, Runnable workerFinished) {
        AiTelemetry.RequestObservation request = telemetry.currentRequest();
        FutureTask<T> task = new FutureTask<>(() -> {
            try (AiTelemetry.Scope ignored = request == null ? null : request.activate()) {
                return operation.call();
            }
        }) {
            @Override public void run() {
                try { super.run(); }
                finally { workerFinished.run(); }
            }
        };
        try { workers.execute(task); }
        catch (RejectedExecutionException error) {
            workerFinished.run();
            throw ProviderCallException.capacity();
        }
        return task;
    }

    ScheduledFuture<?> schedule(Duration delay, Runnable action) {
        return timers.schedule(action, delay.toNanos(), TimeUnit.NANOSECONDS);
    }

    void cancelBestEffort(Runnable action) {
        // Even a broken cancel() implementation must not block a deadline thread.
        cancellation.execute(action);
    }

    AiTelemetry.RequestObservation currentRequest() { return telemetry.currentRequest(); }
    AiTelemetry.StageTimer llmStage() { return telemetry.stage("llm"); }

    void registerCancellation(AiTelemetry.RequestObservation request, Runnable hook) {
        if (request == null) return;
        boolean cancelled;
        synchronized (requests) {
            RequestCancellation state = requests.computeIfAbsent(request, ignored -> new RequestCancellation());
            cancelled = state.cancelled;
            if (!cancelled) state.hooks.add(hook);
        }
        if (cancelled) hook.run();
    }

    void unregisterCancellation(AiTelemetry.RequestObservation request, Runnable hook) {
        if (request == null) return;
        synchronized (requests) {
            RequestCancellation state = requests.get(request);
            if (state == null) return;
            state.hooks.remove(hook);
            if (!state.cancelled && state.hooks.isEmpty()) requests.remove(request);
        }
    }

    /**
     * Fence callbacks before the controller restores memory. Each hook waits for
     * any in-progress delivery into AiServices, including its memory writes.
     * A weak tombstone also fences a new tool-round stream racing cancellation.
     */
    public void cancelRequest(AiTelemetry.RequestObservation request) {
        if (request == null) return;
        List<Runnable> hooks;
        synchronized (requests) {
            RequestCancellation state = requests.computeIfAbsent(request, ignored -> new RequestCancellation());
            state.cancelled = true;
            hooks = new ArrayList<>(state.hooks);
            state.hooks.clear();
        }
        hooks.forEach(Runnable::run);
    }

    private static final class RequestCancellation {
        boolean cancelled;
        final Set<Runnable> hooks = new HashSet<>();
    }

    public int inFlightCount() { return maximum - capacity.availablePermits(); }

    @Override public void close() {
        // Do not block Spring shutdown indefinitely on an SDK that ignores interruption.
        timers.shutdownNow();
        cancellation.shutdownNow();
        workers.shutdownNow();
    }

    final class Permit implements AutoCloseable {
        private final AtomicBoolean released = new AtomicBoolean();
        @Override public void close() { if (released.compareAndSet(false, true)) capacity.release(); }
    }
}
