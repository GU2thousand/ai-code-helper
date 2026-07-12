package com.aicodehelper.ai.api;

import dev.langchain4j.model.output.structured.Description;

import java.util.List;

@Description("A practical programming learning and job-preparation plan tailored to the user's goal")
public record LearningReportDraft(
        @Description("A concise Chinese report title") String title,
        @Description("A specific Chinese summary grounded in the user's stated goal") String summary,
        @Description("Three to six measurable learning goals") List<String> goals,
        @Description("A four-week execution plan") List<WeeklyPlanDraft> weeklyPlan,
        @Description("Two to four portfolio projects relevant to the user's technology direction")
        List<String> recommendedProjects,
        @Description("Four to eight interview preparation actions relevant to the target role")
        List<String> interviewChecklist
) {
    @Description("One week of the learning plan")
    public record WeeklyPlanDraft(
            @Description("Week number starting at 1") int week,
            @Description("The week's concise focus") String focus,
            @Description("Two to four verifiable tasks") List<String> tasks
    ) {
    }
}
