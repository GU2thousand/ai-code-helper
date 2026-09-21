package com.aicodehelper.agent;

public record ToolError(Code code, String message, boolean retryable) {
    public enum Code {
        TOOL_TIMEOUT, HTTP_5XX, MALFORMED_ARGUMENTS, MALFORMED_RESPONSE, EMPTY_RESPONSE,
        REPEATED_TOOL_CALL, UNAUTHORIZED_TOOL, MCP_CONNECTION_FAILED, AGENT_STEP_LIMIT,
        USER_CANCELLED, INTERRUPTED, PROVIDER_UNAVAILABLE, TOTAL_TOOL_BUDGET_EXCEEDED,
        TOOL_CALL_LIMIT, RISK_DENIED, REQUEST_SCOPE_MISSING, TOOL_FAILED
    }

    public static ToolError of(Code code) {
        boolean retryable = switch (code) {
            case TOOL_TIMEOUT, HTTP_5XX, MCP_CONNECTION_FAILED, PROVIDER_UNAVAILABLE -> true;
            default -> false;
        };
        // Fixed messages intentionally exclude upstream bodies, endpoint URLs, arguments and credentials.
        String message = switch (code) {
            case TOOL_TIMEOUT -> "The tool exceeded its execution timeout.";
            case HTTP_5XX -> "The tool service returned a server error.";
            case MALFORMED_ARGUMENTS -> "Tool arguments must be a valid JSON object matching the tool schema.";
            case MALFORMED_RESPONSE -> "The tool returned an invalid or oversized response.";
            case EMPTY_RESPONSE -> "The tool returned no usable results.";
            case REPEATED_TOOL_CALL -> "An identical tool call was already attempted; use existing evidence or finish.";
            case UNAUTHORIZED_TOOL -> "The requested tool is not allowed.";
            case MCP_CONNECTION_FAILED -> "The MCP connection is unavailable.";
            case AGENT_STEP_LIMIT -> "The agent step limit was reached; finish with available evidence.";
            case USER_CANCELLED -> "The user cancelled this request.";
            case INTERRUPTED -> "Tool execution was interrupted.";
            case PROVIDER_UNAVAILABLE -> "The tool provider is unavailable.";
            case TOTAL_TOOL_BUDGET_EXCEEDED -> "The request tool time budget was exhausted; finish with available evidence.";
            case TOOL_CALL_LIMIT -> "The per-tool call limit was reached.";
            case RISK_DENIED -> "This runtime allows read-only tools only.";
            case REQUEST_SCOPE_MISSING -> "No active request scope is available for tool execution.";
            case TOOL_FAILED -> "Tool execution failed; do not treat this response as evidence.";
        };
        return new ToolError(code, message, retryable);
    }
}
