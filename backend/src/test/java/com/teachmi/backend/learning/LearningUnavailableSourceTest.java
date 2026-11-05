package com.teachmi.backend.learning;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(classes = LearningTestApplication.class)
class LearningUnavailableSourceTest {
    @Autowired WebApplicationContext context;
    @Autowired LearningSessionRepository sessions;
    @Autowired LearningMessageRepository messages;
    @Autowired org.springframework.jdbc.core.JdbcTemplate jdbc;

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

    @Test
    void missingDialogueGeneratorKeepsTheSubmittedAnswerForRetry() throws Exception {
        var session = new LearningSession("missing-dialogue", new CreateSessionRequest("m01", "c01", null, null),
                "{\"name\":\"개념\",\"scope\":\"범위\",\"sourceChunkIds\":[\"k01\"]}");
        session.selectMode("review", null);
        session = sessions.saveAndFlush(session);
        messages.saveAndFlush(new LearningMessage(session.id, "사례를 설명해 줄래?", "[\"k01\"]"));
        try {
            MockMvcBuilders.webAppContextSetup(context).build()
                    .perform(post("/api/v1/sessions/" + session.id + "/turns")
                            .principal(() -> "missing-dialogue").header("Idempotency-Key", "saved-input")
                            .contentType("application/json")
                            .content("{\"kind\":\"answer\",\"text\":\"내 설명\",\"expectedVersion\":0}"))
                    .andExpect(status().isServiceUnavailable());
            assertThat(messages.findBySessionIdOrderBySequenceAsc(session.id)).hasSize(2);
            assertThat(sessions.findById(session.id).orElseThrow().pendingTurnId).isNotNull();
        } finally {
            jdbc.update("DELETE FROM teachmi.learning_turn WHERE session_id=?", session.id);
            jdbc.update("DELETE FROM teachmi.learning_message WHERE session_id=?", session.id);
            sessions.deleteById(session.id);
        }
    }

    @Test
    void missingCProblemSourceCannotStartAFakeReteach() throws Exception {
        var session = new LearningSession("missing-c", new CreateSessionRequest("m01","c01",null,null),
                "{\"name\":\"개념\",\"scope\":\"범위\",\"sourceChunkIds\":[\"k01\"]}");
        session.stage = "problem";
        session = sessions.saveAndFlush(session);
        try {
            var result = MockMvcBuilders.webAppContextSetup(context).build()
                    .perform(post("/api/v1/sessions/" + session.id + "/reteach").principal(() -> "missing-c")
                            .header("Idempotency-Key","missing-c").contentType("application/json")
                            .content("{\"problemId\":\"p01\",\"attemptId\":\"a01\",\"expectedVersion\":0}")).andReturn();
            assertThat(result.getResponse().getStatus()).isEqualTo(503);
            assertThat(result.getResponse().getContentAsString()).contains("LEARNING_PROBLEM_INTEGRATION_PENDING");
            assertThat(sessions.findById(session.id).orElseThrow().stage).isEqualTo("problem");
            assertThat(sessions.findById(session.id).orElseThrow().activeReteachId).isNull();
        } finally { sessions.deleteById(session.id); }
    }

    @Test
    void savedEvidenceCanBeReadWithoutAnyAOrCOrGeminiAdapter() throws Exception {
        var session = sessions.saveAndFlush(new LearningSession("saved-evidence",
                new CreateSessionRequest("m01", "c01", null, null),
                "{\"name\":\"저장된 개념\",\"scope\":\"저장된 범위\",\"sourceChunkIds\":[\"k01\"]}"));
        try {
            var result = MockMvcBuilders.webAppContextSetup(context).build()
                    .perform(get("/api/v1/sessions/" + session.id + "/evidence").principal(() -> "saved-evidence"))
                    .andReturn();
            assertThat(result.getResponse().getStatus()).isEqualTo(200);
            assertThat(result.getResponse().getContentAsString()).contains("not_started");
            assertThat(sessions.findById(session.id).orElseThrow().version).isZero();
        } finally { sessions.deleteById(session.id); }
    }
}
