package com.aicodehelper.agent;

import com.aicodehelper.config.AppProperties;
import com.aicodehelper.mcp.OptionalMcpToolProvider;
import com.aicodehelper.tool.InterviewQuestionTool;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.agent.tool.ToolSpecifications;
import dev.langchain4j.data.message.ToolExecutionResultMessage;
import dev.langchain4j.invocation.InvocationContext;
import dev.langchain4j.service.tool.AiServiceTool;
import dev.langchain4j.service.tool.DefaultToolExecutor;
import dev.langchain4j.service.tool.BeforeToolExecution;
import dev.langchain4j.service.tool.ToolErrorHandlerResult;
import dev.langchain4j.service.tool.ToolExecutionResult;
import dev.langchain4j.service.tool.ToolExecutor;
import dev.langchain4j.service.tool.ToolProvider;
import dev.langchain4j.service.tool.ToolProviderRequest;
import dev.langchain4j.service.tool.ToolProviderResult;
import org.springframework.stereotype.Component;

import java.lang.reflect.Method;
import java.time.Instant;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.concurrent.atomic.AtomicReference;

/** Every advertised tool, including local annotated tools, goes through the same bounded executor. */
@Component
public class BoundedToolProvider implements ToolProvider {
    private static final String INVOCATION_TOOLS = BoundedToolProvider.class.getName() + ".tools";
    private final BoundedToolRuntime runtime;
    private final AgentProperties config;
    private final AppProperties.Mcp mcpConfig;
    private final OptionalMcpToolProvider mcp;
    private final AiServiceTool interview;

    public BoundedToolProvider(BoundedToolRuntime runtime, AgentProperties config, AppProperties app,
                               OptionalMcpToolProvider mcp, InterviewQuestionTool interviewTool) {
        this.runtime = runtime;
        this.config = config;
        this.mcpConfig = app.getMcp();
        this.mcp = mcp;
        try {
            Method method = InterviewQuestionTool.class.getMethod("fetchInterviewQuestions", String.class);
            ToolSpecification spec = ToolSpecifications.toolSpecificationFrom(method);
            this.interview = AiServiceTool.builder().toolSpecification(spec)
                    .toolExecutor(DefaultToolExecutor.builder().object(interviewTool)
                            .originalMethod(method).methodToInvoke(method)
                            .wrapToolArgumentsExceptions(true).propagateToolExecutionExceptions(true).build()).build();
        } catch (NoSuchMethodException error) { throw new IllegalStateException(error); }
    }

    @Override public ToolProviderResult provideTools(ToolProviderRequest request) {
        BoundedToolRuntime.Scope scope = runtime.scope(request.chatMemoryId());
        ToolProviderResult.Builder result = ToolProviderResult.builder();
        if (config.getAllowedTools().contains(interview.name())) result.add(wrap(interview, scope, false));
        if (mcp.isConfigured()) {
            AtomicReference<ToolProviderResult> discovered = new AtomicReference<>();
            ToolResult discovery = runtime.discover(scope, () -> {
                ToolProviderResult found = mcp.provideTools(request);
                discovered.set(found);
                return Map.of("availableToolCount", found.aiServiceTools().size());
            });
            if (discovery.success()) {
                for (AiServiceTool tool : discovered.get().aiServiceTools()) {
                    // Enforce both the original MCP allowlist and the global runtime policy.
                    if (mcpConfig.getAllowedToolNames().contains(tool.name()) && config.getAllowedTools().contains(tool.name())) {
                        result.add(wrap(tool, scope, true));
                    }
                }
            }
        }
        ToolProviderResult available = result.build();
        if (request.invocationParameters() != null) {
            request.invocationParameters().put(INVOCATION_TOOLS, new InvocationTools(scope,
                    available.aiServiceTools().stream().map(AiServiceTool::name).collect(Collectors.toUnmodifiableSet())));
        }
        return available;
    }

    /** Unknown names bypass LangChain4j executors, so account for their rejection at its actual loop boundary. */
    public void beforeToolExecution(BeforeToolExecution before) {
        if (before.invocationContext() == null || before.invocationContext().invocationParameters() == null) return;
        InvocationTools tools = before.invocationContext().invocationParameters().get(INVOCATION_TOOLS);
        if (tools != null && !tools.names().contains(before.request().name())) {
            runtime.execute(tools.scope(), new ToolRequest(before.request().name(), before.request().arguments()),
                    null, () -> { throw new IllegalStateException("An unauthorized delegate must never execute"); });
        }
    }

    private record InvocationTools(BoundedToolRuntime.Scope scope, Set<String> names) { }

    AiServiceTool wrap(AiServiceTool original, BoundedToolRuntime.Scope scope, boolean isMcp) {
        return original.toBuilder().toolExecutor(new ToolExecutor() {
            @Override public String execute(ToolExecutionRequest request, Object memoryId) {
                return executeWithContext(request, InvocationContext.builder().chatMemoryId(memoryId).build()).resultText();
            }

            @Override public ToolExecutionResult executeWithContext(ToolExecutionRequest request, InvocationContext context) {
                ToolResult outcome = runtime.execute(scope, new ToolRequest(request.name(), request.arguments()),
                        config.policy(original.name(), !isMcp), () -> {
                            try {
                                ToolExecutionResult upstream = original.toolExecutor().executeWithContext(request, context);
                                if (upstream == null) throw new ToolFailureException(ToolError.Code.EMPTY_RESPONSE);
                                if (upstream.isError()) throw new ToolFailureException(ToolError.Code.TOOL_FAILED);
                                if (isMcp && OptionalMcpToolProvider.TIMEOUT_SENTINEL.equals(upstream.resultText())) {
                                    throw new ToolFailureException(ToolError.Code.TOOL_TIMEOUT);
                                }
                                return upstream.resultText();
                            } catch (RuntimeException error) {
                                ToolError.Code code = BoundedToolRuntime.classify(error);
                                if (isMcp && code == ToolError.Code.PROVIDER_UNAVAILABLE) code = ToolError.Code.MCP_CONNECTION_FAILED;
                                throw new ToolFailureException(code);
                            }
                        });
                return ToolExecutionResult.builder().isError(!outcome.success())
                        .result(outcome).resultText(runtime.json(outcome)).build();
            }
        }).build();
    }

    /** LangChain4j bypasses ToolExecutor for unknown names; retain an explicit failed contract. */
    public ToolExecutionResultMessage unauthorized(ToolExecutionRequest request) {
        return ToolExecutionResultMessage.builder().id(request.id()).toolName(request.name())
                .isError(true).text(errorJson(request.name(), ToolError.Code.UNAUTHORIZED_TOOL)).build();
    }

    public ToolErrorHandlerResult sanitizedError(Throwable error) {
        return ToolErrorHandlerResult.text(errorJson("unknown", BoundedToolRuntime.classify(error)));
    }

    private String errorJson(String name, ToolError.Code code) {
        Instant now = Instant.now();
        return runtime.json(new ToolResult(new ToolRequest(name, "{}"), false, null, ToolError.of(code),
                new ToolMetadata(now, now, 0, 0, 0)));
    }
}
