package com.aicodehelper.config;

import com.aicodehelper.ai.ModelRuntimeInfo;
import com.aicodehelper.ai.SafeChatModelListener;
import com.aicodehelper.ai.local.LocalChatModel;
import com.aicodehelper.ai.local.LocalHashEmbeddingModel;
import com.aicodehelper.ai.local.LocalStreamingChatModel;
import com.aicodehelper.ai.provider.DeadlineChatModel;
import com.aicodehelper.ai.provider.DeadlineEmbeddingModel;
import com.aicodehelper.ai.provider.DeadlineStreamingChatModel;
import com.aicodehelper.ai.provider.ProviderCallExecutor;
import com.aicodehelper.ai.provider.ProviderProperties;
import com.aicodehelper.observability.AiTelemetry;
import dev.langchain4j.community.model.dashscope.QwenChatModel;
import dev.langchain4j.community.model.dashscope.QwenEmbeddingModel;
import dev.langchain4j.community.model.dashscope.QwenStreamingChatModel;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.StreamingChatModel;
import dev.langchain4j.model.chat.listener.ChatModelListener;
import dev.langchain4j.model.embedding.EmbeddingModel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.util.StringUtils;

import java.util.List;
import java.time.Duration;
import java.util.concurrent.ExecutorService;

@Configuration
public class ModelConfiguration {

    private static final Logger log = LoggerFactory.getLogger(ModelConfiguration.class);

    @Bean
    ChatModelListener safeChatModelListener(AiTelemetry telemetry) {
        return new SafeChatModelListener(telemetry);
    }

    @Bean(destroyMethod = "close")
    ProviderCallExecutor providerCallExecutor(ProviderProperties provider, AiTelemetry telemetry) {
        return new ProviderCallExecutor(provider, telemetry);
    }

    @Bean
    ChatModel chatModel(AppProperties properties, ChatModelListener listener,
                        ProviderProperties provider, ProviderCallExecutor calls) {
        AppProperties.Dashscope config = properties.getAi().getDashscope();
        if (!StringUtils.hasText(config.getApiKey())) {
            log.info("DASHSCOPE_API_KEY is absent; using the local chat model");
            return new DeadlineChatModel(new LocalChatModel(), calls, provider.getChatTimeout());
        }

        QwenChatModel.QwenChatModelBuilder builder = QwenChatModel.builder()
                .apiKey(config.getApiKey())
                .modelName(config.getChatModel())
                .temperature(config.getTemperature())
                .maxTokens(config.getMaxTokens())
                .listeners(List.of(listener));
        if (StringUtils.hasText(config.getBaseUrl())) {
            builder.baseUrl(config.getBaseUrl());
        }
        log.info("Using DashScope chat model name={}", config.getChatModel());
        return new DeadlineChatModel(builder.build(), calls, provider.getChatTimeout());
    }

    @Bean
    StreamingChatModel streamingChatModel(
            AppProperties properties,
            ChatModelListener listener,
            ExecutorService aiStreamExecutor,
            ProviderProperties provider,
            ProviderCallExecutor calls
    ) {
        AppProperties.Dashscope config = properties.getAi().getDashscope();
        Duration streamDeadline = providerStreamTimeout(properties, provider);
        Duration firstTokenDeadline = minimum(provider.getStreamFirstTokenTimeout(), streamDeadline);
        String apiKey = StringUtils.hasText(config.getStreamingApiKey())
                ? config.getStreamingApiKey()
                : config.getApiKey();
        if (!StringUtils.hasText(apiKey)) {
            return new DeadlineStreamingChatModel(new LocalStreamingChatModel(aiStreamExecutor), calls,
                    firstTokenDeadline, streamDeadline);
        }

        String modelName = StringUtils.hasText(config.getStreamingChatModel())
                ? config.getStreamingChatModel()
                : config.getChatModel();
        String baseUrl = StringUtils.hasText(config.getStreamingBaseUrl())
                ? config.getStreamingBaseUrl()
                : config.getBaseUrl();

        QwenStreamingChatModel.QwenStreamingChatModelBuilder builder = QwenStreamingChatModel.builder()
                .apiKey(apiKey)
                .modelName(modelName)
                .temperature(config.getTemperature())
                .maxTokens(config.getMaxTokens())
                .listeners(List.of(listener));
        if (StringUtils.hasText(baseUrl)) {
            builder.baseUrl(baseUrl);
        }
        return new DeadlineStreamingChatModel(builder.build(), calls,
                firstTokenDeadline, streamDeadline);
    }

    static Duration providerStreamTimeout(AppProperties properties, ProviderProperties provider) {
        Duration servletTimeout = properties.getAi().getStreamTimeout();
        if (servletTimeout == null || servletTimeout.compareTo(Duration.ofNanos(10)) < 0) {
            throw new IllegalArgumentException("app.ai.stream-timeout must be positive");
        }
        Duration margin = minimum(Duration.ofMillis(100), servletTimeout.dividedBy(10));
        return minimum(provider.getStreamTimeout(), servletTimeout.minus(margin));
    }

    private static Duration minimum(Duration left, Duration right) {
        return left.compareTo(right) <= 0 ? left : right;
    }

    @Bean
    EmbeddingModel embeddingModel(AppProperties properties, ProviderProperties provider, ProviderCallExecutor calls) {
        AppProperties.Dashscope config = properties.getAi().getDashscope();
        String apiKey = StringUtils.hasText(config.getEmbeddingApiKey())
                ? config.getEmbeddingApiKey()
                : config.getApiKey();
        if (!StringUtils.hasText(apiKey)) {
            log.info("DashScope embedding key is absent; using local deterministic embeddings");
            return new DeadlineEmbeddingModel(new LocalHashEmbeddingModel(), calls, provider.getEmbeddingTimeout());
        }

        QwenEmbeddingModel.QwenEmbeddingModelBuilder builder = QwenEmbeddingModel.builder()
                .apiKey(apiKey)
                .modelName(config.getEmbeddingModel());
        if (StringUtils.hasText(config.getBaseUrl())) {
            builder.baseUrl(config.getBaseUrl());
        }
        log.info("Using DashScope embedding model name={}", config.getEmbeddingModel());
        return new DeadlineEmbeddingModel(builder.build(), calls, provider.getEmbeddingTimeout());
    }

    @Bean
    ModelRuntimeInfo modelRuntimeInfo(AppProperties properties) {
        AppProperties.Dashscope config = properties.getAi().getDashscope();
        boolean chatRemote = StringUtils.hasText(config.getApiKey());
        boolean embeddingRemote = StringUtils.hasText(config.getEmbeddingApiKey()) || chatRemote;
        return new ModelRuntimeInfo(
                chatRemote ? "dashscope" : "local",
                chatRemote ? config.getChatModel() : LocalChatModel.MODEL_NAME,
                embeddingRemote ? "dashscope" : "local",
                embeddingRemote ? config.getEmbeddingModel() : "local-hash-embedding"
        );
    }
}
