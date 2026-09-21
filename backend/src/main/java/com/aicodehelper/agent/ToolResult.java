package com.aicodehelper.agent;

public record ToolResult(ToolRequest request, boolean success, Object payload,
                         ToolError error, ToolMetadata metadata) { }
