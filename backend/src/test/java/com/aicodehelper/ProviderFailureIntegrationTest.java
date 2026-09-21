package com.aicodehelper;

import com.aicodehelper.ai.AiExecutionRegistry;
import com.aicodehelper.ai.local.LocalChatModel;
import com.aicodehelper.ai.provider.DeadlineChatModel;
import com.aicodehelper.ai.provider.DeadlineStreamingChatModel;
import com.aicodehelper.ai.provider.ProviderCallExecutor;
import com.aicodehelper.ai.provider.ProviderProperties;
import com.aicodehelper.memory.ConversationMemoryRegistry;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.StreamingChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.chat.response.PartialResponse;
import dev.langchain4j.model.chat.response.PartialResponseContext;
import dev.langchain4j.model.chat.response.StreamingChatResponseHandler;
import dev.langchain4j.model.chat.response.StreamingHandle;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.AsyncEvent;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockAsyncContext;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.time.Duration;
import java.io.IOException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Uses actual MVC + AiServices memory writes, with deterministic local provider faults. */
@SpringBootTest(properties = {
        "app.ai.dashscope.api-key=", "app.ai.dashscope.embedding-api-key=",
        "app.mcp.enabled=false", "app.storage.enabled=false",
        "app.security.token-secret=provider-failure-integration-secret-with-enough-entropy",
        "app.provider.chat-timeout=200ms", "app.provider.stream-first-token-timeout=200ms",
        "app.provider.stream-timeout=450ms", "app.provider.max-in-flight=1",
        "app.provider.max-queued=0",
        "app.ai.stream-timeout=5s"
})
@AutoConfigureMockMvc
@Import(ProviderFailureIntegrationTest.FaultConfiguration.class)
class ProviderFailureIntegrationTest {
    @Autowired private MockMvc mvc;
    @Autowired private ObjectMapper json;
    @Autowired private ConversationMemoryRegistry memories;
    @Autowired private AiExecutionRegistry executions;
    @Autowired private ProviderCallExecutor calls;
    @Autowired private FaultModels faults;

    @BeforeEach void reset() {
        faults.chatMode = ChatMode.OK;
        faults.streamMode = StreamMode.SILENT;
        faults.release = new CountDownLatch(1);
        faults.streamHandler = null;
        faults.cancelled.set(false);
    }

    @AfterEach void cleanup() throws Exception {
        faults.release.countDown();
        if (faults.streamHandler != null) faults.completeLate();
        await(() -> calls.inFlightCount() == 0);
        assertThat(executions.activeTotal()).isZero();
    }

