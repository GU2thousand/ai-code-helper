package com.aicodehelper.ai;

import com.aicodehelper.config.AppProperties;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;

class AiExecutionRegistryTest {

    @Test
    void evictionGuardUsesTheSameActiveConversationLockAsAcquire() {
        AiExecutionRegistry registry = new AiExecutionRegistry(Clock.systemUTC(), new AppProperties());
        String key = "guest:one:memory:active";
        AtomicBoolean evicted = new AtomicBoolean();

        AiExecutionRegistry.Lease lease = registry.acquire(key);
        assertThat(registry.runIfInactive(key, () -> evicted.set(true))).isFalse();
        assertThat(evicted).isFalse();

        lease.close();
        assertThat(registry.runIfInactive(key, () -> evicted.set(true))).isTrue();
        assertThat(evicted).isTrue();
    }
}
