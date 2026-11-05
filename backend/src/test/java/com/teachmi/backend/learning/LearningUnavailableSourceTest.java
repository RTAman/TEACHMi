package com.teachmi.backend.learning;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(classes = LearningTestApplication.class)
class LearningUnavailableSourceTest {
    @Autowired WebApplicationContext context;
    @Autowired LearningSessionRepository sessions;

    @Test
    void missingAAdapterRejectsCreationInsteadOfTrustingClientConcepts() throws Exception {
        MockMvcBuilders.webAppContextSetup(context).build()
                .perform(post("/api/v1/sessions").principal(() -> "adapter-check")
                        .header("Idempotency-Key", "no-source")
                        .contentType("application/json")
                        .content("{\"materialId\":\"m01\",\"conceptId\":\"c01\"}"))
                .andExpect(status().isServiceUnavailable());
    }

    @Test
    void missingGeneratorPreservesTheModeSelectionStage() throws Exception {
        var session = sessions.saveAndFlush(new LearningSession("generator-check",
                new CreateSessionRequest("m01", "c01", null, null),
                "{\"name\":\"개념\",\"scope\":\"범위\",\"sourceChunkIds\":[\"k01\"]}"));
        try {
            MockMvcBuilders.webAppContextSetup(context).build()
                    .perform(put("/api/v1/sessions/" + session.id + "/mode")
                            .principal(() -> "generator-check").header("Idempotency-Key", "missing-generator")
                            .contentType("application/json")
                            .content("{\"mode\":\"first\",\"expectedVersion\":0}"))
                    .andExpect(status().isServiceUnavailable());
            var unchanged = sessions.findById(session.id).orElseThrow();
            assertThat(unchanged.stage).isEqualTo("mode");
            assertThat(unchanged.version).isZero();
        } finally {
            sessions.deleteById(session.id);
        }
    }
}