    @Test void syncTimeoutRestoresMemoryAndReleasesConversationLease() throws Exception {
        Session session = originalConversation("timeout");
        faults.chatMode = ChatMode.IGNORE_INTERRUPT;
        mvc.perform(post("/api/ai/chat").cookie(session.cookie()).contentType(MediaType.APPLICATION_JSON)
                        .content(body("timeout", true)))
                .andExpect(status().isGatewayTimeout())
                .andExpect(jsonPath("$.code").value("AI_PROVIDER_TIMEOUT"));
        assertOriginal(session);
        assertThat(executions.activeTotal()).isZero();
        assertThat(calls.inFlightCount()).isEqualTo(1);

        // Conversation capacity is released, but stuck SDK work still consumes provider capacity.
        mvc.perform(post("/api/ai/chat").cookie(session.cookie()).contentType(MediaType.APPLICATION_JSON)
                        .content(body("timeout", true)))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("AI_PROVIDER_CAPACITY"));
        assertOriginal(session);
        faults.release.countDown();
        await(() -> calls.inFlightCount() == 0);
        assertOriginal(session); // A late synchronous response never reaches AiServices.
        faults.chatMode = ChatMode.OK;
        assertRecovery(session, "timeout");
    }

    @Test void thrownProviderFailureRestoresMemoryAndReleasesCapacity() throws Exception {
        Session session = originalConversation("throws");
        faults.chatMode = ChatMode.THROW;
        mvc.perform(post("/api/ai/chat").cookie(session.cookie()).contentType(MediaType.APPLICATION_JSON)
                        .content(body("throws", true)))
                .andExpect(status().isBadGateway())
                .andExpect(jsonPath("$.code").value("AI_UPSTREAM_ERROR"));
        assertOriginal(session);
        await(() -> calls.inFlightCount() == 0);
        assertThat(executions.activeTotal()).isZero();
        faults.chatMode = ChatMode.OK;
        assertRecovery(session, "throws");
    }

    @Test void firstTokenTimeoutRollsBackAndSuppressesLateCompletion() throws Exception {
        Session session = originalConversation("first-token");
        String result = stream(session, "first-token");
        assertThat(result).contains("event:error", "AI_PROVIDER_TIMEOUT").doesNotContain("event:done");
        assertOriginal(session);
        assertThat(executions.activeTotal()).isZero();
        assertThat(calls.inFlightCount()).isEqualTo(1);
        faults.completeLate();
        await(() -> calls.inFlightCount() == 0);
        assertOriginal(session);
        assertRecovery(session, "first-token");
    }

    @Test void streamTotalTimeoutAfterPartialTokenRollsBackAndCancels() throws Exception {
        Session session = originalConversation("partial-timeout");
        faults.streamMode = StreamMode.PARTIAL;
        String result = stream(session, "partial-timeout");
        assertThat(result).contains("event:message", "partial text", "event:error", "AI_PROVIDER_TIMEOUT")
                .doesNotContain("event:done");
        await(faults.cancelled::get);
        assertOriginal(session);
        assertThat(executions.activeTotal()).isZero();
        // This fake SDK deliberately ignores cancellation, so its provider slot stays occupied.
        assertThat(calls.inFlightCount()).isEqualTo(1);
        faults.completeLate();
        await(() -> calls.inFlightCount() == 0);
        assertOriginal(session);
        assertRecovery(session, "partial-timeout");
    }

    @Test void disconnectBeforeFirstTokenFencesLateMemoryWrites() throws Exception {
        Session session = originalConversation("disconnect");
        MvcResult started = startStream(session, "disconnect");
        await(() -> faults.streamHandler != null);
        MockAsyncContext context = (MockAsyncContext) started.getRequest().getAsyncContext();
        AsyncEvent disconnect = new AsyncEvent(context, new IOException("Injected client disconnect"));
        for (var listener : context.getListeners()) listener.onError(disconnect);
        // Servlet callbacks release framework locks before asynchronous cleanup fences
        // provider callbacks and restores memory; wait for that owned lease to close.
        await(() -> executions.activeTotal() == 0);
        assertThat(executions.activeTotal()).isZero();
        assertOriginal(session);
        faults.completeLate();
        await(() -> calls.inFlightCount() == 0);
        assertOriginal(session);
        assertRecovery(session, "disconnect");
    }

    private Session originalConversation(String memoryId) throws Exception {
        MvcResult guest = mvc.perform(post("/api/users/guest").contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isCreated()).andReturn();
        String id = json.readTree(guest.getResponse().getContentAsString()).get("userId").asText();
        Session session = new Session(guest.getResponse().getCookie("AI_GUEST_TOKEN"), "guest:" + id + ":memory:" + memoryId);
        memories.get(session.key()).add(UserMessage.from("original question"));
        memories.get(session.key()).add(AiMessage.from("original answer"));
        return session;
    }

    private void assertOriginal(Session session) {
        assertThat(memories.get(session.key()).messages()).containsExactly(
                UserMessage.from("original question"), AiMessage.from("original answer"));
    }

    private void assertRecovery(Session session, String memoryId) throws Exception {
        mvc.perform(post("/api/ai/chat").cookie(session.cookie()).contentType(MediaType.APPLICATION_JSON)
                        .content(body(memoryId, false))).andExpect(status().isOk());
        assertThat(memories.get(session.key()).messages().toString()).doesNotContain("late contaminated answer");
    }

    private String stream(Session session, String memoryId) throws Exception {
        MvcResult started = startStream(session, memoryId);
        started.getAsyncResult(5_000);
        return mvc.perform(asyncDispatch(started)).andExpect(status().isOk()).andReturn()
                .getResponse().getContentAsString();
    }

    private MvcResult startStream(Session session, String memoryId) throws Exception {
        MvcResult ticket = mvc.perform(post("/api/ai/chat/streams").cookie(session.cookie())
                        .contentType(MediaType.APPLICATION_JSON).content(body(memoryId, true)))
                .andExpect(status().isCreated()).andReturn();
        String id = json.readTree(ticket.getResponse().getContentAsString()).get("streamId").asText();
        return mvc.perform(get("/api/ai/chat/streams/{id}", id).cookie(session.cookie()))
                .andExpect(request().asyncStarted()).andReturn();
    }

    private static String body(String memoryId, boolean regenerate) {
        return "{\"memoryId\":\"" + memoryId + "\",\"message\":\"Explain Java Spring dependency injection\",\"regenerate\":" + regenerate + "}";
    }

    private static void await(BooleanSupplier condition) throws Exception {
        long until = System.nanoTime() + Duration.ofSeconds(5).toNanos();
        while (!condition.getAsBoolean() && System.nanoTime() < until) Thread.sleep(5);
        assertThat(condition.getAsBoolean()).isTrue();
    }

    private record Session(Cookie cookie, String key) { }
    private enum ChatMode { OK, THROW, IGNORE_INTERRUPT }
    private enum StreamMode { SILENT, PARTIAL }

    static final class FaultModels {
        volatile ChatMode chatMode = ChatMode.OK;
        volatile StreamMode streamMode = StreamMode.SILENT;
        volatile CountDownLatch release = new CountDownLatch(1);
        volatile StreamingChatResponseHandler streamHandler;
        final AtomicBoolean cancelled = new AtomicBoolean();

        ChatResponse chat(ChatRequest request) {
            if (chatMode == ChatMode.THROW) throw new IllegalStateException("Injected provider failure: secret must not escape");
            if (chatMode == ChatMode.IGNORE_INTERRUPT) {
                boolean released = false;
                while (!released) {
                    try { release.await(); released = true; }
                    catch (InterruptedException ignored) { /* Simulate an SDK that ignores cancellation. */ }
                }
                return lateResponse();
            }
            return new LocalChatModel().doChat(request);
        }

        void stream(StreamingChatResponseHandler handler) {
            streamHandler = handler;
            if (streamMode == StreamMode.PARTIAL) handler.onPartialResponse(new PartialResponse("partial text"),
                    new PartialResponseContext(new StreamingHandle() {
                        @Override public void cancel() { cancelled.set(true); }
                        @Override public boolean isCancelled() { return cancelled.get(); }
                    }));
        }
        void completeLate() { streamHandler.onCompleteResponse(lateResponse()); }
        static ChatResponse lateResponse() {
            return ChatResponse.builder().aiMessage(AiMessage.from("late contaminated answer")).build();
        }
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class FaultConfiguration {
        @Bean FaultModels faultModels() { return new FaultModels(); }
        @Bean @Primary ChatModel faultChatModel(FaultModels faults, ProviderCallExecutor calls, ProviderProperties config) {
            return new DeadlineChatModel(new ChatModel() {
                @Override public ChatResponse doChat(ChatRequest request) { return faults.chat(request); }
            }, calls, config.getChatTimeout());
        }
        @Bean @Primary StreamingChatModel faultStreamingModel(FaultModels faults, ProviderCallExecutor calls, ProviderProperties config) {
            return new DeadlineStreamingChatModel(new StreamingChatModel() {
                @Override public void doChat(ChatRequest request, StreamingChatResponseHandler handler) { faults.stream(handler); }
            }, calls, config.getStreamFirstTokenTimeout(), config.getStreamTimeout());
        }
    }
}
