package com.aicodehelper.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;

/** Fixed local fault fixtures exercising the same executor as production. No model selection is measured. */
@Component
public class AgentRuntimeEvaluation {
    private final ObjectMapper mapper;

    public AgentRuntimeEvaluation(ObjectMapper mapper) { this.mapper = mapper; }

    public EvaluationResult evaluate(String scenario) {
        if (scenario == null || !SCENARIOS.contains(scenario)) throw new IllegalArgumentException("Unknown agent fixture");
        AgentProperties config = new AgentProperties();
        config.setAllowedTools(List.of("fixture_tool"));
        config.setMaxCallsPerTool(6);
        config.setToolTimeout(Duration.ofMillis(40));
        config.setMaxTotalToolTime(Duration.ofSeconds(2));
        if (scenario.equals("budget_exhausted")) config.setMaxTotalToolTime(Duration.ofMillis(25));
        if (scenario.equals("per_tool_limit")) config.setMaxCallsPerTool(1);
        if (scenario.equals("timeout")) config.setMaxToolRetries(0);
        AtomicInteger calls = new AtomicInteger();
        try (BoundedToolRuntime runtime = new BoundedToolRuntime(config, mapper, List.of());
             BoundedToolRuntime.Scope scope = runtime.begin("fixture-" + UUID.randomUUID())) {
            ToolPolicy policy = config.policy("fixture_tool", true);
            if (scenario.equals("risk_denied")) policy = new ToolPolicy("fixture_tool", 6,
                    config.getToolTimeout(), ToolPolicy.RiskLevel.WRITE, true);
            if (scenario.equals("user_cancel")) scope.cancel();
            String name = scenario.equals("unauthorized") ? "forbidden_tool" : "fixture_tool";
            int steps = switch (scenario) { case "loop" -> 7; case "duplicate", "per_tool_limit" -> 2; default -> 1; };
            for (int index = 0; index < steps; index++) {
                String arguments = "{\"query\":\"fixture-" + (scenario.equals("duplicate") ? 0 : index) + "\"}";
                runtime.execute(scope, new ToolRequest(name, arguments), policy, () -> {
                    int attempt = calls.incrementAndGet();
                    return switch (scenario) {
                        case "timeout", "budget_exhausted" -> {
                            new CountDownLatch(1).await();
                            yield Map.of("never", "reached");
                        }
                        case "http_5xx_recovery" -> {
                            if (attempt < 3) throw new ToolFailureException(ToolError.Code.HTTP_5XX);
                            yield Map.of("evidence", "recovered");
                        }
                        case "http_5xx_exhausted" -> throw new ToolFailureException(ToolError.Code.HTTP_5XX);
                        case "malformed" -> "{not-json";
                        case "empty" -> List.of();
                        case "provider_unavailable" -> throw new ToolFailureException(ToolError.Code.PROVIDER_UNAVAILABLE);
                        case "mcp_connection_failure" -> throw new ToolFailureException(ToolError.Code.MCP_CONNECTION_FAILED);
                        default -> Map.of("evidence", "fixed offline fixture");
                    };
                });
            }
            List<ToolResult> results = scope.results();
            ToolResult last = results.getLast();
            return new EvaluationResult(scenario, "controlled_runtime", results, scope.steps(), calls.get(),
                    results.stream().mapToInt(result -> result.metadata().retryCount()).sum(), last.success(),
                    "Fixed injected tool outcomes only; does not measure live model tool selection, Qwen quality, or real MCP reliability.");
        }
    }

    public static final List<String> SCENARIOS = List.of("success", "timeout", "http_5xx_recovery",
            "http_5xx_exhausted", "malformed", "empty", "duplicate", "unauthorized", "provider_unavailable",
            "mcp_connection_failure", "loop", "user_cancel", "budget_exhausted", "per_tool_limit", "risk_denied");

    public record EvaluationResult(String scenario, String mode, List<ToolResult> results, int steps,
                                   int underlyingCalls, int retries, boolean completion, String limitations) { }
}
