package com.aicodehelper.config;

import com.aicodehelper.ai.ModelRuntimeInfo;
import dev.langchain4j.community.model.dashscope.QwenChatModel;
import dev.langchain4j.community.model.dashscope.QwenEmbeddingModel;
import dev.langchain4j.community.model.dashscope.QwenStreamingChatModel;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.StreamingChatModel;
import dev.langchain4j.model.embedding.EmbeddingModel;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(properties = {
        "langchain4j.community.dashscope.chat-model.api-key=starter-chat-key",
        "langchain4j.community.dashscope.chat-model.model-name=qwen-plus",
        "langchain4j.community.dashscope.streaming-chat-model.api-key=starter-stream-key",
        "langchain4j.community.dashscope.streaming-chat-model.model-name=qwen-turbo",
        "langchain4j.community.dashscope.embedding-model.api-key=starter-embedding-key",
        "langchain4j.community.dashscope.embedding-model.model-name=text-embedding-v4",
        "app.mcp.enabled=false",
        "app.security.token-secret=standard-properties-integration-secret-long-enough"
})
class StandardDashScopePropertiesIntegrationTest {

    @Autowired
    private ChatModel chatModel;

    @Autowired
    private StreamingChatModel streamingChatModel;

    @Autowired
    private EmbeddingModel embeddingModel;

    @Autowired
    private ModelRuntimeInfo runtimeInfo;

    @Test
    void starterPropertyNamesConfigureTheManuallyObservedModels() {
        assertThat(chatModel).isInstanceOf(QwenChatModel.class);
        assertThat(streamingChatModel).isInstanceOf(QwenStreamingChatModel.class);
        QwenStreamingChatModel streaming = (QwenStreamingChatModel) streamingChatModel;
        assertThat(ReflectionTestUtils.getField(streaming, "apiKey")).isEqualTo("starter-stream-key");
        assertThat(streaming.defaultRequestParameters().modelName()).isEqualTo("qwen-turbo");
        assertThat(embeddingModel).isInstanceOf(QwenEmbeddingModel.class);
        assertThat(runtimeInfo.chatProvider()).isEqualTo("dashscope");
        assertThat(runtimeInfo.chatModel()).isEqualTo("qwen-plus");
        assertThat(runtimeInfo.embeddingProvider()).isEqualTo("dashscope");
        assertThat(runtimeInfo.embeddingModel()).isEqualTo("text-embedding-v4");
    }
}
