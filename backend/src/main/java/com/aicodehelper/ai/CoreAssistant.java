package com.aicodehelper.ai;

import dev.langchain4j.service.MemoryId;
import dev.langchain4j.service.Result;
import dev.langchain4j.service.SystemMessage;
import dev.langchain4j.service.TokenStream;
import dev.langchain4j.service.UserMessage;
import dev.langchain4j.service.memory.ChatMemoryAccess;

public interface CoreAssistant extends ChatMemoryAccess {

    @SystemMessage(fromResource = "system-prompt.txt")
    Result<String> chat(@MemoryId String memoryId, @UserMessage String message);

    @SystemMessage(fromResource = "system-prompt.txt")
    TokenStream stream(@MemoryId String memoryId, @UserMessage String message);
}
