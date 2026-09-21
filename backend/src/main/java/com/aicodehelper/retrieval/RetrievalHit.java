package com.aicodehelper.retrieval;

public record RetrievalHit(String chunkId, String source, String title, String location, String text,
                           String sourceHash, Double lexicalScore, Double vectorScore, Double fusionScore,
                           Double rerankScore, Integer lexicalRank, Integer vectorRank, Integer fusedRank,
                           Integer finalRank) {}
