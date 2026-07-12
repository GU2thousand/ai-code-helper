package com.aicodehelper.ai;

public record ModelRuntimeInfo(
        String chatProvider,
        String chatModel,
        String embeddingProvider,
        String embeddingModel
) {

    public boolean localChat() {
        return "local".equals(chatProvider);
    }
}
