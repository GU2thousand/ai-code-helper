package com.aicodehelper.retrieval;

import org.springframework.boot.context.properties.ConfigurationProperties;
import java.time.Duration;

@ConfigurationProperties(prefix = "app.retrieval")
public class RetrievalProperties {
    private String mode = "lexical";
    private String backend = "memory";
    private String embeddingVersion = "v1";
    private Duration embeddingRetryBackoff = Duration.ofSeconds(30);
    private int candidates = 25;
    private int rrfK = 60;
    private final Postgres postgres = new Postgres();
    private final Reranker reranker = new Reranker();
    public String getMode() { return mode; }
    public void setMode(String mode) { this.mode = mode; }
    public String getBackend() { return backend; }
    public void setBackend(String backend) { this.backend = backend; }
    public String getEmbeddingVersion() { return embeddingVersion; }
    public void setEmbeddingVersion(String value) { this.embeddingVersion = value; }
    public Duration getEmbeddingRetryBackoff() { return embeddingRetryBackoff; }
    public void setEmbeddingRetryBackoff(Duration value) { this.embeddingRetryBackoff = value; }
    public int getCandidates() { return candidates; }
    public void setCandidates(int value) { this.candidates = value; }
    public int getRrfK() { return rrfK; }
    public void setRrfK(int value) { this.rrfK = value; }
    public Postgres getPostgres() { return postgres; }
    public Reranker getReranker() { return reranker; }
    public static class Postgres {
        private String url = "jdbc:postgresql://localhost:5432/aicodehelper";
        private String username = "aicodehelper";
        private String password = "";
        private int connectTimeoutSeconds = 3;
        private int queryTimeoutSeconds = 5;
        private Duration retryBackoff = Duration.ofSeconds(30);
        public String getUrl() { return url; }
        public void setUrl(String value) { this.url = value; }
        public String getUsername() { return username; }
        public void setUsername(String value) { this.username = value; }
        public String getPassword() { return password; }
        public void setPassword(String value) { this.password = value; }
        public int getConnectTimeoutSeconds() { return connectTimeoutSeconds; }
        public void setConnectTimeoutSeconds(int value) { this.connectTimeoutSeconds = value; }
        public int getQueryTimeoutSeconds() { return queryTimeoutSeconds; }
        public void setQueryTimeoutSeconds(int value) { this.queryTimeoutSeconds = value; }
        public Duration getRetryBackoff() { return retryBackoff; }
        public void setRetryBackoff(Duration value) { this.retryBackoff = value; }
    }
    public static class Reranker {
        private String type = "deterministic-token-overlap";
        private String url = "";
        private String apiKey = "";
        private Duration timeout = Duration.ofSeconds(2);
        public String getType() { return type; }
        public void setType(String value) { this.type = value; }
        public String getUrl() { return url; }
        public void setUrl(String value) { this.url = value; }
        public String getApiKey() { return apiKey; }
        public void setApiKey(String value) { this.apiKey = value; }
        public Duration getTimeout() { return timeout; }
        public void setTimeout(Duration value) { this.timeout = value; }
    }
}
