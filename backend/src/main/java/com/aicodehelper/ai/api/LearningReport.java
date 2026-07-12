package com.aicodehelper.ai.api;

import java.time.Instant;
import java.util.List;

public record LearningReport(
        String memoryId,
        String title,
        String summary,
        List<String> goals,
        List<WeeklyPlan> weeklyPlan,
        List<String> recommendedProjects,
        List<String> interviewChecklist,
        String model,
        Instant generatedAt
) {
    public record WeeklyPlan(int week, String focus, List<String> tasks) {
    }
}
