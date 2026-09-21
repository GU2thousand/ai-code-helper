package com.aicodehelper.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

@ConfigurationProperties(prefix = "app")
public class AppProperties {

    private final Storage storage = new Storage();

    public Storage getStorage() { return storage; }

    public static class Storage {
        private boolean enabled;
        private String directory = "./data";
        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean enabled) { this.enabled = enabled; }
        public String getDirectory() { return directory; }
        public void setDirectory(String directory) { this.directory = directory; }
    }

    private final Ai ai = new Ai();
    private final Security security = new Security();
    private final Cors cors = new Cors();
    private final Rag rag = new Rag();
    private final Mcp mcp = new Mcp();
    private final Tools tools = new Tools();

    public Ai getAi() {
        return ai;
    }

    public Security getSecurity() {
        return security;
    }

    public Cors getCors() {
        return cors;
    }

    public Rag getRag() {
        return rag;
    }

    public Mcp getMcp() {
        return mcp;
    }

    public Tools getTools() {
        return tools;
    }

    public static class Ai {
        private final Dashscope dashscope = new Dashscope();
        private int maxMemoryMessages = 20;
        private int maxConversations = 2_000;
        private int maxInputCharacters = 4_000;
        private Duration streamTimeout = Duration.ofMinutes(2);
        private Duration streamTicketTtl = Duration.ofSeconds(30);
        private int maxStreamTickets = 2_000;
        private int maxPendingStreamTicketsPerOwner = 8;
        private int maxConcurrentAiRequests = 64;
        private int maxConcurrentAiRequestsPerOwner = 2;
        private int maxAiStartsPerMinute = 300;
        private int maxAiStartsPerMinutePerOwner = 20;

        public Dashscope getDashscope() {
            return dashscope;
        }

        public int getMaxMemoryMessages() {
            return maxMemoryMessages;
        }

        public void setMaxMemoryMessages(int maxMemoryMessages) {
            this.maxMemoryMessages = maxMemoryMessages;
        }

        public int getMaxConversations() {
            return maxConversations;
        }

        public void setMaxConversations(int maxConversations) {
            this.maxConversations = maxConversations;
        }

        public int getMaxInputCharacters() {
            return maxInputCharacters;
        }

        public void setMaxInputCharacters(int maxInputCharacters) {
            this.maxInputCharacters = maxInputCharacters;
        }

        public Duration getStreamTimeout() {
            return streamTimeout;
        }

        public void setStreamTimeout(Duration streamTimeout) {
            this.streamTimeout = streamTimeout;
        }

        public Duration getStreamTicketTtl() {
            return streamTicketTtl;
        }

        public void setStreamTicketTtl(Duration streamTicketTtl) {
            this.streamTicketTtl = streamTicketTtl;
        }

        public int getMaxStreamTickets() {
            return maxStreamTickets;
        }

        public void setMaxStreamTickets(int maxStreamTickets) {
            this.maxStreamTickets = maxStreamTickets;
        }

        public int getMaxPendingStreamTicketsPerOwner() {
            return maxPendingStreamTicketsPerOwner;
        }

        public void setMaxPendingStreamTicketsPerOwner(int maxPendingStreamTicketsPerOwner) {
            this.maxPendingStreamTicketsPerOwner = maxPendingStreamTicketsPerOwner;
        }

        public int getMaxConcurrentAiRequests() {
            return maxConcurrentAiRequests;
        }

        public void setMaxConcurrentAiRequests(int maxConcurrentAiRequests) {
            this.maxConcurrentAiRequests = maxConcurrentAiRequests;
        }

        public int getMaxConcurrentAiRequestsPerOwner() {
            return maxConcurrentAiRequestsPerOwner;
        }

        public void setMaxConcurrentAiRequestsPerOwner(int maxConcurrentAiRequestsPerOwner) {
            this.maxConcurrentAiRequestsPerOwner = maxConcurrentAiRequestsPerOwner;
        }

        public int getMaxAiStartsPerMinute() {
            return maxAiStartsPerMinute;
        }

        public void setMaxAiStartsPerMinute(int maxAiStartsPerMinute) {
            this.maxAiStartsPerMinute = maxAiStartsPerMinute;
        }

        public int getMaxAiStartsPerMinutePerOwner() {
            return maxAiStartsPerMinutePerOwner;
        }

        public void setMaxAiStartsPerMinutePerOwner(int maxAiStartsPerMinutePerOwner) {
            this.maxAiStartsPerMinutePerOwner = maxAiStartsPerMinutePerOwner;
        }
    }

    public static class Dashscope {
        private String apiKey = "";
        private String streamingApiKey = "";
        private String embeddingApiKey = "";
        private String baseUrl = "";
        private String streamingBaseUrl = "";
        private String chatModel = "qwen-max";
        private String streamingChatModel = "";
        private String embeddingModel = "text-embedding-v4";
        private Float temperature = 0.3f;
        private Integer maxTokens = 2_048;

        public String getApiKey() {
            return apiKey;
        }

        public void setApiKey(String apiKey) {
            this.apiKey = apiKey;
        }

        public String getStreamingApiKey() {
            return streamingApiKey;
        }

        public void setStreamingApiKey(String streamingApiKey) {
            this.streamingApiKey = streamingApiKey;
        }

        public String getEmbeddingApiKey() {
            return embeddingApiKey;
        }

        public void setEmbeddingApiKey(String embeddingApiKey) {
            this.embeddingApiKey = embeddingApiKey;
        }

        public String getBaseUrl() {
            return baseUrl;
        }

        public void setBaseUrl(String baseUrl) {
            this.baseUrl = baseUrl;
        }

        public String getStreamingBaseUrl() {
            return streamingBaseUrl;
        }

        public void setStreamingBaseUrl(String streamingBaseUrl) {
            this.streamingBaseUrl = streamingBaseUrl;
        }

        public String getChatModel() {
            return chatModel;
        }

        public void setChatModel(String chatModel) {
            this.chatModel = chatModel;
        }

        public String getStreamingChatModel() {
            return streamingChatModel;
        }

        public void setStreamingChatModel(String streamingChatModel) {
            this.streamingChatModel = streamingChatModel;
        }

        public String getEmbeddingModel() {
            return embeddingModel;
        }

        public void setEmbeddingModel(String embeddingModel) {
            this.embeddingModel = embeddingModel;
        }

        public Float getTemperature() {
            return temperature;
        }

        public void setTemperature(Float temperature) {
            this.temperature = temperature;
        }

        public Integer getMaxTokens() {
            return maxTokens;
        }

        public void setMaxTokens(Integer maxTokens) {
            this.maxTokens = maxTokens;
        }
    }

    public static class Security {
        private String tokenSecret = "";
        private Duration guestTtl = Duration.ofHours(12);
        private boolean secureCookies;
        private String sameSite = "Lax";
        private boolean exposeTokenInResponse;

        public String getTokenSecret() {
            return tokenSecret;
        }

        public void setTokenSecret(String tokenSecret) {
            this.tokenSecret = tokenSecret;
        }

        public Duration getGuestTtl() {
            return guestTtl;
        }

        public void setGuestTtl(Duration guestTtl) {
            this.guestTtl = guestTtl;
        }

        public boolean isSecureCookies() {
            return secureCookies;
        }

        public void setSecureCookies(boolean secureCookies) {
            this.secureCookies = secureCookies;
        }

        public String getSameSite() {
            return sameSite;
        }

        public void setSameSite(String sameSite) {
            this.sameSite = sameSite;
        }

        public boolean isExposeTokenInResponse() {
            return exposeTokenInResponse;
        }

        public void setExposeTokenInResponse(boolean exposeTokenInResponse) {
            this.exposeTokenInResponse = exposeTokenInResponse;
        }
    }

    public static class Cors {
        private List<String> allowedOrigins = new ArrayList<>(List.of(
                "http://localhost:5173",
                "http://127.0.0.1:5173", "http://localhost:4173", "http://127.0.0.1:4173"
        ));

        public List<String> getAllowedOrigins() {
            return allowedOrigins;
        }

        public void setAllowedOrigins(List<String> allowedOrigins) {
            this.allowedOrigins = allowedOrigins;
        }
    }

    public static class Rag {
        private String location = "classpath*:knowledge-base/*.md";
        private int maxResults = 4;
        private double minScore = 0.05;

        public String getLocation() {
            return location;
        }

        public void setLocation(String location) {
            this.location = location;
        }

        public int getMaxResults() {
            return maxResults;
        }

        public void setMaxResults(int maxResults) {
            this.maxResults = maxResults;
        }

        public double getMinScore() {
            return minScore;
        }

        public void setMinScore(double minScore) {
            this.minScore = minScore;
        }
    }

    public static class Mcp {
        private boolean enabled;
        private String apiKey = "";
        private String endpoint = "https://open.bigmodel.cn/api/mcp/web_search_prime/mcp";
        private List<String> allowedToolNames = new ArrayList<>(List.of("webSearchPrime", "web_search_prime"));
        private Duration timeout = Duration.ofSeconds(8);
        private Duration retryBackoff = Duration.ofSeconds(30);

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public String getApiKey() {
            return apiKey;
        }

        public void setApiKey(String apiKey) {
            this.apiKey = apiKey;
        }

        public String getEndpoint() {
            return endpoint;
        }

        public void setEndpoint(String endpoint) {
            this.endpoint = endpoint;
        }

        public List<String> getAllowedToolNames() {
            return allowedToolNames;
        }

        public void setAllowedToolNames(List<String> allowedToolNames) {
            this.allowedToolNames = allowedToolNames;
        }

        public Duration getTimeout() {
            return timeout;
        }

        public void setTimeout(Duration timeout) {
            this.timeout = timeout;
        }

        public Duration getRetryBackoff() {
            return retryBackoff;
        }

        public void setRetryBackoff(Duration retryBackoff) {
            this.retryBackoff = retryBackoff;
        }
    }

    public static class Tools {
        private String interviewSearchUrl = "https://www.nowcoder.com/search";
        private Duration connectTimeout = Duration.ofSeconds(4);

        public String getInterviewSearchUrl() {
            return interviewSearchUrl;
        }

        public void setInterviewSearchUrl(String interviewSearchUrl) {
            this.interviewSearchUrl = interviewSearchUrl;
        }

        public Duration getConnectTimeout() {
            return connectTimeout;
        }

        public void setConnectTimeout(Duration connectTimeout) {
            this.connectTimeout = connectTimeout;
        }
    }
}
