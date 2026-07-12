package com.aicodehelper.ai;

import com.aicodehelper.config.AppProperties;
import com.aicodehelper.error.AiExecutionRejectedException;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

@Component
public class AiExecutionRegistry {

    private final Clock clock;
    private final AppProperties.Ai config;
    private final Set<String> activeConversations = new HashSet<>();
    private final Map<String, Integer> activeByOwner = new HashMap<>();
    private final Map<String, Integer> startsByOwner = new HashMap<>();
    private long startsWindowMinute = Long.MIN_VALUE;
    private int activeTotal;
    private int startsTotal;

    public AiExecutionRegistry(Clock clock, AppProperties properties) {
        this.clock = clock;
        this.config = properties.getAi();
    }

    public synchronized Lease acquire(String conversationKey) {
        rotateStartsWindow();
        String owner = ownerKey(conversationKey);
        if (activeConversations.contains(conversationKey)) {
            throw AiExecutionRejectedException.conversationBusy();
        }
        if (activeTotal >= Math.max(1, config.getMaxConcurrentAiRequests())
                || activeByOwner.getOrDefault(owner, 0)
                >= Math.max(1, config.getMaxConcurrentAiRequestsPerOwner())
                || startsTotal >= Math.max(1, config.getMaxAiStartsPerMinute())
                || startsByOwner.getOrDefault(owner, 0)
                >= Math.max(1, config.getMaxAiStartsPerMinutePerOwner())) {
            throw AiExecutionRejectedException.capacityReached();
        }

        activeConversations.add(conversationKey);
        activeTotal++;
        activeByOwner.merge(owner, 1, Integer::sum);
        startsTotal++;
        startsByOwner.merge(owner, 1, Integer::sum);
        return new Lease(this, conversationKey, owner);
    }

    public synchronized int activeTotal() {
        return activeTotal;
    }

    public synchronized boolean isActive(String conversationKey) {
        return activeConversations.contains(conversationKey);
    }

    /**
     * Runs an eviction while holding the same monitor used by acquire(), closing the check/evict race.
     */
    public synchronized boolean runIfInactive(String conversationKey, Runnable eviction) {
        if (activeConversations.contains(conversationKey)) {
            return false;
        }
        eviction.run();
        return true;
    }

    private synchronized void release(String conversationKey, String owner) {
        if (!activeConversations.remove(conversationKey)) {
            return;
        }
        activeTotal--;
        activeByOwner.computeIfPresent(owner, (key, count) -> count <= 1 ? null : count - 1);
    }

    private void rotateStartsWindow() {
        long currentMinute = clock.instant().getEpochSecond() / 60;
        if (currentMinute != startsWindowMinute) {
            startsWindowMinute = currentMinute;
            startsTotal = 0;
            startsByOwner.clear();
        }
    }

    private String ownerKey(String conversationKey) {
        int delimiter = conversationKey.lastIndexOf(":memory:");
        return delimiter < 0 ? conversationKey : conversationKey.substring(0, delimiter);
    }

    public static final class Lease implements AutoCloseable {
        private final AiExecutionRegistry registry;
        private final String conversationKey;
        private final String owner;
        private final AtomicBoolean closed = new AtomicBoolean();

        private Lease(AiExecutionRegistry registry, String conversationKey, String owner) {
            this.registry = registry;
            this.conversationKey = conversationKey;
            this.owner = owner;
        }

        @Override
        public void close() {
            if (closed.compareAndSet(false, true)) {
                registry.release(conversationKey, owner);
            }
        }
    }
}
