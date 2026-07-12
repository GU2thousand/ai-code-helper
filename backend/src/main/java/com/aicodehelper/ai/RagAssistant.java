package com.aicodehelper.ai;

import dev.langchain4j.service.MemoryId;
import dev.langchain4j.service.Result;
import dev.langchain4j.service.SystemMessage;
import dev.langchain4j.service.UserMessage;
import dev.langchain4j.service.memory.ChatMemoryAccess;

public interface RagAssistant extends ChatMemoryAccess {

    @SystemMessage(fromResource = "system-prompt.txt")
    Result<String> chat(@MemoryId String memoryId, @UserMessage String message);
}
