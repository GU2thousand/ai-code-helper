package com.aicodehelper.retrieval;

import com.aicodehelper.ingestion.DocumentChunk;

import java.sql.Array;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Properties;
import java.util.Set;
import java.util.UUID;

/** Persistent, exact cosine search. No approximate vector index is claimed or required. */
public final class PgVectorEmbeddingRepository implements EmbeddingRepository {
    private static final long REINDEX_LOCK = 0x4149434f44454cL;
    private static final String COLUMNS = "id, source, title, location, content, source_hash, ordinal, "
            + "embedding::text AS vector_text, embedding_model, embedding_version";
    private static final String UPSERT = """
            INSERT INTO ai_knowledge_chunks
                (id, source, title, location, content, source_hash, ordinal, embedding, embedding_model, embedding_version)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?::vector, ?, ?)
            ON CONFLICT (id) DO UPDATE SET source = EXCLUDED.source, title = EXCLUDED.title,
                location = EXCLUDED.location, content = EXCLUDED.content, source_hash = EXCLUDED.source_hash,
                ordinal = EXCLUDED.ordinal, embedding = EXCLUDED.embedding,
                embedding_model = EXCLUDED.embedding_model, embedding_version = EXCLUDED.embedding_version
            """;

    private final RetrievalProperties.Postgres config;
    private final ConnectionFactory connections;
    private final Object operationLock = new Object();
    private boolean schemaInitialized;
    private long retryAfterNanos;

    public PgVectorEmbeddingRepository(RetrievalProperties.Postgres config) {
        this(config, PgVectorEmbeddingRepository::connect);
    }

    PgVectorEmbeddingRepository(RetrievalProperties.Postgres config, ConnectionFactory connections) {
        this.config = Objects.requireNonNull(config);
        this.connections = Objects.requireNonNull(connections);
        if (config.getConnectTimeoutSeconds() < 1 || config.getQueryTimeoutSeconds() < 1) {
            throw new IllegalArgumentException("PostgreSQL timeouts must be positive");
        }
        if (config.getRetryBackoff() == null || config.getRetryBackoff().isNegative()
                || config.getRetryBackoff().compareTo(Duration.ofDays(1)) > 0) {
            throw new IllegalArgumentException("PostgreSQL retry backoff must be between zero and one day");
        }
    }

    @Override
    public void replaceAll(List<IndexedChunk> chunks) {
        List<IndexedChunk> snapshot = List.copyOf(chunks);
        validateSnapshot(snapshot);
        String snapshotId = EmbeddingSnapshotIdentity.of(snapshot);
        inTransaction(true, connection -> {
            try (PreparedStatement statement = prepare(connection, UPSERT)) {
                for (IndexedChunk indexed : snapshot) {
                    DocumentChunk chunk = indexed.chunk();
                    statement.setObject(1, UUID.fromString(chunk.id()));
                    statement.setString(2, chunk.source());
                    statement.setString(3, chunk.title());
                    statement.setString(4, chunk.location());
                    statement.setString(5, chunk.text());
                    statement.setString(6, chunk.sourceHash());
                    statement.setInt(7, chunk.ordinal());
                    statement.setString(8, vectorLiteral(indexed.embedding()));
                    statement.setString(9, indexed.embeddingModel());
                    statement.setString(10, indexed.embeddingVersion());
                    statement.addBatch();
                }
                if (!snapshot.isEmpty()) statement.executeBatch();
            }
            String[] ids = snapshot.stream().map(indexed -> indexed.chunk().id()).toArray(String[]::new);
            Array retainedIds = connection.createArrayOf("uuid", ids);
            try (PreparedStatement statement = prepare(connection,
                    "DELETE FROM ai_knowledge_chunks WHERE NOT (id = ANY (?))")) {
                statement.setArray(1, retainedIds);
                statement.executeUpdate();
            } finally {
                retainedIds.free();
            }
            try (PreparedStatement statement = prepare(connection, """
                    INSERT INTO ai_knowledge_index_state (singleton, snapshot_id) VALUES (TRUE, ?)
                    ON CONFLICT (singleton) DO UPDATE SET snapshot_id = EXCLUDED.snapshot_id
                    """)) {
                statement.setString(1, snapshotId);
                statement.executeUpdate();
            }
            return null;
        });
    }

    @Override
    public List<IndexedChunk> all() {
        return inTransaction(false, connection -> {
            try (PreparedStatement statement = prepare(connection,
                    "SELECT " + COLUMNS + " FROM ai_knowledge_chunks ORDER BY id");
                 ResultSet rows = statement.executeQuery()) {
                List<IndexedChunk> chunks = new ArrayList<>();
                while (rows.next()) chunks.add(readChunk(rows));
                return List.copyOf(chunks);
            }
        });
    }

