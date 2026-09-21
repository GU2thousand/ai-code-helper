package com.aicodehelper.retrieval;

import com.aicodehelper.ingestion.DocumentChunk;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import java.sql.Array;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.util.List;
import java.util.Properties;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class PgVectorEmbeddingRepositoryTest {
    @Test
    void sanitizesConnectionFailureAndBacksOffWithoutLeakingTheCause() throws Exception {
        RetrievalProperties.Postgres config = config();
        config.setRetryBackoff(Duration.ofMinutes(1));
        PgVectorEmbeddingRepository.ConnectionFactory factory = mock(PgVectorEmbeddingRepository.ConnectionFactory.class);
        when(factory.open(config)).thenThrow(new SQLException("jdbc://private-host password=super-secret"));
        PgVectorEmbeddingRepository repository = new PgVectorEmbeddingRepository(config, factory);

        assertThatThrownBy(repository::all).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("unavailable").hasMessageNotContaining("private-host")
                .hasMessageNotContaining("super-secret").hasNoCause();
        assertThatThrownBy(repository::all).isInstanceOf(IllegalStateException.class).hasNoCause();
        verify(factory, times(1)).open(config);
    }

    @Test
    void retriesSchemaInitializationAfterDatabaseRecovers() throws Exception {
        RetrievalProperties.Postgres config = config();
        JdbcFixture jdbc = jdbc();
        AtomicInteger calls = new AtomicInteger();
        PgVectorEmbeddingRepository repository = new PgVectorEmbeddingRepository(config, ignored -> {
            if (calls.getAndIncrement() == 0) throw new SQLException("initial outage");
            return jdbc.connection();
        });

        assertThatThrownBy(repository::all).isInstanceOf(IllegalStateException.class);
        assertThat(repository.all()).isEmpty();
        verify(jdbc.ddl()).execute("CREATE EXTENSION IF NOT EXISTS vector");
        verify(jdbc.connection()).commit();
        assertThat(calls.get()).isEqualTo(2);
    }

    @Test
    void rollsBackTheEntireReindexWhenAnyUpsertFails() throws Exception {
        JdbcFixture jdbc = jdbc();
        doThrow(new SQLException("sensitive document content")).when(jdbc.statement()).executeBatch();
        PgVectorEmbeddingRepository repository = new PgVectorEmbeddingRepository(config(), ignored -> jdbc.connection());

        assertThatThrownBy(() -> repository.replaceAll(List.of(chunk(1, new float[] {1, 0}))))
                .isInstanceOf(IllegalStateException.class).hasMessageNotContaining("sensitive").hasNoCause();
        verify(jdbc.connection()).rollback();
        verify(jdbc.connection(), never()).commit();
        verify(jdbc.statement(), never()).executeUpdate();
    }

    @Test
    void deletesMissingIdsAndCommitsAnEmptySnapshotAtomically() throws Exception {
        JdbcFixture jdbc = jdbc();
        PgVectorEmbeddingRepository repository = new PgVectorEmbeddingRepository(config(), ignored -> jdbc.connection());

        repository.replaceAll(List.of());

        verify(jdbc.connection()).setAutoCommit(false);
        verify(jdbc.connection()).prepareStatement("SELECT pg_advisory_xact_lock(?)");
        verify(jdbc.connection()).prepareStatement("DELETE FROM ai_knowledge_chunks WHERE NOT (id = ANY (?))");
        verify(jdbc.statement()).setArray(1, jdbc.ids());
        verify(jdbc.statement(), times(2)).executeUpdate();
        verify(jdbc.statement()).setString(1, EmbeddingSnapshotIdentity.of(List.of()));
        verify(jdbc.ids()).free();
        verify(jdbc.connection()).commit();
    }

    @Test
    void rejectsInvalidSnapshotsBeforeOpeningADatabaseConnection() {
        PgVectorEmbeddingRepository.ConnectionFactory factory = mock(PgVectorEmbeddingRepository.ConnectionFactory.class);
        PgVectorEmbeddingRepository repository = new PgVectorEmbeddingRepository(config(), factory);
        IndexedChunk first = chunk(1, new float[] {1, 0});

        assertThatThrownBy(() -> repository.replaceAll(List.of(first, first)))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("Duplicate");
        assertThatThrownBy(() -> repository.replaceAll(List.of(first, chunk(2, new float[] {1, 0, 0}))))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("same dimension");
        for (float[] invalid : List.of(new float[] {0, 0}, new float[] {Float.NaN, 1},
                new float[] {Float.POSITIVE_INFINITY}, new float[] {})) {
            assertThatThrownBy(() -> repository.replaceAll(List.of(chunk(2, invalid))))
                    .isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> repository.vectorSearch(invalid, 5, 0))
                    .isInstanceOf(IllegalArgumentException.class);
        }
        verifyNoInteractions(factory);
    }

    @Test
    void rejectsQueryDimensionMismatchWithoutRunningTheCosineOperator() throws Exception {
        JdbcFixture jdbc = jdbc();
        when(jdbc.rows().next()).thenReturn(true, false);
        when(jdbc.rows().getInt("dimensions")).thenReturn(3);
        PgVectorEmbeddingRepository repository = new PgVectorEmbeddingRepository(config(), ignored -> jdbc.connection());

        assertThatThrownBy(() -> repository.vectorSearch(new float[] {1, 0}, 5, 0))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("dimension");
        verify(jdbc.connection()).rollback();
        verify(jdbc.statement(), times(1)).executeQuery();
    }

    @Test
    void rejectsAMissingOrDifferentSnapshotIdentityBeforeInspectingVectors() throws Exception {
        for (boolean metadataExists : List.of(false, true)) {
            JdbcFixture jdbc = jdbc();
            when(jdbc.rows().next()).thenReturn(metadataExists);
            when(jdbc.rows().getString("snapshot_id")).thenReturn("different-generation");
            PgVectorEmbeddingRepository repository = new PgVectorEmbeddingRepository(config(), ignored -> jdbc.connection());

            assertThatThrownBy(() -> repository.vectorSearch(new float[] {1, 0}, 5, 0, "expected-generation"))
                    .isInstanceOf(EmbeddingSnapshotMismatchException.class);

            verify(jdbc.connection()).prepareStatement("SELECT pg_advisory_xact_lock(?)");
            verify(jdbc.connection()).rollback();
            verify(jdbc.connection(), never()).commit();
            verify(jdbc.connection(), never()).prepareStatement(contains("vector_dims"));
            verify(jdbc.connection(), never()).prepareStatement(contains("<=>"));
        }
    }

    @Test
    void rollsBackChunksWhenUpdatingTheirSnapshotIdentityFails() throws Exception {
        JdbcFixture jdbc = jdbc();
        PreparedStatement identityUpdate = mock(PreparedStatement.class);
        when(jdbc.connection().prepareStatement(contains("INSERT INTO ai_knowledge_index_state")))
                .thenReturn(identityUpdate);
        when(identityUpdate.executeUpdate()).thenThrow(new SQLException("metadata storage failure"));
        PgVectorEmbeddingRepository repository = new PgVectorEmbeddingRepository(config(), ignored -> jdbc.connection());

        assertThatThrownBy(() -> repository.replaceAll(List.of(chunk(1, new float[] {1, 0}))))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("unavailable").hasNoCause();

        verify(jdbc.statement()).executeBatch();
        verify(jdbc.statement()).executeUpdate();
        verify(jdbc.connection()).rollback();
        verify(jdbc.connection(), never()).commit();
    }

    /** Opt in against a pgvector-capable test database; every test object lives in a unique temporary schema. */
    @Test
    @EnabledIfEnvironmentVariable(named = "TEST_PGVECTOR_URL", matches = ".+")
    void realPgvectorPersistsReconcilesRanksAndPreservesOldCorpusOnFailure() throws Exception {
        RetrievalProperties.Postgres config = config();
        config.setUrl(System.getenv("TEST_PGVECTOR_URL"));
        config.setUsername(System.getenv().getOrDefault("TEST_PGVECTOR_USERNAME", "postgres"));
        config.setPassword(System.getenv().getOrDefault("TEST_PGVECTOR_PASSWORD", ""));
        String schema = "retrieval_test_" + UUID.randomUUID().toString().replace("-", "");
        Properties properties = new Properties();
        properties.setProperty("user", config.getUsername());
        properties.setProperty("password", config.getPassword());
        properties.setProperty("connectTimeout", "3");
        properties.setProperty("socketTimeout", "5");
        try (Connection administration = DriverManager.getConnection(config.getUrl(), properties);
             Statement ddl = administration.createStatement()) {
            ddl.setQueryTimeout(5);
            ddl.execute("CREATE EXTENSION IF NOT EXISTS vector WITH SCHEMA public");
            ddl.execute("CREATE SCHEMA " + schema);
            try {
                config.setUrl(config.getUrl() + (config.getUrl().contains("?") ? "&" : "?")
                        + "currentSchema=" + schema + ",public");
                PgVectorEmbeddingRepository repository = new PgVectorEmbeddingRepository(config);
                IndexedChunk first = chunk(1, new float[] {1, 0});
                IndexedChunk tied = chunk(2, new float[] {1, 0});
                IndexedChunk opposite = chunk(3, new float[] {-1, 0});
                repository.replaceAll(List.of(opposite, tied, first));

                PgVectorEmbeddingRepository restarted = new PgVectorEmbeddingRepository(config);
                assertThat(restarted.all()).extracting(value -> value.chunk().id())
                        .containsExactly(first.chunk().id(), tied.chunk().id(), opposite.chunk().id());
                List<ScoredChunk> ranked = restarted.vectorSearch(new float[] {1, 0}, 10, 0);
                assertThat(ranked).extracting(value -> value.indexedChunk().chunk().id())
                        .containsExactly(first.chunk().id(), tied.chunk().id(), opposite.chunk().id());
                assertThat(ranked).extracting(ScoredChunk::score).containsExactly(1.0, 1.0, 0.0);
                assertThat(restarted.vectorSearch(new float[] {1, 0}, 10, 0.75)).hasSize(2);
                restarted.replaceAll(List.of(first));
                restarted.replaceAll(List.of(first));
                assertThat(restarted.all()).hasSize(1);

                ddl.execute("ALTER TABLE " + schema + ".ai_knowledge_chunks ADD CONSTRAINT test_reject_content "
                        + "CHECK (content <> 'rejected')");
                DocumentChunk badChunk = new DocumentChunk(tied.chunk().id(), "test", "test", "line:1",
                        "rejected", "hash", 0);
                assertThatThrownBy(() -> restarted.replaceAll(List.of(
                        new IndexedChunk(badChunk, new float[] {1, 0}, "test-model", "v1"))))
                        .isInstanceOf(IllegalStateException.class);
                assertThat(restarted.all()).extracting(value -> value.chunk().id()).containsExactly(first.chunk().id());
                assertThat(restarted.vectorSearch(new float[] {1, 0}, 1, 0,
                        EmbeddingSnapshotIdentity.of(List.of(first)))).hasSize(1);

                // A complete reindex can change dimensions without an implicit model/version mix.
                restarted.replaceAll(List.of(chunk(4, new float[] {1, 0, 0})));
                assertThat(restarted.vectorSearch(new float[] {1, 0, 0}, 1, 0)).hasSize(1);
                restarted.replaceAll(List.of());
                assertThat(restarted.all()).isEmpty();
            } finally {
                ddl.execute("DROP SCHEMA " + schema + " CASCADE");
            }
        }
    }

    @Test
    @EnabledIfEnvironmentVariable(named = "TEST_PGVECTOR_URL", matches = ".+")
    void realPgvectorRejectsOtherInstancesModelVersionOrCorpusAtTheSameDimensions() throws Exception {
        RetrievalProperties.Postgres config = config();
        config.setUrl(System.getenv("TEST_PGVECTOR_URL"));
        config.setUsername(System.getenv().getOrDefault("TEST_PGVECTOR_USERNAME", "postgres"));
        config.setPassword(System.getenv().getOrDefault("TEST_PGVECTOR_PASSWORD", ""));
        String schema = "retrieval_identity_test_" + UUID.randomUUID().toString().replace("-", "");
        Properties properties = new Properties();
        properties.setProperty("user", config.getUsername());
        properties.setProperty("password", config.getPassword());
        properties.setProperty("connectTimeout", "3");
        properties.setProperty("socketTimeout", "5");
        try (Connection administration = DriverManager.getConnection(config.getUrl(), properties);
             Statement ddl = administration.createStatement()) {
            ddl.setQueryTimeout(5);
            ddl.execute("CREATE EXTENSION IF NOT EXISTS vector WITH SCHEMA public");
            ddl.execute("CREATE SCHEMA " + schema);
            try {
                config.setUrl(config.getUrl() + (config.getUrl().contains("?") ? "&" : "?")
                        + "currentSchema=" + schema + ",public");
                PgVectorEmbeddingRepository instanceA = new PgVectorEmbeddingRepository(config);
                PgVectorEmbeddingRepository instanceB = new PgVectorEmbeddingRepository(config);
                IndexedChunk original = chunk(1, new float[] {1, 0});
                List<IndexedChunk> snapshotA = List.of(original);
                String identityA = EmbeddingSnapshotIdentity.of(snapshotA);
                instanceA.replaceAll(snapshotA);
                assertThat(instanceA.vectorSearch(new float[] {1, 0}, 5, 0, identityA)).hasSize(1);

                for (List<IndexedChunk> snapshotB : List.of(
                        List.of(new IndexedChunk(original.chunk(), new float[] {0, 1}, "other-model", "v1")),
                        List.of(new IndexedChunk(original.chunk(), new float[] {0, 1}, "test-model", "v2")),
                        List.of(chunk(2, new float[] {1, 0})) )) {
                    instanceB.replaceAll(snapshotB);
                    String identityB = EmbeddingSnapshotIdentity.of(snapshotB);
                    assertThat(identityB).isNotEqualTo(identityA);
                    assertThatThrownBy(() -> instanceA.vectorSearch(new float[] {1, 0}, 5, 0, identityA))
                            .isInstanceOf(EmbeddingSnapshotMismatchException.class);
                    assertThat(instanceB.vectorSearch(new float[] {1, 0}, 5, 0, identityB))
                            .hasSize(1).first().extracting(hit -> hit.indexedChunk().chunk().id())
                            .isEqualTo(snapshotB.getFirst().chunk().id());
                    instanceA.replaceAll(snapshotA);
                    assertThat(instanceA.vectorSearch(new float[] {1, 0}, 5, 0, identityA)).hasSize(1);
                    assertThatThrownBy(() -> instanceB.vectorSearch(new float[] {1, 0}, 5, 0, identityB))
                            .isInstanceOf(EmbeddingSnapshotMismatchException.class);
                }
            } finally {
                ddl.execute("DROP SCHEMA " + schema + " CASCADE");
            }
        }
    }

    private static RetrievalProperties.Postgres config() {
        RetrievalProperties.Postgres config = new RetrievalProperties.Postgres();
        config.setRetryBackoff(Duration.ZERO);
        return config;
    }

    private static IndexedChunk chunk(int id, float[] vector) {
        DocumentChunk chunk = new DocumentChunk(new UUID(0, id).toString(), "test/source.md", "Test source",
                "line:1", "content " + id, "sha256", 0);
        return new IndexedChunk(chunk, vector, "test-model", "v1");
    }

    private static JdbcFixture jdbc() throws SQLException {
        Connection connection = mock(Connection.class);
        PreparedStatement statement = mock(PreparedStatement.class);
        Statement ddl = mock(Statement.class);
        ResultSet rows = mock(ResultSet.class);
        Array ids = mock(Array.class);
        when(connection.prepareStatement(anyString())).thenReturn(statement);
        when(connection.createStatement()).thenReturn(ddl);
        when(connection.createArrayOf(eq("uuid"), any(Object[].class))).thenReturn(ids);
        when(statement.executeQuery()).thenReturn(rows);
        return new JdbcFixture(connection, statement, ddl, rows, ids);
    }

    private record JdbcFixture(Connection connection, PreparedStatement statement, Statement ddl, ResultSet rows, Array ids) {}
}
