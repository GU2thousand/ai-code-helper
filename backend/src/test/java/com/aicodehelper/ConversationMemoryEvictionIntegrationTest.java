package com.aicodehelper;

import com.aicodehelper.memory.ConversationMemoryRegistry;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.memory.ChatMemory;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = {
        "app.ai.dashscope.api-key=",
        "app.ai.dashscope.embedding-api-key=",
        "app.ai.max-conversations=2",
        "app.mcp.enabled=false",
        "app.security.token-secret=memory-eviction-integration-secret-long-enough"
})
@AutoConfigureMockMvc
class ConversationMemoryEvictionIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private ConversationMemoryRegistry memories;

    @Test
    void coordinatedEvictionReplacesTheObjectCachedByEveryAiService() throws Exception {
        MvcResult guest = mockMvc.perform(post("/api/users/guest")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isCreated())
                .andReturn();
        Cookie cookie = guest.getResponse().getCookie("AI_GUEST_TOKEN");
        String userId = objectMapper.readTree(guest.getResponse().getContentAsString()).get("userId").asText();
        String firstKey = "guest:" + userId + ":memory:first";
        String secondKey = "guest:" + userId + ":memory:second";
        String thirdKey = "guest:" + userId + ":memory:third";

        chat(cookie, "first", "first before eviction");
        ChatMemory originalFirstMemory = memories.get(firstKey);
        chat(cookie, "second", "second conversation");
        chat(cookie, "third", "third conversation");

        assertThat(memories.size()).isEqualTo(2);
        assertThat(memories.contains(firstKey)).isFalse();
        assertThat(memories.contains(secondKey)).isTrue();
        assertThat(memories.contains(thirdKey)).isTrue();

        chat(cookie, "first", "first after eviction");
        ChatMemory replacementFirstMemory = memories.get(firstKey);
        assertThat(replacementFirstMemory).isNotSameAs(originalFirstMemory);
        assertThat(replacementFirstMemory.messages()).contains(UserMessage.from("first after eviction"));
        assertThat(memories.size()).isEqualTo(2);
    }

    private void chat(Cookie cookie, String memoryId, String message) throws Exception {
        mockMvc.perform(post("/api/ai/chat")
                        .cookie(cookie)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"memoryId":"%s","message":"%s"}
                                """.formatted(memoryId, message)))
                .andExpect(status().isOk());
    }
}
