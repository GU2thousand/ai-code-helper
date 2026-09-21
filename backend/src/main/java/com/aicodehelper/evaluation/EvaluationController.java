package com.aicodehelper.evaluation;

import com.aicodehelper.agent.AgentRuntimeEvaluation;
import com.aicodehelper.ai.ModelRuntimeInfo;
import com.aicodehelper.error.ApiException;
import com.aicodehelper.retrieval.RetrievalHit;
import com.aicodehelper.retrieval.RetrievalResult;
import com.aicodehelper.retrieval.RetrievalService;
import com.aicodehelper.retrieval.RetrievalProperties;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.Semaphore;
import java.util.function.Supplier;

/** Opt-in, authenticated, bounded evaluator surface. Never accepts arbitrary tools or fault URLs. */
@RestController
@RequestMapping("/api/evaluation")
@ConditionalOnProperty(name = "app.evaluation.enabled", havingValue = "true")
public class EvaluationController {
    private final EvaluationAccess access;
    private final RetrievalService retrieval;
    private final AgentRuntimeEvaluation agent;
    private final ModelRuntimeInfo model;
    private final RetrievalProperties config;
    private final Semaphore capacity = new Semaphore(2);

    public EvaluationController(EvaluationAccess access, RetrievalService retrieval,
                                AgentRuntimeEvaluation agent, ModelRuntimeInfo model,
                                RetrievalProperties config) {
        this.access = access;
        this.retrieval = retrieval;
        this.agent = agent;
        this.model = model;
        this.config = config;
    }

    @GetMapping("/status")
    public Map<String, Object> status(HttpServletRequest request) {
        access.authorize(request);
        return Map.of("schemaVersion", 1, "chatProvider", model.chatProvider(),
                "embeddingProvider", model.embeddingProvider(), "embeddingModel", model.embeddingModel(),
                "ingestion", retrieval.ingestionStatus(), "agentScenarios", AgentRuntimeEvaluation.SCENARIOS,
                "agentEvaluationMode", "controlled_runtime",
                "retrievalConfig", Map.of("mode", config.getMode(), "backend", config.getBackend(),
                        "candidates", config.getCandidates(), "rrfK", config.getRrfK(),
                        "embeddingVersion", config.getEmbeddingVersion(),
                        "reranker", config.getReranker().getType()));
    }

    @PostMapping("/retrieval")
    public Map<String, Object> retrieve(@Valid @RequestBody RetrievalRequest body,
                                         HttpServletRequest request) {
        access.authorize(request);
        return bounded(() -> {
            long start = System.nanoTime();
            RetrievalResult result = retrieval.search(body.question(), body.mode(), body.k());
            Map<String, Object> response = new LinkedHashMap<>();
            response.put("schemaVersion", 1);
            response.put("mode", result.mode());
            response.put("backend", result.backend());
            response.put("embeddingProvider", model.embeddingProvider());
            response.put("embeddingModel", result.embeddingModel());
            response.put("embeddingVersion", result.embeddingVersion());
            response.put("corpusHash", result.corpusHash());
            response.put("reranker", result.reranker());
            response.put("vectorMinScore", result.vectorMinScore());
            response.put("degraded", result.degraded());
            response.put("warnings", result.warnings());
            response.put("timingsMs", result.timingsMs());
            response.put("durationMs", (System.nanoTime() - start) / 1_000_000.0);
            response.put("results", result.hits().stream().map(this::hit).toList());
            return response;
        });
    }

    @PostMapping("/agent")
    public AgentRuntimeEvaluation.EvaluationResult agent(@Valid @RequestBody AgentRequest body,
                                                         HttpServletRequest request) {
        access.authorize(request);
        return bounded(() -> {
            try {
                return agent.evaluate(body.scenario());
            } catch (IllegalArgumentException error) {
                throw new ApiException(HttpStatus.BAD_REQUEST, "UNKNOWN_AGENT_SCENARIO", "Unknown agent fixture");
            }
        });
    }

    private Map<String, Object> hit(RetrievalHit hit) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("chunkId", hit.chunkId());
        result.put("source", hit.source());
        result.put("sourceHash", hit.sourceHash());
        result.put("title", hit.title());
        result.put("location", hit.location());
        result.put("text", hit.text());
        result.put("lexicalScore", hit.lexicalScore());
        result.put("vectorScore", hit.vectorScore());
        result.put("fusionScore", hit.fusionScore());
        result.put("rerankScore", hit.rerankScore());
        result.put("lexicalRank", hit.lexicalRank());
        result.put("vectorRank", hit.vectorRank());
        result.put("fusedRank", hit.fusedRank());
        result.put("finalRank", hit.finalRank());
        result.put("score", hit.rerankScore() != null ? hit.rerankScore()
                : hit.fusionScore() != null ? hit.fusionScore()
                : hit.vectorScore() != null ? hit.vectorScore() : hit.lexicalScore());
        return result;
    }

    private <T> T bounded(Supplier<T> action) {
        if (!capacity.tryAcquire()) {
            throw new ApiException(HttpStatus.TOO_MANY_REQUESTS, "EVALUATION_BUSY", "Two evaluations are already active");
        }
        try { return action.get(); }
        finally { capacity.release(); }
    }

    public record RetrievalRequest(@NotBlank @Size(max = 4000) String question,
            @NotBlank @Pattern(regexp = "vector|lexical|hybrid|hybrid_rerank") String mode,
            @Min(1) @Max(30) int k) { }

    public record AgentRequest(@NotBlank @Size(max = 64) String scenario) { }
}
