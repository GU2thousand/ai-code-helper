package com.aicodehelper.retrieval;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import java.net.InetSocketAddress;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class HttpCrossEncoderRerankerTest {
    @Test void appliesFiniteScoresAndRejectsMalformedResponse() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/rank", exchange -> {
            exchange.getRequestBody().readAllBytes();
            byte[] body = "{\"scores\":[0.1,0.9]}".getBytes();
            exchange.sendResponseHeaders(200, body.length); exchange.getResponseBody().write(body); exchange.close();
        });
        server.createContext("/bad", exchange -> {
            byte[] body = "{\"scores\":[\"pretend\"]}".getBytes();
            exchange.sendResponseHeaders(200, body.length); exchange.getResponseBody().write(body); exchange.close();
        });
        server.start();
        try {
            var config = config(server, "/rank", Duration.ofSeconds(2));
            var ranked = new HttpCrossEncoderReranker(config).rerank("query", hits());
            assertThat(ranked).extracting(RetrievalHit::chunkId).containsExactly("b", "a");
            assertThat(ranked).extracting(RetrievalHit::finalRank).containsExactly(1, 2);
            assertThat(ranked).extracting(RetrievalHit::fusedRank).containsExactly(2, 1);
            assertThatThrownBy(() -> new HttpCrossEncoderReranker(config(server, "/bad", Duration.ofSeconds(2))).rerank("query", hits()))
                    .isInstanceOf(IllegalStateException.class);
        } finally { server.stop(0); }
    }

    @Test void stalledResponseBodyTimesOutAndCancelsRequest() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        var executor = Executors.newVirtualThreadPerTaskExecutor(); server.setExecutor(executor);
        CountDownLatch headersSent = new CountDownLatch(1), release = new CountDownLatch(1);
        server.createContext("/stall", exchange -> {
            exchange.getRequestBody().readAllBytes(); exchange.sendResponseHeaders(200, 100);
            exchange.getResponseBody().write('{'); exchange.getResponseBody().flush(); headersSent.countDown();
            try { release.await(5, TimeUnit.SECONDS); } catch (InterruptedException error) { Thread.currentThread().interrupt(); }
            exchange.close();
        });
        server.start();
        try {
            var reranker = new HttpCrossEncoderReranker(config(server, "/stall", Duration.ofMillis(400)));
            long start = System.nanoTime();
            assertThatThrownBy(() -> reranker.rerank("query", hits())).isInstanceOf(java.net.http.HttpTimeoutException.class);
            assertThat(headersSent.getCount()).isZero();
            assertThat(Duration.ofNanos(System.nanoTime() - start)).isLessThan(Duration.ofSeconds(2));
        } finally { release.countDown(); server.stop(0); executor.shutdownNow(); }
    }

    private RetrievalProperties.Reranker config(HttpServer server, String path, Duration timeout) {
        var config = new RetrievalProperties.Reranker();
        config.setUrl("http://127.0.0.1:" + server.getAddress().getPort() + path); config.setTimeout(timeout); return config;
    }
    private List<RetrievalHit> hits() {
        return List.of(hit("a", 1), hit("b", 2));
    }
    private RetrievalHit hit(String id, int rank) {
        return new RetrievalHit(id, "source", "title", "location", "text", "hash", 1.0, 0.8, 0.03, null, rank, rank, rank, rank);
    }
}
