package com.aicodehelper.evaluation;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties = {"app.evaluation.enabled=true", "app.evaluation.key=integration-only",
        "app.storage.enabled=false", "app.mcp.enabled=false"})
@AutoConfigureMockMvc
class EvaluationIntegrationTest {
    @Autowired MockMvc mvc;

    @Test
    void operatorKeyIsRequiredAndNeverEchoed() throws Exception {
        mvc.perform(get("/api/evaluation/status")).andExpect(status().isForbidden());
        mvc.perform(get("/api/evaluation/status").header("X-Evaluation-Key", "integration-only"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.agentEvaluationMode").value("controlled_runtime"))
                .andExpect(content().string(not(containsString("integration-only"))));
    }

    @Test
    void topFiveHasStableChunkEvidenceAndDoesNotCallAnAnswerModel() throws Exception {
        mvc.perform(post("/api/evaluation/retrieval").header("X-Evaluation-Key", "integration-only")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"question":"Java Spring Boot concurrency","mode":"vector","k":5}
                                """))
                .andExpect(status().isOk()).andExpect(jsonPath("$.results", hasSize(5)))
                .andExpect(jsonPath("$.results[0].chunkId").isString())
                .andExpect(jsonPath("$.results[0].sourceHash").isString())
                .andExpect(jsonPath("$.corpusHash").isString())
                .andExpect(jsonPath("$.durationMs").isNumber())
                .andExpect(jsonPath("$.embeddingProvider").value("local"))
                .andExpect(jsonPath("$.answer").doesNotExist());
    }

    @Test
    void controlledAgentFailuresAreTypedAndArbitraryExecutionIsRejected() throws Exception {
        mvc.perform(post("/api/evaluation/agent").header("X-Evaluation-Key", "integration-only")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"scenario\":\"unauthorized\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.mode").value("controlled_runtime"))
                .andExpect(jsonPath("$.underlyingCalls").value(0))
                .andExpect(jsonPath("$.results[0].error.code").value("UNAUTHORIZED_TOOL"));
        mvc.perform(post("/api/evaluation/agent").header("X-Evaluation-Key", "integration-only")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"scenario\":\"http://example.com\"}"))
                .andExpect(status().isBadRequest());
        mvc.perform(post("/api/evaluation/retrieval").header("X-Evaluation-Key", "integration-only")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"question\":\"Java\",\"mode\":\"vector\",\"k\":1000000}"))
                .andExpect(status().isBadRequest());
    }
}
