package com.aicodehelper.retrieval;

import java.util.List;
import java.util.Map;

public record RetrievalResult(String query, String mode, String backend, String embeddingModel,
                              String embeddingVersion, String corpusHash, String reranker, double vectorMinScore,
                              boolean degraded, List<String> warnings, Map<String, Double> timingsMs,
                              List<RetrievalHit> hits) {}
