package com.aicodehelper.retrieval;

import java.util.List;

public interface Reranker {
    List<RetrievalHit> rerank(String query, List<RetrievalHit> candidates) throws Exception;
    String name();
}