    @Override
    public List<ScoredChunk> vectorSearch(float[] queryVector, int limit, double minScore) {
        return vectorSearch(queryVector, limit, minScore, null);
    }

    @Override
    public List<ScoredChunk> vectorSearch(float[] queryVector, int limit, double minScore, String expectedSnapshotId) {
        validateVector(queryVector);
        if (!Double.isFinite(minScore) || minScore < 0 || minScore > 1) {
            throw new IllegalArgumentException("Minimum vector score must be between zero and one");
        }
        if (limit < 1) return List.of();
        float[] query = queryVector.clone();
        return inTransaction(false, connection -> {
            if (expectedSnapshotId != null) {
                try (PreparedStatement state = prepare(connection,
                        "SELECT snapshot_id FROM ai_knowledge_index_state WHERE singleton = TRUE");
                     ResultSet rows = state.executeQuery()) {
                    if (!rows.next() || !expectedSnapshotId.equals(rows.getString("snapshot_id"))) {
                        throw new EmbeddingSnapshotMismatchException();
                    }
                }
            }
            try (PreparedStatement dimensions = prepare(connection,
                    "SELECT DISTINCT vector_dims(embedding) AS dimensions FROM ai_knowledge_chunks");
                 ResultSet rows = dimensions.executeQuery()) {
                while (rows.next()) {
                    if (rows.getInt("dimensions") != query.length) {
                        throw new IllegalArgumentException("Query vector dimension does not match the stored corpus; reindex required");
                    }
                }
            }
            // pgvector cosine distance = 1 - cosine similarity; convert to the shared [0,1] score.
            String sql = "SELECT * FROM (SELECT " + COLUMNS
                    + ", GREATEST(0.0, LEAST(1.0, 1.0 - (embedding <=> ?::vector) / 2.0)) AS score"
                    + " FROM ai_knowledge_chunks) scored WHERE score >= ? ORDER BY score DESC, id LIMIT ?";
            try (PreparedStatement statement = prepare(connection, sql)) {
                statement.setString(1, vectorLiteral(query));
                statement.setDouble(2, minScore);
                statement.setInt(3, limit);
                try (ResultSet rows = statement.executeQuery()) {
                    List<ScoredChunk> chunks = new ArrayList<>();
                    while (rows.next()) chunks.add(new ScoredChunk(readChunk(rows), rows.getDouble("score")));
                    return List.copyOf(chunks);
                }
            }
        });
    }

    @Override public String name() { return "pgvector"; }

    private <T> T inTransaction(boolean writing, SqlOperation<T> operation) {
        synchronized (operationLock) {
            if (retryAfterNanos != 0 && System.nanoTime() - retryAfterNanos < 0) throw unavailable();
            try (Connection connection = connections.open(config)) {
                connection.setAutoCommit(false);
                connection.setTransactionIsolation(Connection.TRANSACTION_READ_COMMITTED);
                try {
                    // Shared locks keep dimension checking and ranking on the same corpus; schema/reindex writes
                    // serialize across application instances without blocking other initialized readers.
                    String lockSql = writing || !schemaInitialized ? "SELECT pg_advisory_xact_lock(?)"
                            : "SELECT pg_advisory_xact_lock_shared(?)";
                    try (PreparedStatement lock = prepare(connection, lockSql)) {
                        lock.setLong(1, REINDEX_LOCK);
                        lock.execute();
                    }
                    if (!schemaInitialized) initializeSchema(connection);
                    T result = operation.execute(connection);
                    connection.commit();
                    schemaInitialized = true;
                    retryAfterNanos = 0;
                    return result;
                } catch (SQLException | RuntimeException failure) {
                    try { connection.rollback(); } catch (SQLException ignored) { /* preserve sanitized failure */ }
                    throw failure;
                }
            } catch (SQLException failure) {
                schemaInitialized = false;
                retryAfterNanos = System.nanoTime() + config.getRetryBackoff().toNanos();
                // JDBC errors can include connection strings, credentials and indexed document contents.
                throw unavailable();
            }
        }
    }

