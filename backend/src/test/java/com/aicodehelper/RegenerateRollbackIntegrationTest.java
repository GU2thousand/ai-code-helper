package com.aicodehelper;

import com.aicodehelper.memory.ConversationMemoryRegistry;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.StreamingChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.StreamingChatResponseHandler;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = {
        "app.ai.dashscope.api-key=",
        "app.ai.dashscope.embedding-api-key=",
        "app.mcp.enabled=false",
        "app.security.token-secret=regenerate-rollback-integration-secret"
})
@AutoConfigureMockMvc
@Import(RegenerateRollbackIntegrationTest.FailingStreamConfiguration.class)
class RegenerateRollbackIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private ConversationMemoryRegistry memories;

    @Test
    void failedStreamBeforeFirstChunkRestoresTheOriginalTurn() throws Exception {
        MvcResult guest = mockMvc.perform(post("/api/users/guest")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isCreated())
                .andReturn();
        Cookie cookie = guest.getResponse().getCookie("AI_GUEST_TOKEN");
        String userId = objectMapper.readTree(guest.getResponse().getContentAsString()).get("userId").asText();
        String key = "guest:" + userId + ":memory:regen-failure";
        memories.get(key).add(UserMessage.from("original question"));
        memories.get(key).add(AiMessage.from("original answer"));

        MvcResult ticketResponse = mockMvc.perform(post("/api/ai/chat/streams")
                        .cookie(cookie)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"memoryId":"regen-failure","message":"replacement","regenerate":true}
                                """))
                .andExpect(status().isCreated())
                .andReturn();
        String streamId = objectMapper.readTree(ticketResponse.getResponse().getContentAsString())
                .get("streamId").asText();

        MvcResult started = mockMvc.perform(get("/api/ai/chat/streams/{id}", streamId).cookie(cookie))
                .andExpect(request().asyncStarted())
                .andReturn();
        started.getAsyncResult(5_000);
        mockMvc.perform(asyncDispatch(started))
                .andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("event:error")));

        assertThat(memories.get(key).messages()).containsExactly(
                UserMessage.from("original question"),
                AiMessage.from("original answer")
        );
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class FailingStreamConfiguration {

        @Bean
        @Primary
        StreamingChatModel failingStreamingChatModel() {
            return new StreamingChatModel() {
                @Override
                public void doChat(ChatRequest request, StreamingChatResponseHandler handler) {
                    handler.onError(new IllegalStateException("simulated upstream failure"));
                }
            };
        }
    }
}
