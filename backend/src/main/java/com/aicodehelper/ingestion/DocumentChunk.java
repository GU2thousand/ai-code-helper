package com.aicodehelper.ingestion;

/** Identity is derived from canonical source, chunk content and duplicate ordinal, never wall clock time. */
public record DocumentChunk(String id, String source, String title, String location, String text,
                            String sourceHash, int ordinal) {}
