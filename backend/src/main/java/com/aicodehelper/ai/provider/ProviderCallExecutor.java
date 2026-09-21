package com.aicodehelper.ai.provider;

import com.aicodehelper.observability.AiTelemetry;

import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.FutureTask;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
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
    private final Object capacityLock = new Object();
    private final ArrayDeque<Admission> waiting = new ArrayDeque<>();
    private final int maxQueued;
    private final Duration queueTimeout;
    private int active;
    private boolean closed;
    private final int maximum;
    private final AiTelemetry telemetry;
    private final Map<AiTelemetry.RequestObservation, RequestCancellation> requests = new WeakHashMap<>();

    public ProviderCallExecutor(ProviderProperties properties, AiTelemetry telemetry) {
        maximum = Math.max(1, properties.getMaxInFlight());
        maxQueued = Math.max(0, properties.getMaxQueued());
        queueTimeout = properties.getQueueTimeout();
        this.telemetry = telemetry;
        timers.setRemoveOnCancelPolicy(true);
        telemetry.providerCapacity(this::inFlightCount, this::queuedCount);
    }

    public ProviderCallExecutor(ProviderProperties properties) {
        this(properties, AiTelemetry.noop());
    }

    public <T> T call(Duration timeout, Callable<T> operation) {
        long deadline = System.nanoTime() + timeout.toNanos();
        Admission admission = reserve(timeout);
        AtomicBoolean cancelled = new AtomicBoolean();
        java.util.concurrent.atomic.AtomicReference<Future<T>> running = new java.util.concurrent.atomic.AtomicReference<>();
        AiTelemetry.RequestObservation request = currentRequest();
        // One hook spans queueing, worker publication and result delivery. It never
        // interrupts a caller thread that might already have moved on to another request.
        Runnable cancel = () -> {
            cancelled.set(true);
            admission.cancel();
            Future<T> task = running.get();
            if (task != null) task.cancel(true);
        };
        registerCancellation(request, cancel);
        try {
            Permit permit = admission.await();
            if (cancelled.get() || Thread.currentThread().isInterrupted() || deadline - System.nanoTime() <= 0) {
                permit.close();
                if (cancelled.get() || Thread.currentThread().isInterrupted()) throw ProviderCallException.cancelled();
                throw ProviderCallException.timeout();
            }
            Future<T> result = submit(() -> {
                if (cancelled.get()) throw ProviderCallException.cancelled();
                return operation.call();
            }, permit::close);
            running.set(result);
            if (cancelled.get()) result.cancel(true);
            T value = result.get(Math.max(0, deadline - System.nanoTime()), TimeUnit.NANOSECONDS);
            if (cancelled.get()) throw ProviderCallException.cancelled();
            return value;
        } catch (TimeoutException error) {
            cancel.run();
            throw ProviderCallException.timeout();
        } catch (InterruptedException error) {
            cancel.run();
            Thread.currentThread().interrupt();
            throw ProviderCallException.cancelled();
        } catch (java.util.concurrent.CancellationException error) {
            throw ProviderCallException.cancelled();
        } catch (ExecutionException error) {
            if (error.getCause() instanceof RuntimeException cause) throw cause;
            if (error.getCause() instanceof Error cause) throw cause;
            throw new IllegalStateException("Provider call failed", error.getCause());
        } finally {
            admission.cancel();
            unregisterCancellation(request, cancel);
        }
    }

    /** Reserve FIFO admission without waiting; streaming callbacks must never block here. */
    Admission reserve(Duration budget) {
        synchronized (capacityLock) {
            if (closed) throw ProviderCallException.cancelled();
            if (active < maximum && waiting.isEmpty()) {
                Admission admission = new Admission(budget, false);
                active++;
                admission.grant(new Permit());
                return admission;
            }
            if (waiting.size() >= maxQueued) {
                telemetry.providerRejected();
                throw ProviderCallException.capacity();
            }
            Admission admission = new Admission(budget, true);
            waiting.addLast(admission);
            return admission;
        }
    }

    // Kept for direct callers; async streaming uses reserve() and awaits on its worker.
    Permit acquire() {
        Admission admission = reserve(queueTimeout);
        try { return admission.await(); }
        finally { admission.cancel(); }
    }

    final class Admission {
        private final CompletableFuture<Permit> ready = new CompletableFuture<>();
        private final long started = System.nanoTime();
        private final boolean queued;
        private final boolean callBudgetLimited;
        private final long expiresAt;
        private final AiTelemetry.StageTimer queueStage;
        private Permit granted;
        private boolean claimed;
        private boolean cancelled;
        private boolean waitRecorded;

        private Admission(Duration budget, boolean queued) {
            this.queued = queued;
            callBudgetLimited = budget.compareTo(queueTimeout) <= 0;
            expiresAt = started + Math.min(budget.toNanos(), queueTimeout.toNanos());
            queueStage = queued ? telemetry.stage("provider.queue") : null;
        }

        private ProviderCallException expired() {
            return callBudgetLimited ? ProviderCallException.timeout() : ProviderCallException.queueTimeout();
        }

        // Complete only a private future read through get(); no user callbacks run under the pool lock.
        private void grant(Permit permit) {
            granted = permit;
            recordWait("admitted", null);
            ready.complete(permit);
        }

        private void recordWait(String outcome, Throwable failure) {
            if (!queued || waitRecorded) return;
            waitRecorded = true;
            telemetry.providerQueueWait(Duration.ofNanos(System.nanoTime() - started), outcome);
            if (failure != null) queueStage.failure(failure);
            queueStage.close();
        }

        Permit await() {
            try {
                Permit permit = ready.get(Math.max(0, expiresAt - System.nanoTime()), TimeUnit.NANOSECONDS);
                synchronized (capacityLock) {
                    if (closed || cancelled || Thread.currentThread().isInterrupted()) throw ProviderCallException.cancelled();
                    if (claimed) throw new IllegalStateException("Provider admission was already claimed");
                    claimed = true;
                    return permit;
                }
            } catch (InterruptedException error) {
                cancel();
                Thread.currentThread().interrupt();
                throw ProviderCallException.cancelled();
            } catch (TimeoutException error) {
                ProviderCallException failure = expired();
                withdraw(failure, "timeout");
                throw failure;
            } catch (ExecutionException error) {
                if (error.getCause() instanceof RuntimeException cause) throw cause;
                throw new IllegalStateException("Provider admission failed", error.getCause());
            } finally { cancel(); }
        }

        void cancel() { withdraw(ProviderCallException.cancelled(), "cancelled"); }

        private void withdraw(ProviderCallException failure, String outcome) {
            synchronized (capacityLock) {
                if (claimed || cancelled) return;
                cancelled = true;
                waiting.remove(this);
                recordWait(outcome, failure);
                ready.completeExceptionally(failure);
                if (granted != null) granted.close();
            }
        }
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

    public int inFlightCount() { synchronized (capacityLock) { return active; } }
    public int queuedCount() { synchronized (capacityLock) { return waiting.size(); } }

    @Override public void close() {
        // Do not block Spring shutdown indefinitely on an SDK that ignores interruption.
        synchronized (capacityLock) {
            closed = true;
            for (Admission admission : new ArrayList<>(waiting)) admission.cancel();
        }
        timers.shutdownNow();
        cancellation.shutdownNow();
        workers.shutdownNow();
    }

    final class Permit implements AutoCloseable {
        private final AtomicBoolean released = new AtomicBoolean();
        @Override public void close() {
            if (!released.compareAndSet(false, true)) return;
            synchronized (capacityLock) {
                active--;
                while (!closed && !waiting.isEmpty() && active < maximum) {
                    Admission next = waiting.removeFirst();
                    if (next.cancelled) continue;
                    if (next.expiresAt - System.nanoTime() <= 0) {
                        next.withdraw(next.expired(), "timeout");
                        continue;
                    }
                    active++;
                    next.grant(new Permit());
                }
            }
        }
    }
}
