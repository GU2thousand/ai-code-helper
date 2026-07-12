package com.aicodehelper.config;

import com.aicodehelper.ai.SafeChatModelListener;
import dev.langchain4j.community.model.dashscope.QwenChatModel;
import dev.langchain4j.community.model.dashscope.QwenEmbeddingModel;
import dev.langchain4j.community.model.dashscope.QwenStreamingChatModel;
import org.junit.jupiter.api.Test;

import java.util.concurrent.Executors;

import static org.assertj.core.api.Assertions.assertThat;

class ModelConfigurationTest {

    @Test
    void nonEmptyEnvironmentKeysSelectAllThreeDashScopeModelsWithoutCallingNetwork() {
        AppProperties properties = new AppProperties();
        properties.getAi().getDashscope().setApiKey("test-chat-key");
        properties.getAi().getDashscope().setEmbeddingApiKey("test-embedding-key");
        ModelConfiguration configuration = new ModelConfiguration();
        SafeChatModelListener listener = new SafeChatModelListener();

        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            assertThat(configuration.chatModel(properties, listener)).isInstanceOf(QwenChatModel.class);
            assertThat(configuration.streamingChatModel(properties, listener, executor))
                    .isInstanceOf(QwenStreamingChatModel.class);
            assertThat(configuration.embeddingModel(properties)).isInstanceOf(QwenEmbeddingModel.class);
        }
        assertThat(configuration.modelRuntimeInfo(properties).chatProvider()).isEqualTo("dashscope");
        assertThat(configuration.modelRuntimeInfo(properties).embeddingProvider()).isEqualTo("dashscope");
    }
}
