package com.aicodehelper.ai.local;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.UserMessage;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

final class LocalAnswerGenerator {

    private static final ObjectMapper JSON = new ObjectMapper();

    private static final Pattern RAG_AUGMENTATION = Pattern.compile(
            "(?is)\\s*Answer using the following information:.*$"
    );
    private static final Pattern MEMORY_QUESTION = Pattern.compile(
            "(?is).*(我(刚才|之前|上一条).{0,12}(说|问|提到).{0,8}(什么|啥)|what did i (just |previously )?(say|ask)).*"
    );
    private static final Pattern STRUCTURED_REPORT_GOAL = Pattern.compile(
            "(?is)\\[STRUCTURED_LEARNING_REPORT]\\s*<goal>(.*?)</goal>"
    );

    String answer(List<ChatMessage> messages) {
        List<String> rawUserMessages = messages.stream()
                .filter(UserMessage.class::isInstance)
                .map(UserMessage.class::cast)
                .map(this::textOf)
                .toList();
        String rawQuestion = rawUserMessages.isEmpty() ? "编程学习" : rawUserMessages.getLast();
        Matcher reportRequest = STRUCTURED_REPORT_GOAL.matcher(rawQuestion);
        if (reportRequest.find()) {
            return structuredReport(reportRequest.group(1));
        }

        List<String> userMessages = rawUserMessages.stream()
                .map(this::originalQuestion)
                .toList();
        String question = userMessages.isEmpty() ? "编程学习" : userMessages.getLast();

        String topic = compact(question);
        if (MEMORY_QUESTION.matcher(topic).matches() && userMessages.size() >= 2) {
            String previous = compact(userMessages.get(userMessages.size() - 2));
            return "当前运行在本地模拟模式，未调用外部模型。\n\n"
                    + "你上一条消息是：“" + abbreviate(previous, 120) + "”。";
        }
        String lower = topic.toLowerCase(Locale.ROOT);
        String guidance;
        if (lower.contains("spring") || lower.contains("java")) {
            guidance = "建议先用一个小型 REST API 验证分层、参数校验和测试，再逐步加入数据库、缓存与异步任务。";
        } else if (lower.contains("vue") || lower.contains("前端") || lower.contains("javascript")) {
            guidance = "建议把页面拆成状态、展示和数据访问三层，并为加载中、失败、空数据和移动端布局分别设计状态。";
        } else if (lower.contains("面试") || lower.contains("简历")) {
            guidance = "建议按“知识点复盘—项目故事—限时模拟—复盘改进”的节奏准备，并用可量化结果描述项目贡献。";
        } else {
            guidance = "建议先明确一个可验证的小目标，完成最小实现与测试，再根据反馈迭代；遇到问题时记录现象、假设和验证结果。";
        }

        return "当前运行在本地模拟模式，未调用外部模型。\n\n"
                + "针对“" + abbreviate(topic, 72) + "”：" + guidance
                + "\n\n配置 `DASHSCOPE_API_KEY` 后，同一接口会自动使用通义千问。";
    }

