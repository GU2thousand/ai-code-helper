package com.aicodehelper.agent;

import com.aicodehelper.config.AppProperties;
import com.aicodehelper.mcp.OptionalMcpToolProvider;
import com.aicodehelper.tool.InterviewQuestionTool;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ToolExecutionResultMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.invocation.InvocationContext;
import dev.langchain4j.invocation.InvocationParameters;
import dev.langchain4j.memory.chat.MessageWindowChatMemory;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.service.AiServices;
import dev.langchain4j.service.MemoryId;
import dev.langchain4j.service.tool.AiServiceTool;
import dev.langchain4j.service.tool.BeforeToolExecution;
import dev.langchain4j.service.tool.ToolExecutionResult;
import dev.langchain4j.service.tool.ToolExecutor;
import dev.langchain4j.service.tool.ToolProviderRequest;
import dev.langchain4j.service.tool.ToolProviderResult;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.same;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class BoundedToolProviderTest {
    private static final String KEY = "guest:test:memory:bounded-provider";
    private static final String INTERVIEW = "fetch_interview_questions";
    private static final String SEARCH = "webSearchPrime";
    private static final String SECRET = "Bearer sk-test-do-not-expose";

    private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
    private AgentProperties config;
    private AppProperties app;
    private BoundedToolRuntime runtime;
    private OptionalMcpToolProvider mcp;
    private InterviewQuestionTool interview;
    private BoundedToolProvider provider;

    @BeforeEach
    void setUp() {
        config = new AgentProperties();
        config.setMaxToolRetries(0);
        app = new AppProperties();
        app.getMcp().setApiKey(SECRET);
        runtime = new BoundedToolRuntime(config, mapper, List.of());
        mcp = mock(OptionalMcpToolProvider.class);
        interview = mock(InterviewQuestionTool.class);
        provider = new BoundedToolProvider(runtime, config, app, mcp, interview);
    }

    @AfterEach
    void tearDown() {
        runtime.close();
    }

    @Test
    void hallucinatedNamesAreAccountedInTheCapturedRequestWithoutExecutingAnyDelegate() throws Exception {
        InvocationContext invocation = context();
        try (BoundedToolRuntime.Scope scope = runtime.begin(KEY)) {
            provider.provideTools(providerRequest(invocation));
            ToolExecutionRequest forbidden = call("delete_everything", "{}");
            provider.beforeToolExecution(BeforeToolExecution.builder().request(forbidden)
                    .invocationContext(invocation).build());
            assertThat(scope.steps()).isEqualTo(1);
            assertThat(scope.results().getLast().error().code()).isEqualTo(ToolError.Code.UNAUTHORIZED_TOOL);
            assertThat(mapper.readTree(provider.unauthorized(forbidden).text()).path("success").asBoolean()).isFalse();
            verifyNoInteractions(interview);
        }
    }

    @Test
    void realAiServicesToolLoopUsesBoundedInterviewExecutorWithoutNetwork() throws Exception {
        when(interview.fetchInterviewQuestions("Java"))
                .thenReturn(List.of("Explain Java virtual threads"));
        AtomicInteger modelCalls = new AtomicInteger();
        ChatModel scripted = new ChatModel() {
            @Override
            public ChatResponse doChat(ChatRequest request) {
                if (modelCalls.getAndIncrement() == 0) {
                    assertThat(request.parameters().toolSpecifications())
                            .extracting(ToolSpecification::name).contains(INTERVIEW);
                    return ChatResponse.builder().aiMessage(AiMessage.from(
                            call(INTERVIEW, "{\"topic\":\"Java\"}"))).build();
                }
                ToolExecutionResultMessage result = request.messages().stream()
                        .filter(ToolExecutionResultMessage.class::isInstance)
                        .map(ToolExecutionResultMessage.class::cast).findFirst().orElseThrow();
                return ChatResponse.builder().aiMessage(AiMessage.from(result.text())).build();
            }
        };
        ScriptedAssistant assistant = AiServices.builder(ScriptedAssistant.class)
                .chatModel(scripted)
                .chatMemoryProvider(ignored -> MessageWindowChatMemory.withMaxMessages(8))
                .toolProvider(provider)
                .maxToolCallingRoundTrips(config.getMaxSteps())
                .build();

        try (BoundedToolRuntime.Scope scope = runtime.begin(KEY)) {
            JsonNode answer = mapper.readTree(assistant.chat(KEY, "Find Java interview questions"));
            assertThat(answer.path("success").asBoolean()).isTrue();
            assertThat(answer.path("payload").get(0).asText()).isEqualTo("Explain Java virtual threads");
            assertThat(scope.steps()).isEqualTo(1);
            assertThat(scope.results()).hasSize(1);
        }
        assertThat(modelCalls.get()).isEqualTo(2);
        verify(interview).fetchInterviewQuestions("Java");
        verify(mcp, never()).provideTools(any());
    }

    @Test
    void advertisedInterviewToolIsInterruptedAtTheBoundedTimeout() throws Exception {
        config.setToolTimeout(Duration.ofMillis(100));
        CountDownLatch neverReleased = new CountDownLatch(1);
        when(interview.fetchInterviewQuestions("slow")).thenAnswer(ignored -> {
            neverReleased.await();
            return List.of("Unexpected result");
        });
        try (BoundedToolRuntime.Scope ignored = runtime.begin(KEY)) {
            ToolExecutor executor = provider.provideTools(providerRequest(context()))
                    .toolExecutorByName(INTERVIEW);
            long start = System.nanoTime();
            ToolExecutionResult result = executor.executeWithContext(
                    call(INTERVIEW, "{\"topic\":\"slow\"}"), context());
            assertError(result, ToolError.Code.TOOL_TIMEOUT);
            assertThat(Duration.ofNanos(System.nanoTime() - start)).isLessThan(Duration.ofSeconds(2));
        }
        verify(mcp, never()).provideTools(any());
    }

    @Test
    void annotatedToolFailureKeepsItsTypedTimeoutClassification() throws Exception {
        when(interview.fetchInterviewQuestions("Java"))
                .thenThrow(new ToolFailureException(ToolError.Code.TOOL_TIMEOUT));
        try (BoundedToolRuntime.Scope ignored = runtime.begin(KEY)) {
            ToolExecutor executor = provider.provideTools(providerRequest(context()))
                    .toolExecutorByName(INTERVIEW);
            assertError(executor.executeWithContext(call(INTERVIEW, "{\"topic\":\"Java\"}"), context()),
                    ToolError.Code.TOOL_TIMEOUT);
        }
    }

    @Test
    void mcpReceivesTheOriginalInvocationContextAndDoesNotUseStringOnlyExecution() throws Exception {
        ToolExecutor upstream = mock(ToolExecutor.class);
        InvocationContext context = context();
        ToolExecutionRequest request = call(SEARCH, "{\"query\":\"Java\"}");
        when(upstream.executeWithContext(same(request), same(context)))
                .thenReturn(ToolExecutionResult.builder().resultText("A sourced search result").build());
        advertiseMcp(upstream);

        try (BoundedToolRuntime.Scope scope = runtime.begin(KEY)) {
            ToolExecutor executor = provider.provideTools(providerRequest(context)).toolExecutorByName(SEARCH);
            ToolExecutionResult result = executor.executeWithContext(request, context);
            assertThat(result.isError()).isFalse();
            assertThat(mapper.readTree(result.resultText()).path("payload").asText())
                    .isEqualTo("A sourced search result");
            assertThat(scope.results()).extracting(ToolResult::success).containsExactly(true, true);
        }
        verify(upstream).executeWithContext(same(request), same(context));
        verify(upstream, never()).execute(any(), any());
        verifyNoInteractions(interview);
    }

    @Test
    void mcpStructuredErrorIsNotMistakenForEvidenceAndDoesNotExposeCredentials() throws Exception {
        ToolExecutor upstream = mock(ToolExecutor.class);
        when(upstream.executeWithContext(any(), any())).thenReturn(ToolExecutionResult.builder()
                .isError(true).resultText("upstream error " + SECRET).build());
        advertiseMcp(upstream);
        try (BoundedToolRuntime.Scope ignored = runtime.begin(KEY)) {
            ToolExecutor executor = provider.provideTools(providerRequest(context())).toolExecutorByName(SEARCH);
            ToolExecutionResult result = executor.executeWithContext(
                    call(SEARCH, "{\"query\":\"" + SECRET + "\"}"), context());
            assertError(result, ToolError.Code.TOOL_FAILED);
            assertThat(result.resultText()).doesNotContain(SECRET, "upstream error", "arguments");
            assertThat(mapper.readTree(result.resultText()).path("payload").isNull()).isTrue();
        }
        verify(upstream, never()).execute(any(), any());
    }

    @Test
    void exactNativeMcpTimeoutSentinelBecomesTypedFailure() throws Exception {
        ToolExecutor upstream = mock(ToolExecutor.class);
        when(upstream.executeWithContext(any(), any())).thenReturn(ToolExecutionResult.builder()
                .resultText(OptionalMcpToolProvider.TIMEOUT_SENTINEL).build());
        advertiseMcp(upstream);
        try (BoundedToolRuntime.Scope ignored = runtime.begin(KEY)) {
            ToolExecutor executor = provider.provideTools(providerRequest(context())).toolExecutorByName(SEARCH);
            assertError(executor.executeWithContext(call(SEARCH, "{\"query\":\"Java\"}"), context()),
                    ToolError.Code.TOOL_TIMEOUT);
        }
    }

    @Test
    void ordinaryMcpTextContainingTimeoutSentinelRemainsSuccessful() throws Exception {
        ToolExecutor upstream = mock(ToolExecutor.class);
        String text = "Documentation mentions " + OptionalMcpToolProvider.TIMEOUT_SENTINEL;
        when(upstream.executeWithContext(any(), any())).thenReturn(ToolExecutionResult.builder().resultText(text).build());
        advertiseMcp(upstream);
        try (BoundedToolRuntime.Scope ignored = runtime.begin(KEY)) {
            ToolExecutor executor = provider.provideTools(providerRequest(context())).toolExecutorByName(SEARCH);
            ToolExecutionResult result = executor.executeWithContext(call(SEARCH, "{\"query\":\"Java\"}"), context());
            assertThat(result.isError()).isFalse();
            assertThat(mapper.readTree(result.resultText()).path("payload").asText()).isEqualTo(text);
        }
    }

    @Test
    void failedMcpExecutionSanitizesRawExceptionAndArguments() throws Exception {
        ToolExecutor upstream = mock(ToolExecutor.class);
        when(upstream.executeWithContext(any(), any()))
                .thenThrow(new IllegalStateException("https://private.example?api_key=" + SECRET));
        advertiseMcp(upstream);
        try (BoundedToolRuntime.Scope ignored = runtime.begin(KEY)) {
            ToolExecutor executor = provider.provideTools(providerRequest(context())).toolExecutorByName(SEARCH);
            ToolExecutionResult result = executor.executeWithContext(
                    call(SEARCH, "{\"query\":\"" + SECRET + "\"}"), context());
            assertError(result, ToolError.Code.TOOL_FAILED);
            assertThat(result.resultText()).doesNotContain(SECRET, "private.example", "api_key", "arguments");
        }
    }

    @Test
    void staleExecutorCannotBorrowTheNewRequestScopeWithTheSameConversationKey() throws Exception {
        when(interview.fetchInterviewQuestions(anyString())).thenReturn(List.of("A useful interview question"));
        BoundedToolRuntime.Scope oldScope = runtime.begin(KEY);
        ToolExecutor stale = provider.provideTools(providerRequest(context())).toolExecutorByName(INTERVIEW);
        oldScope.close();

        try (BoundedToolRuntime.Scope newScope = runtime.begin(KEY)) {
            ToolExecutionRequest request = call(INTERVIEW, "{\"topic\":\"Java\"}");
            assertError(stale.executeWithContext(request, context()), ToolError.Code.USER_CANCELLED);
            assertThat(newScope.steps()).isZero();
            assertThat(newScope.results()).isEmpty();
            verifyNoInteractions(interview);

            ToolExecutor fresh = provider.provideTools(providerRequest(context())).toolExecutorByName(INTERVIEW);
            ToolExecutionResult result = fresh.executeWithContext(request, context());
            assertThat(result.isError()).isFalse();
            assertThat(newScope.steps()).isEqualTo(1);
            assertThat(newScope.results()).hasSize(1);
        }
        verify(interview, times(1)).fetchInterviewQuestions(eq("Java"));
        assertThat(runtime.activeScopes()).isZero();
    }

    private void advertiseMcp(ToolExecutor executor) {
        when(mcp.isConfigured()).thenReturn(true);
        AiServiceTool tool = AiServiceTool.builder()
                .toolSpecification(ToolSpecification.builder().name(SEARCH).description("Search public sources").build())
                .toolExecutor(executor).build();
        when(mcp.provideTools(any())).thenReturn(ToolProviderResult.builder().add(tool).build());
    }

    private void assertError(ToolExecutionResult result, ToolError.Code code) throws Exception {
        assertThat(result.isError()).isTrue();
        JsonNode json = mapper.readTree(result.resultText());
        assertThat(json.path("success").asBoolean()).isFalse();
        assertThat(json.path("error").path("code").asText()).isEqualTo(code.name());
    }

    private InvocationContext context() {
        return InvocationContext.builder().chatMemoryId(KEY).invocationId(UUID.randomUUID())
                .invocationParameters(InvocationParameters.from("request-marker", "preserve-this-context")).build();
    }

    private ToolProviderRequest providerRequest(InvocationContext context) {
        return ToolProviderRequest.builder().invocationContext(context)
                .userMessage(UserMessage.from("Find evidence")).build();
    }

    private ToolExecutionRequest call(String name, String arguments) {
        return ToolExecutionRequest.builder().id("tool-call-1").name(name).arguments(arguments).build();
    }

    interface ScriptedAssistant {
        String chat(@MemoryId String memoryId, @dev.langchain4j.service.UserMessage String message);
    }
}
