package com.aicodehelper.ai.local;

import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.request.ChatRequest;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class LocalChatModelTest {

    private final LocalChatModel model = new LocalChatModel();

    @Test
    void stripsRagAugmentationFromDisplayedTopic() {
        String augmented = "EventSource 跨域怎么做？\n\nAnswer using the following information:\nsecret context";
        String answer = model.chat(ChatRequest.builder().messages(UserMessage.from(augmented)).build())
                .aiMessage().text();

        assertThat(answer).contains("EventSource 跨域怎么做？");
        assertThat(answer).doesNotContain("Answer using the following information", "secret context");
    }

    @Test
    void demonstratesConversationMemoryInLocalMode() {
        ChatRequest request = ChatRequest.builder().messages(
                UserMessage.from("我正在学习 Java 21 虚拟线程"),
                AiMessage.from("好的"),
                UserMessage.from("我刚才说了什么？")
        ).build();

        String answer = model.chat(request).aiMessage().text();

        assertThat(answer).contains("我正在学习 Java 21 虚拟线程");
    }
}
