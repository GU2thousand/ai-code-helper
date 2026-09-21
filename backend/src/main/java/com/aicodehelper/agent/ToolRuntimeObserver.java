package com.aicodehelper.agent;

import java.util.function.Consumer;

public interface ToolRuntimeObserver {
    void completed(String conversationKey, ToolResult result);

    /** Called on the request thread so observers can capture tracing context for asynchronous tools. */
    default Consumer<ToolResult> forRequest(String conversationKey) {
        return result -> completed(conversationKey, result);
    }
}
