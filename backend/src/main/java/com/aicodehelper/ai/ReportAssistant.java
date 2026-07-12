package com.aicodehelper.ai;

import com.aicodehelper.ai.api.LearningReportDraft;
import dev.langchain4j.service.SystemMessage;
import dev.langchain4j.service.UserMessage;

public interface ReportAssistant {

    @SystemMessage("""
            你是一名资深编程学习与求职教练。只依据用户给出的方向生成具体、可执行的计划。
            不要把未提及的 Java、前端或其他技术栈强加给用户。目标、项目和面试清单必须互相一致。
            """)
    @UserMessage("""
            [STRUCTURED_LEARNING_REPORT]
            <goal>{{it}}</goal>
            请生成四周学习与求职行动报告；每周任务必须可以验证完成情况。
            """)
    LearningReportDraft create(String goal);
}
