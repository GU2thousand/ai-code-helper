package com.aicodehelper.retrieval;

import com.aicodehelper.ai.local.LocalHashEmbeddingModel;
import com.aicodehelper.config.AppProperties;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import static org.assertj.core.api.Assertions.assertThat;

class RetrievalBilingualTest {
    @Test void lexicalAndRerankedHybridRetrieveCrossOriginCookieEvidenceInChinese() {
        var service = new RetrievalService(new LocalHashEmbeddingModel(), new PathMatchingResourcePatternResolver(),
                new AppProperties(), new RetrievalProperties());
        for (String mode : new String[]{"lexical", "hybrid_rerank"}) {
            var result = service.search("EventSource 跨域 Cookie 应如何配置？", mode, 5);
            assertThat(result.hits().getFirst().source()).isEqualTo("vue-sse.md");
            assertThat(result.hits().getFirst().text()).contains("withCredentials", "CORS");
        }
    }
}
