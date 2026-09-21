package com.aicodehelper.retrieval;

/** The shared database belongs to a different corpus/model generation; serving those hits would be invalid. */
public final class EmbeddingSnapshotMismatchException extends IllegalStateException {
    public EmbeddingSnapshotMismatchException() { super("Stored embedding snapshot differs from the requested corpus and model generation"); }
}