    private void initializeSchema(Connection connection) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.setQueryTimeout(config.getQueryTimeoutSeconds());
            statement.execute("CREATE EXTENSION IF NOT EXISTS vector");
            statement.execute("""
                    CREATE TABLE IF NOT EXISTS ai_knowledge_chunks (
                        id UUID PRIMARY KEY, source TEXT NOT NULL, title TEXT NOT NULL,
                        location TEXT NOT NULL, content TEXT NOT NULL, source_hash TEXT NOT NULL,
                        ordinal INTEGER NOT NULL, embedding vector NOT NULL,
                        embedding_model TEXT NOT NULL, embedding_version TEXT NOT NULL
                    )
                    """);
            statement.execute("""
                    CREATE TABLE IF NOT EXISTS ai_knowledge_index_state (
                        singleton BOOLEAN PRIMARY KEY CHECK (singleton), snapshot_id TEXT NOT NULL
                    )
                    """);
        }
    }

    private PreparedStatement prepare(Connection connection, String sql) throws SQLException {
        PreparedStatement statement = connection.prepareStatement(sql);
        try {
            statement.setQueryTimeout(config.getQueryTimeoutSeconds());
            return statement;
        } catch (SQLException failure) {
            statement.close();
            throw failure;
        }
    }

    private static Connection connect(RetrievalProperties.Postgres config) throws SQLException {
        Properties properties = new Properties();
        properties.setProperty("user", config.getUsername());
        properties.setProperty("password", config.getPassword());
        properties.setProperty("connectTimeout", Integer.toString(config.getConnectTimeoutSeconds()));
        properties.setProperty("socketTimeout", Integer.toString(config.getQueryTimeoutSeconds()));
        properties.setProperty("ApplicationName", "ai-code-helper-retrieval");
        return DriverManager.getConnection(config.getUrl(), properties);
    }

    private static void validateSnapshot(List<IndexedChunk> chunks) {
        Set<String> ids = new HashSet<>();
        int dimension = -1;
        String model = null;
        String version = null;
        for (IndexedChunk indexed : chunks) {
            DocumentChunk chunk = Objects.requireNonNull(indexed.chunk(), "Chunk is required");
            UUID.fromString(chunk.id());
            if (!ids.add(chunk.id())) throw new IllegalArgumentException("Duplicate chunk ID in corpus snapshot");
            Objects.requireNonNull(chunk.source(), "Source is required");
            Objects.requireNonNull(chunk.title(), "Title is required");
            Objects.requireNonNull(chunk.location(), "Location is required");
            Objects.requireNonNull(chunk.text(), "Content is required");
            Objects.requireNonNull(chunk.sourceHash(), "Source hash is required");
            Objects.requireNonNull(indexed.embeddingModel(), "Embedding model is required");
            Objects.requireNonNull(indexed.embeddingVersion(), "Embedding version is required");
            if (model != null && (!model.equals(indexed.embeddingModel()) || !version.equals(indexed.embeddingVersion()))) {
                throw new IllegalArgumentException("Corpus embeddings must use one model and version");
            }
            model = indexed.embeddingModel();
            version = indexed.embeddingVersion();
            float[] vector = indexed.embedding();
            validateVector(vector);
            if (dimension != -1 && dimension != vector.length) {
                throw new IllegalArgumentException("Corpus vectors must have the same dimension");
            }
            dimension = vector.length;
        }
    }

    private static void validateVector(float[] vector) {
        if (vector == null || vector.length == 0) throw new IllegalArgumentException("Embedding vector is required");
        boolean nonZero = false;
        for (float value : vector) {
            if (!Float.isFinite(value)) throw new IllegalArgumentException("Embedding vector must contain finite values");
            nonZero |= value != 0;
        }
        if (!nonZero) throw new IllegalArgumentException("Cosine search requires a nonzero vector");
    }

    private static String vectorLiteral(float[] vector) {
        StringBuilder result = new StringBuilder("[");
        for (int i = 0; i < vector.length; i++) {
            if (i != 0) result.append(',');
            result.append(Float.toString(vector[i]));
        }
        return result.append(']').toString();
    }

    private static IndexedChunk readChunk(ResultSet row) throws SQLException {
        String literal = row.getString("vector_text");
        String[] components = literal.substring(1, literal.length() - 1).split(",");
        float[] vector = new float[components.length];
        for (int i = 0; i < components.length; i++) vector[i] = Float.parseFloat(components[i]);
        DocumentChunk chunk = new DocumentChunk(row.getString("id"), row.getString("source"), row.getString("title"),
                row.getString("location"), row.getString("content"), row.getString("source_hash"), row.getInt("ordinal"));
        return new IndexedChunk(chunk, vector, row.getString("embedding_model"), row.getString("embedding_version"));
    }

    private static IllegalStateException unavailable() {
        return new IllegalStateException("PostgreSQL embedding repository unavailable; check database configuration and retry");
    }

    @FunctionalInterface interface ConnectionFactory { Connection open(RetrievalProperties.Postgres config) throws SQLException; }
    @FunctionalInterface private interface SqlOperation<T> { T execute(Connection connection) throws SQLException; }
}
