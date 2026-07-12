package com.aicodehelper.ai.local;

import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.model.ModelProvider;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.output.FinishReason;

public final class LocalChatModel implements ChatModel {

    public static final String MODEL_NAME = "local-mock";
    private final LocalAnswerGenerator answerGenerator = new LocalAnswerGenerator();

    @Override
    public ChatResponse doChat(ChatRequest request) {
        String answer = answerGenerator.answer(request.messages());
        return ChatResponse.builder()
                .aiMessage(AiMessage.from(answer))
                .modelName(MODEL_NAME)
                .finishReason(FinishReason.STOP)
                .build();
    }

    @Override
    public ModelProvider provider() {
        return ModelProvider.OTHER;
    }
}
