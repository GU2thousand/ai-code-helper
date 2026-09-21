package com.aicodehelper.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static com.aicodehelper.agent.ToolError.Code.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class BoundedToolRuntimeTest {
    private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();

    private AgentProperties config() {
        AgentProperties config = new AgentProperties();
        config.setAllowedTools(List.of("fixture_tool"));
        return config;
    }

    private ToolRequest request(int index) { return new ToolRequest("fixture_tool", "{\"query\":" + index + "}"); }

    @Test void retriesServerFailuresButDoesNotExposeProviderMessages() {
        AgentProperties config = config();
        AtomicInteger calls = new AtomicInteger();
        try (var runtime = new BoundedToolRuntime(config, mapper, List.of()); var scope = runtime.begin("one")) {
            ToolResult result = runtime.execute(scope, request(1), config.policy("fixture_tool", true), () -> {
                if (calls.incrementAndGet() < 3) throw new dev.langchain4j.exception.HttpException(503, "secret-key upstream body");
                return Map.of("answer", "verified");
            });
            assertThat(result.success()).isTrue();
            assertThat(result.metadata().retryCount()).isEqualTo(2);
            assertThat(calls).hasValue(3);
            assertThat(runtime.json(result)).doesNotContain("secret-key", "arguments");
            assertThat(scope.steps()).isEqualTo(1);
        }
    }

    @Test void timeoutIsTypedAndCancelled() throws Exception {
        AgentProperties config = config();
        config.setToolTimeout(Duration.ofMillis(50));
        config.setMaxToolRetries(0);
        CountDownLatch exited = new CountDownLatch(1);
        try (var runtime = new BoundedToolRuntime(config, mapper, List.of()); var scope = runtime.begin("one")) {
            ToolResult result = runtime.execute(scope, request(1), config.policy("fixture_tool", false), () -> {
                try { new CountDownLatch(1).await(); return "never"; }
                finally { exited.countDown(); }
            });
            assertThat(result.error().code()).isEqualTo(TOOL_TIMEOUT);
            assertThat(exited.await(2, TimeUnit.SECONDS)).isTrue();
        }
    }

    @Test void cumulativeBudgetBoundsRetries() {
        AgentProperties config = config();
        config.setMaxTotalToolTime(Duration.ofMillis(30));
        config.setToolTimeout(Duration.ofMillis(100));
        try (var runtime = new BoundedToolRuntime(config, mapper, List.of()); var scope = runtime.begin("one")) {
            ToolResult result = runtime.execute(scope, request(1), config.policy("fixture_tool", false), () -> {
                new CountDownLatch(1).await(); return "never";
            });
            assertThat(result.error().code()).isEqualTo(TOTAL_TOOL_BUDGET_EXCEEDED);
            assertThat(result.metadata().retryCount()).isZero();
        }
    }

    @Test void repeatedArgumentsAreCanonicalizedAndNeverExecutedTwice() {
        AgentProperties config = config();
        AtomicInteger calls = new AtomicInteger();
        try (var runtime = new BoundedToolRuntime(config, mapper, List.of()); var scope = runtime.begin("one")) {
            var first = new ToolRequest("fixture_tool", "{\"a\":1,\"b\":{\"x\":2,\"y\":3}}");
            var repeated = new ToolRequest("fixture_tool", "{ \"b\":{\"y\":3,\"x\":2},\"a\":1 }");
            runtime.execute(scope, first, config.policy("fixture_tool", false), () -> { calls.incrementAndGet(); return "evidence"; });
            var result = runtime.execute(scope, repeated, config.policy("fixture_tool", false), () -> { calls.incrementAndGet(); return "bad"; });
            assertThat(result.error().code()).isEqualTo(REPEATED_TOOL_CALL);
            assertThat(calls).hasValue(1);
        }
    }

    @Test void unauthorizedRiskAndMalformedArgumentsNeverCallDelegate() {
        AgentProperties config = config();
        AtomicInteger calls = new AtomicInteger();
        try (var runtime = new BoundedToolRuntime(config, mapper, List.of()); var scope = runtime.begin("one")) {
            var unauthorized = runtime.execute(scope, new ToolRequest("delete_everything", "{}"),
                    config.policy("delete_everything", false), () -> calls.incrementAndGet());
            var risk = runtime.execute(scope, request(2), new ToolPolicy("fixture_tool", 3,
                    Duration.ofSeconds(1), ToolPolicy.RiskLevel.WRITE, false), () -> calls.incrementAndGet());
            var malformed = runtime.execute(scope, new ToolRequest("fixture_tool", "{broken"),
                    config.policy("fixture_tool", false), () -> calls.incrementAndGet());
            assertThat(unauthorized.error().code()).isEqualTo(UNAUTHORIZED_TOOL);
            assertThat(risk.error().code()).isEqualTo(RISK_DENIED);
            assertThat(malformed.error().code()).isEqualTo(MALFORMED_ARGUMENTS);
            assertThat(calls).hasValue(0);
        }
    }

    @Test void invalidAndEmptyResultsAreFailuresWithoutRetry() {
        AgentProperties config = config();
        try (var runtime = new BoundedToolRuntime(config, mapper, List.of()); var scope = runtime.begin("one")) {
            var malformed = runtime.execute(scope, request(1), config.policy("fixture_tool", true), () -> "{broken");
            var empty = runtime.execute(scope, request(2), config.policy("fixture_tool", true), List::of);
            assertThat(malformed.error().code()).isEqualTo(MALFORMED_RESPONSE);
            assertThat(empty.error().code()).isEqualTo(EMPTY_RESPONSE);
            assertThat(malformed.metadata().retryCount()).isZero();
        }
    }

    @Test void toolCountAndStepLimitsStopDistinctCallLoops() {
        AgentProperties config = config();
        config.setMaxCallsPerTool(6);
        AtomicInteger calls = new AtomicInteger();
        try (var runtime = new BoundedToolRuntime(config, mapper, List.of()); var scope = runtime.begin("one")) {
            ToolResult result = null;
            for (int index = 0; index < 7; index++) result = runtime.execute(scope, request(index),
                    config.policy("fixture_tool", false), () -> { calls.incrementAndGet(); return "evidence"; });
            assertThat(result.error().code()).isEqualTo(AGENT_STEP_LIMIT);
            assertThat(calls).hasValue(6);
            assertThat(scope.steps()).isEqualTo(6);
        }
    }

    @Test void cancellationInterruptsPendingCallAndCleanupDoesNotAffectAnotherRequest() throws Exception {
        AgentProperties config = config();
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch exited = new CountDownLatch(1);
        try (var runtime = new BoundedToolRuntime(config, mapper, List.of())) {
            var scope = runtime.begin("one");
            var pending = CompletableFuture.supplyAsync(() -> runtime.execute(scope, request(1),
                    config.policy("fixture_tool", false), () -> {
                        entered.countDown();
                        try { new CountDownLatch(1).await(); return "never"; }
                        finally { exited.countDown(); }
                    }));
            assertThat(entered.await(2, TimeUnit.SECONDS)).isTrue();
            scope.cancel();
            assertThat(pending.get(2, TimeUnit.SECONDS).error().code()).isEqualTo(USER_CANCELLED);
            assertThat(exited.await(2, TimeUnit.SECONDS)).isTrue();
            scope.close();
            assertThat(runtime.activeScopes()).isZero();
            try (var next = runtime.begin("one")) {
                assertThat(runtime.execute(scope, request(2), config.policy("fixture_tool", false), () -> "old").error().code())
                        .isEqualTo(USER_CANCELLED);
                assertThat(runtime.execute(next, request(1), config.policy("fixture_tool", false), () -> "fresh").success()).isTrue();
            }
            assertThat(runtime.activeScopes()).isZero();
        }
    }

    @Test void interruptStatusIsPreservedAndNoAutomaticRetryOccurs() {
        AgentProperties config = config();
        try (var runtime = new BoundedToolRuntime(config, mapper, List.of()); var scope = runtime.begin("one")) {
            Thread.currentThread().interrupt();
            try {
                var result = runtime.execute(scope, request(1), config.policy("fixture_tool", false), () -> {
                    new CountDownLatch(1).await(); return "never";
                });
                assertThat(result.error().code()).isEqualTo(INTERRUPTED);
                assertThat(Thread.currentThread().isInterrupted()).isTrue();
                assertThat(result.metadata().retryCount()).isZero();
            } finally { Thread.interrupted(); }
        }
    }

    @Test void telemetryFailuresCannotChangeToolOutcomesOrLeakScopes() {
        AgentProperties config = config();
        ToolRuntimeObserver broken = (key, result) -> { throw new IllegalStateException("telemetry unavailable"); };
        try (var runtime = new BoundedToolRuntime(config, mapper, List.of(broken))) {
            try (var scope = runtime.begin("one")) {
                assertThatThrownBy(() -> runtime.begin("one")).isInstanceOf(IllegalStateException.class);
                assertThat(runtime.execute(scope, request(1), config.policy("fixture_tool", false), () -> "evidence").success()).isTrue();
            }
            assertThat(runtime.activeScopes()).isZero();
        }
    }

    @Test void physicalCallCapacityStaysReservedUntilUncooperativeCallsActuallyExit() throws Exception {
        AgentProperties config = config();
        config.setMaxToolRetries(0);
        config.setToolTimeout(Duration.ofMillis(40));
        CountDownLatch release = new CountDownLatch(1);
        CountDownLatch exited = new CountDownLatch(64);
        try (var runtime = new BoundedToolRuntime(config, mapper, List.of())) {
            try {
                for (int index = 0; index < 64; index++) {
                    try (var scope = runtime.begin("capacity-" + index)) {
                        runtime.execute(scope, request(index), config.policy("fixture_tool", false), () -> {
                            try {
                                while (release.getCount() > 0) {
                                    try { release.await(); } catch (InterruptedException ignored) { /* simulate non-cooperative upstream */ }
                                }
                                return "late";
                            } finally { exited.countDown(); }
                        });
                    }
                }
                assertThat(runtime.inFlightActions()).isEqualTo(64);
                try (var scope = runtime.begin("overflow")) {
                    var denied = runtime.execute(scope, request(99), config.policy("fixture_tool", false), () -> "must not run");
                    assertThat(denied.error().code()).isEqualTo(PROVIDER_UNAVAILABLE);
                }
            } finally { release.countDown(); }
            assertThat(exited.await(3, TimeUnit.SECONDS)).isTrue();
            assertThat(runtime.activeScopes()).isZero();
        }
    }
}