    private String structuredReport(String goal) {
        String topic = compact(goal);
        String lower = topic.toLowerCase(Locale.ROOT);
        String title;
        String summary;
        List<String> goals;
        List<Map<String, Object>> weeks;
        List<String> projects;
        List<String> interview;

        if (lower.contains("python") || lower.contains("数据") || lower.contains("机器学习")
                || lower.contains("ai") || lower.contains("人工智能")) {
            title = "Python 与数据方向四周行动报告";
            summary = "围绕“" + abbreviate(topic, 60)
                    + "”，先建立 Python 数据处理基线，再完成可复现分析项目并准备数据岗位面试证据。";
            goals = List.of(
                    "熟练使用 Python 核心语法、类型标注与自动化测试",
                    "能用 pandas 与可视化工具完成数据清洗、分析和结论表达",
                    "完成一个包含数据字典、实验记录和可复现脚本的作品集项目"
            );
            weeks = List.of(
                    week(1, "Python 基线", "完成语法与标准库自测", "为数据处理函数补齐单元测试"),
                    week(2, "数据分析", "完成一份真实数据清洗 notebook", "用图表解释三个可验证结论"),
                    week(3, "项目工程化", "把分析流程拆成可复用模块", "补充依赖锁定、日志和 README"),
                    week(4, "作品集与面试", "发布项目演示和结果摘要", "完成两轮 Python/SQL 模拟面试")
            );
            projects = List.of("可复现的公开数据洞察平台", "带评估指标与实验追踪的轻量机器学习项目");
            interview = List.of(
                    "准备 Python 数据结构与复杂度示例",
                    "练习 SQL 聚合、窗口函数和查询优化",
                    "用数据口径、异常处理和业务结论复述项目",
                    "准备一次模型或分析结论失效后的排查案例"
            );
        } else if (lower.contains("vue") || lower.contains("react") || lower.contains("前端")
                || lower.contains("javascript") || lower.contains("typescript")) {
            title = "现代前端四周学习与求职报告";
            summary = "围绕“" + abbreviate(topic, 60)
                    + "”，用组件化、状态管理、可访问性和测试构建可展示的前端交付能力。";
            goals = List.of(
                    "掌握组件状态、组合式逻辑与 TypeScript 边界建模",
                    "能实现响应式、可访问且具有错误状态的完整页面",
                    "完成具备单元测试和端到端验证的前端作品集项目"
            );
            weeks = List.of(
                    week(1, "框架与类型基础", "完成组件通信与状态管理练习", "为核心工具函数添加测试"),
                    week(2, "完整交互", "实现加载、空数据和失败状态", "完成移动端与键盘交互适配"),
                    week(3, "质量与性能", "执行性能和可访问性检查", "修复 XSS、竞态与重复请求风险"),
                    week(4, "作品集与面试", "部署演示并完善架构说明", "完成两轮前端系统设计模拟")
            );
            projects = List.of("安全的 SSE 实时协作界面", "带离线状态和权限边界的任务管理器");
            interview = List.of(
                    "解释浏览器渲染、事件循环与网络缓存",
                    "比较本地状态、服务端状态和持久化状态",
                    "用实例说明性能、可访问性与安全取舍",
                    "准备一次前端竞态或内存泄漏排查案例"
            );
        } else if (lower.contains("java") || lower.contains("spring") || lower.contains("后端")) {
            title = "Java 后端四周学习与求职报告";
            summary = "围绕“" + abbreviate(topic, 60)
                    + "”，从 Java 21、Spring Boot 分层和测试出发，形成可运行、可解释的后端项目证据。";
            goals = List.of(
                    "掌握 Java 21、集合并发与异常处理的核心原理",
                    "能设计带参数校验、错误契约和测试的 Spring Boot API",
                    "完成包含数据库、缓存与可观测性的后端作品集项目"
            );
            weeks = List.of(
                    week(1, "Java 与 HTTP 基线", "完成核心知识自测", "实现带校验和测试的 REST API"),
                    week(2, "数据与事务", "加入数据库迁移和事务边界", "验证索引与慢查询分析"),
                    week(3, "可靠性", "实现缓存、限流和幂等策略", "补齐异常、并发与安全测试"),
                    week(4, "展示与面试", "整理架构图和压测结果", "完成两轮 Java 后端模拟面试")
            );
            projects = List.of("带 RAG 与流式响应的 AI 服务", "具备缓存、限流和审计日志的知识 API");
            interview = List.of(
                    "准备 JVM、集合、并发和网络高频题",
                    "用事务与幂等案例解释一致性取舍",
                    "用 STAR 结构复述两个项目难点",
                    "准备一次从日志和指标定位故障的案例"
            );
        } else {
            title = "编程能力四周行动报告";
            summary = "围绕“" + abbreviate(topic, 60)
                    + "”，以可验证的小步交付建立基础、项目质量和求职表达能力。";
            goals = List.of("完成方向相关的知识基线评估", "交付一个有测试和文档的完整项目", "能清晰解释关键设计取舍");
            weeks = List.of(
                    week(1, "目标与基线", "拆分四周可衡量目标", "完成一次知识与项目盘点"),
                    week(2, "核心实现", "完成一个端到端业务切片", "记录关键技术决策"),
                    week(3, "质量改进", "补齐边界和失败测试", "执行性能与安全检查"),
                    week(4, "展示与复盘", "完善演示、README 和架构图", "完成两轮限时模拟面试")
            );
            projects = List.of("与目标方向一致的端到端应用", "解决真实问题的自动化工具");
            interview = List.of("准备 90 秒自我介绍", "用 STAR 结构复述项目难点", "练习核心原理与代码题", "每轮模拟后记录三项改进");
        }

        Map<String, Object> report = new LinkedHashMap<>();
        report.put("title", title);
        report.put("summary", summary);
        report.put("goals", goals);
        report.put("weeklyPlan", weeks);
        report.put("recommendedProjects", projects);
        report.put("interviewChecklist", interview);
        try {
            return JSON.writeValueAsString(report);
        } catch (JsonProcessingException error) {
            throw new IllegalStateException("Unable to generate local structured report", error);
        }
    }

    private Map<String, Object> week(int number, String focus, String... tasks) {
        Map<String, Object> week = new LinkedHashMap<>();
        week.put("week", number);
        week.put("focus", focus);
        week.put("tasks", List.of(tasks));
        return week;
    }

    private String textOf(UserMessage message) {
        return message.hasSingleText() ? message.singleText() : message.toString();
    }

    private String originalQuestion(String text) {
        return RAG_AUGMENTATION.matcher(text).replaceFirst("").trim();
    }

    private String compact(String value) {
        return value == null ? "编程学习" : value.replaceAll("\\s+", " ").trim();
    }

    private String abbreviate(String value, int maxCodePoints) {
        int count = value.codePointCount(0, value.length());
        if (count <= maxCodePoints) {
            return value;
        }
        int end = value.offsetByCodePoints(0, maxCodePoints);
        return value.substring(0, end) + "…";
    }
}
