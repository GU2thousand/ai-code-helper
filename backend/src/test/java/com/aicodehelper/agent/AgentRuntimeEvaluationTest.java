package com.aicodehelper.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.assertj.core.api.Assertions.assertThat;

class AgentRuntimeEvaluationTest {
    @ParameterizedTest
    @CsvSource({"success,success,1", "timeout,TOOL_TIMEOUT,1", "http_5xx_recovery,success,3",
            "http_5xx_exhausted,HTTP_5XX,3", "malformed,MALFORMED_RESPONSE,1", "empty,EMPTY_RESPONSE,1",
            "duplicate,REPEATED_TOOL_CALL,1", "unauthorized,UNAUTHORIZED_TOOL,0",
            "provider_unavailable,PROVIDER_UNAVAILABLE,3", "mcp_connection_failure,MCP_CONNECTION_FAILED,3",
            "loop,AGENT_STEP_LIMIT,6", "user_cancel,USER_CANCELLED,0",
            "budget_exhausted,TOTAL_TOOL_BUDGET_EXCEEDED,1", "per_tool_limit,TOOL_CALL_LIMIT,1", "risk_denied,RISK_DENIED,0"})
    void allFaultFixturesExerciseTheProductionExecutor(String scenario, String expected, int underlyingCalls) {
        var fixture = new AgentRuntimeEvaluation(new ObjectMapper().findAndRegisterModules());
        var evaluated = fixture.evaluate(scenario);
        var result = evaluated.results().getLast();
        assertThat(evaluated.mode()).isEqualTo("controlled_runtime");
        assertThat(evaluated.underlyingCalls()).isEqualTo(underlyingCalls);
        assertThat(evaluated.steps()).isLessThanOrEqualTo(6);
        assertThat(evaluated.completion()).isEqualTo(expected.equals("success"));
        if (!expected.equals("success")) assertThat(result.error().code().name()).isEqualTo(expected);
        assertThat(evaluated.limitations()).contains("does not measure live model");
    }
}
