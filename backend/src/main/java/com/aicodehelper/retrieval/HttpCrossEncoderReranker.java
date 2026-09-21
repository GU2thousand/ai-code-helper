package com.aicodehelper.retrieval;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Flow;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/** Adapter protocol: POST {query, documents:[text]} -> {scores:[number]}, in original input order. */
public final class HttpCrossEncoderReranker implements Reranker {
    private final RetrievalProperties.Reranker config;
    private final HttpClient client;
    private final ObjectMapper mapper = new ObjectMapper();
    public HttpCrossEncoderReranker(RetrievalProperties.Reranker config) {
        this.config = config;
        URI uri = URI.create(config.getUrl());
        if (!List.of("http", "https").contains(uri.getScheme()) || uri.getHost() == null || uri.getUserInfo() != null)
            throw new IllegalArgumentException("Reranker URL must be an HTTP(S) service URL without embedded credentials");
        client = HttpClient.newBuilder().connectTimeout(config.getTimeout()).followRedirects(HttpClient.Redirect.NEVER).build();
    }
    @Override public List<RetrievalHit> rerank(String query, List<RetrievalHit> candidates) throws Exception {
        String body = mapper.writeValueAsString(Map.of("query", query, "documents", candidates.stream().map(hit -> hit.title() + "\n" + hit.text()).toList()));
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(config.getUrl())).timeout(config.getTimeout())
                .header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString(body));
        if (!config.getApiKey().isBlank()) builder.header("Authorization", "Bearer " + config.getApiKey());
        var pending = client.sendAsync(builder.build(), ignored -> new BoundedBodySubscriber());
        HttpResponse<byte[]> response;
        try { response = pending.get(config.getTimeout().toMillis(), TimeUnit.MILLISECONDS); }
        catch (TimeoutException failure) { pending.cancel(true); throw new HttpTimeoutException("Reranker response timed out"); }
        catch (InterruptedException failure) { pending.cancel(true); Thread.currentThread().interrupt(); throw failure; }
        byte[] bytes = response.body();
        if (response.statusCode() != 200) throw new IllegalStateException("Invalid reranker response");
        var scores = mapper.readTree(new String(bytes, StandardCharsets.UTF_8)).get("scores");
        if (scores == null || !scores.isArray() || scores.size() != candidates.size()) throw new IllegalStateException("Reranker score count mismatch");
        List<RetrievalHit> scored = new ArrayList<>();
        for (int i = 0; i < candidates.size(); i++) {
            if (!scores.get(i).isNumber() || !Double.isFinite(scores.get(i).doubleValue())) throw new IllegalStateException("Invalid reranker score");
            scored.add(HybridRetriever.rank(candidates.get(i), scores.get(i).doubleValue(), i + 1));
        }
        scored.sort(Comparator.comparingDouble((RetrievalHit hit) -> hit.rerankScore()).reversed()
                .thenComparing(RetrievalHit::fusedRank).thenComparing(RetrievalHit::chunkId));
        List<RetrievalHit> result = new ArrayList<>();
        for (int i = 0; i < scored.size(); i++) result.add(HybridRetriever.rank(scored.get(i), scored.get(i).rerankScore(), i + 1));
        return List.copyOf(result);
    }
    @Override public String name() { return "http-cross-encoder"; }

    /** Cancels oversized bodies before allocation and lets the caller cancel stalled bodies at its deadline. */
    private static final class BoundedBodySubscriber implements HttpResponse.BodySubscriber<byte[]> {
        private final CompletableFuture<byte[]> result = new CompletableFuture<>();
        private final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        private Flow.Subscription subscription;
        @Override public CompletionStage<byte[]> getBody() { return result; }
        @Override public void onSubscribe(Flow.Subscription subscription) { this.subscription = subscription; subscription.request(Long.MAX_VALUE); }
        @Override public void onNext(List<ByteBuffer> items) {
            for (ByteBuffer item : items) {
                if (bytes.size() + item.remaining() > 65_536) {
                    subscription.cancel(); result.completeExceptionally(new IllegalStateException("Reranker response too large")); return;
                }
                byte[] part = new byte[item.remaining()]; item.get(part); bytes.writeBytes(part);
            }
        }
        @Override public void onError(Throwable error) { result.completeExceptionally(error); }
        @Override public void onComplete() { result.complete(bytes.toByteArray()); }
    }
}
