package com.aicodehelper.ai;

import com.aicodehelper.ai.api.ChatResponse;
import com.aicodehelper.ai.api.LearningReport;
import com.aicodehelper.ai.api.LearningReportDraft;
import com.aicodehelper.ai.api.RagResponse;
import com.aicodehelper.ai.api.RagSource;
import com.aicodehelper.error.ApiException;
import com.aicodehelper.error.UpstreamAiException;
import com.aicodehelper.guardrail.SafeInputGuardrail;
import dev.langchain4j.rag.content.Content;
import dev.langchain4j.rag.content.ContentMetadata;
import dev.langchain4j.service.Result;
import dev.langchain4j.service.TokenStream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.util.List;
import java.util.Set;

@Service
public class AiChatService {

    private static final Logger log = LoggerFactory.getLogger(AiChatService.class);

    private final CoreAssistant coreAssistant;
    private final RagAssistant ragAssistant;
    private final ReportAssistant reportAssistant;
    private final SafeInputGuardrail guardrail;
    private final ModelRuntimeInfo modelInfo;
    private final Clock clock;

    public AiChatService(
            CoreAssistant coreAssistant,
            RagAssistant ragAssistant,
            ReportAssistant reportAssistant,
            SafeInputGuardrail guardrail,
            ModelRuntimeInfo modelInfo,
            Clock clock
    ) {
        this.coreAssistant = coreAssistant;
        this.ragAssistant = ragAssistant;
        this.reportAssistant = reportAssistant;
        this.guardrail = guardrail;
        this.modelInfo = modelInfo;
        this.clock = clock;
    }

    public void validateMessage(String message) {
        guardrail.checkOrThrow(message);
    }

    public ChatResponse chat(String conversationKey, String memoryId, String message) {
        String safeMessage = guardrail.checkOrThrow(message);
        try {
            log.info("Chat request memoryKey={} inputCharacters={} mode={}",
                    keyFingerprint(conversationKey), safeMessage.length(), modelInfo.chatProvider());
            Result<String> result = coreAssistant.chat(conversationKey, safeMessage);
            return new ChatResponse(memoryId, result.content(), modelInfo.chatModel(), clock.instant());
        } catch (ApiException error) {
            throw error;
        } catch (RuntimeException error) {
            log.warn("Chat request failed errorType={}", error.getClass().getSimpleName());
            throw new UpstreamAiException();
        }
    }

    public TokenStream stream(String conversationKey, String message) {
        String safeMessage = guardrail.checkOrThrow(message);
        try {
            log.info("Streaming chat request memoryKey={} inputCharacters={} mode={}",
                    keyFingerprint(conversationKey), safeMessage.length(), modelInfo.chatProvider());
            return coreAssistant.stream(conversationKey, safeMessage);
        } catch (ApiException error) {
            throw error;
        } catch (RuntimeException error) {
            log.warn("Streaming chat setup failed errorType={}", error.getClass().getSimpleName());
            throw new UpstreamAiException();
        }
    }

    public RagResponse rag(String conversationKey, String memoryId, String message) {
        String safeMessage = guardrail.checkOrThrow(message);
        try {
            log.info("RAG request memoryKey={} inputCharacters={} mode={}",
                    keyFingerprint(conversationKey), safeMessage.length(), modelInfo.embeddingProvider());
            Result<String> result = ragAssistant.chat(conversationKey, safeMessage);
            List<RagSource> sources = result.sources() == null
                    ? List.of()
                    : result.sources().stream().map(this::source).toList();
            return new RagResponse(
                    memoryId,
                    result.content(),
                    modelInfo.chatModel(),
                    sources,
                    clock.instant()
            );
        } catch (ApiException error) {
            throw error;
        } catch (RuntimeException error) {
            log.warn("RAG request failed errorType={}", error.getClass().getSimpleName());
            throw new UpstreamAiException();
        }
    }

    public LearningReport report(String conversationKey, String memoryId, String message) {
        String safeMessage = guardrail.checkOrThrow(message);
        String reportInput = safeMessage.length() > 3_400 ? safeMessage.substring(0, 3_400) : safeMessage;
        try {
            log.info("Structured report request memoryKey={} inputCharacters={} mode={}",
                    keyFingerprint(conversationKey), reportInput.length(), modelInfo.chatProvider());
            LearningReportDraft draft = validateReport(reportAssistant.create(reportInput));
            List<LearningReport.WeeklyPlan> plan = draft.weeklyPlan().stream()
                    .map(week -> new LearningReport.WeeklyPlan(week.week(), week.focus(), week.tasks()))
                    .toList();
            return new LearningReport(
                    memoryId,
                    draft.title(),
                    draft.summary(),
                    draft.goals(),
                    plan,
                    draft.recommendedProjects(),
                    draft.interviewChecklist(),
                    modelInfo.chatModel(),
                    clock.instant()
            );
        } catch (ApiException error) {
            throw error;
        } catch (RuntimeException error) {
            log.warn("Structured report request failed errorType={}", error.getClass().getSimpleName());
            throw new UpstreamAiException();
        }
    }

    private LearningReportDraft validateReport(LearningReportDraft draft) {
        if (draft == null
                || blank(draft.title())
                || blank(draft.summary())
                || invalidStringList(draft.goals(), 3, 6)
                || invalidList(draft.weeklyPlan(), 4, 4)
                || invalidStringList(draft.recommendedProjects(), 2, 4)
                || invalidStringList(draft.interviewChecklist(), 4, 8)
                || draft.weeklyPlan().stream().anyMatch(week -> week == null
                || week.week() < 1
                || week.week() > 4
                || blank(week.focus())
                || invalidStringList(week.tasks(), 2, 4))
                || !draft.weeklyPlan().stream().map(LearningReportDraft.WeeklyPlanDraft::week)
                .collect(java.util.stream.Collectors.toSet()).equals(Set.of(1, 2, 3, 4))) {
            throw new IllegalStateException("Model returned an incomplete learning report");
        }
        return draft;
    }

    private boolean blank(String value) {
        return value == null || value.isBlank();
    }

    private boolean invalidList(List<?> value, int min, int max) {
        return value == null || value.size() < min || value.size() > max;
    }

    private boolean invalidStringList(List<String> value, int min, int max) {
        return invalidList(value, min, max) || value.stream().anyMatch(this::blank);
    }

    public RagSource source(Content content) {
        var metadata = content.textSegment().metadata();
        Object scoreValue = content.metadata().get(ContentMetadata.SCORE);
        Double score = scoreValue instanceof Number number ? number.doubleValue() : null;
        return new RagSource(
                metadata.getString("title"),
                metadata.getString("source"),
                metadata.getString("location"),
                abbreviate(content.textSegment().text().replaceFirst("^\\[chunk:[^\\]]+\\]\\s*", ""), 260),
                score,
                metadata.getString("chunk_id")
        );
    }

    private String abbreviate(String value, int maxCharacters) {
        String compact = value.replaceAll("\\s+", " ").trim();
        return compact.length() <= maxCharacters ? compact : compact.substring(0, maxCharacters) + "…";
    }

    private String keyFingerprint(String conversationKey) {
        return Integer.toUnsignedString(conversationKey.hashCode(), 16);
    }
}
