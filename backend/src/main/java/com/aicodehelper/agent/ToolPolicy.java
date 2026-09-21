package com.aicodehelper.agent;

import java.time.Duration;

public record ToolPolicy(String toolName, int maxCalls, Duration timeout,
                         RiskLevel riskLevel, boolean jsonResponse) {
    public enum RiskLevel { READ_ONLY, WRITE, DESTRUCTIVE }
}
