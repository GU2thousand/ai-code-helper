package com.aicodehelper.agent;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Component
@ConfigurationProperties(prefix = "app.agent")
public class AgentProperties {
    private int maxSteps = 6;
    private int maxToolRetries = 2;
    private Duration toolTimeout = Duration.ofSeconds(10);
    private Duration maxTotalToolTime = Duration.ofSeconds(30);
    private int maxCallsPerTool = 3;
    private List<String> allowedTools = new ArrayList<>(List.of(
            "fetch_interview_questions", "webSearchPrime", "web_search_prime"));
    private Map<String, ToolPolicy.RiskLevel> riskLevels = new HashMap<>();

    public int getMaxSteps() { return maxSteps; }
    public void setMaxSteps(int value) { maxSteps = value; }
    public int getMaxToolRetries() { return maxToolRetries; }
    public void setMaxToolRetries(int value) { maxToolRetries = value; }
    public Duration getToolTimeout() { return toolTimeout; }
    public void setToolTimeout(Duration value) { toolTimeout = value; }
    public Duration getMaxTotalToolTime() { return maxTotalToolTime; }
    public void setMaxTotalToolTime(Duration value) { maxTotalToolTime = value; }
    public int getMaxCallsPerTool() { return maxCallsPerTool; }
    public void setMaxCallsPerTool(int value) { maxCallsPerTool = value; }
    public List<String> getAllowedTools() { return allowedTools; }
    public void setAllowedTools(List<String> value) { allowedTools = value; }
    public Map<String, ToolPolicy.RiskLevel> getRiskLevels() { return riskLevels; }
    public void setRiskLevels(Map<String, ToolPolicy.RiskLevel> value) { riskLevels = value; }

    public ToolPolicy policy(String name, boolean jsonResponse) {
        return new ToolPolicy(name, maxCallsPerTool, toolTimeout,
                riskLevels.getOrDefault(name, ToolPolicy.RiskLevel.READ_ONLY), jsonResponse);
    }

    void validate() {
        if (maxSteps < 1 || maxSteps > 6 || maxToolRetries < 0 || maxToolRetries > 2
                || maxCallsPerTool < 1 || maxCallsPerTool > 6
                || toolTimeout == null || toolTimeout.isZero() || toolTimeout.isNegative()
                || toolTimeout.compareTo(Duration.ofSeconds(10)) > 0
                || maxTotalToolTime == null || maxTotalToolTime.isZero() || maxTotalToolTime.isNegative()
                || maxTotalToolTime.compareTo(Duration.ofSeconds(30)) > 0
                || allowedTools == null || riskLevels == null) {
            throw new IllegalArgumentException("Agent limits must be positive and cannot exceed the safety ceilings");
        }
    }
}
