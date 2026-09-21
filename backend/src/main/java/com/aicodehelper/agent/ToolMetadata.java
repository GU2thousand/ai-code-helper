package com.aicodehelper.agent;

import java.time.Instant;

public record ToolMetadata(Instant startedAt, Instant endedAt, long latencyMs,
                           int retryCount, int payloadBytes) { }
