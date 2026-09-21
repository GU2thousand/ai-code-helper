package com.aicodehelper.config;

import com.aicodehelper.ai.SafeChatModelListener;
import com.aicodehelper.ai.provider.DeadlineChatModel;
import com.aicodehelper.ai.provider.DeadlineEmbeddingModel;
import com.aicodehelper.ai.provider.DeadlineStreamingChatModel;
import com.aicodehelper.ai.provider.ProviderCallExecutor;
import com.aicodehelper.ai.provider.ProviderProperties;
import dev.langchain4j.community.model.dashscope.QwenChatModel;
import dev.langchain4j.community.model.dashscope.QwenEmbeddingModel;
import dev.langchain4j.community.model.dashscope.QwenStreamingChatModel;
import org.junit.jupiter.api.Test;

import java.util.concurrent.Executors;
import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

class ModelConfigurationTest {

    @Test
    void providerDeadlinePrecedesTheServletTimeoutEvenWhenOverridden() {
        AppProperties properties = new AppProperties();
        properties.getAi().setStreamTimeout(Duration.ofMillis(50));
        ProviderProperties provider = new ProviderProperties();
        assertThat(ModelConfiguration.providerStreamTimeout(properties, provider)).isEqualTo(Duration.ofMillis(45));
        properties.getAi().setStreamTimeout(Duration.ofSeconds(2));
        assertThat(ModelConfiguration.providerStreamTimeout(properties, provider)).isEqualTo(Duration.ofMillis(1_900));
        provider.setStreamTimeout(Duration.ofMillis(20));
        assertThat(ModelConfiguration.providerStreamTimeout(properties, provider)).isEqualTo(Duration.ofMillis(20));
    }

    @Test
    void nonEmptyEnvironmentKeysSelectAllThreeDashScopeModelsWithoutCallingNetwork() {
        AppProperties properties = new AppProperties();
        properties.getAi().getDashscope().setApiKey("test-chat-key");
        properties.getAi().getDashscope().setEmbeddingApiKey("test-embedding-key");
        ModelConfiguration configuration = new ModelConfiguration();
        SafeChatModelListener listener = new SafeChatModelListener();

        ProviderProperties provider = new ProviderProperties();
        try (var executor = Executors.newVirtualThreadPerTaskExecutor();
             var calls = new ProviderCallExecutor(provider)) {
            assertThat(((DeadlineChatModel) configuration.chatModel(properties, listener, provider, calls)).delegate())
                    .isInstanceOf(QwenChatModel.class);
            assertThat(((DeadlineStreamingChatModel) configuration.streamingChatModel(properties, listener, executor, provider, calls)).delegate())
                    .isInstanceOf(QwenStreamingChatModel.class);
            assertThat(((DeadlineEmbeddingModel) configuration.embeddingModel(properties, provider, calls)).delegate())
                    .isInstanceOf(QwenEmbeddingModel.class);
        }
        assertThat(configuration.modelRuntimeInfo(properties).chatProvider()).isEqualTo("dashscope");
        assertThat(configuration.modelRuntimeInfo(properties).embeddingProvider()).isEqualTo("dashscope");
    }
}
