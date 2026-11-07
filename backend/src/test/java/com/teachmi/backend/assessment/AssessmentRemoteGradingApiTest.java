package com.teachmi.backend.assessment;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import com.teachmi.backend.auth.AuthFilter;
import com.teachmi.backend.learning.LearningException;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.context.WebApplicationContext;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/** 원격 채점기가 있을 때 저장 → 트랜잭션 밖 채점 → 판정 저장으로 나뉘는지 확인한다. 문항은 임시 문항을 쓴다. */
@SpringBootTest(properties = "teachmi.assessment.demo-enabled=true")
class AssessmentRemoteGradingApiTest {
    /** 테스트마다 채점 동작을 바꿀 수 있는 가짜 원격 채점기. */
    static final AtomicReference<Function<RemoteAnswerGrader.Input, AnswerGrader.Grade>> BEHAVIOR = new AtomicReference<>();
    static final AtomicReference<Boolean> INSIDE_TRANSACTION = new AtomicReference<>();

    @TestConfiguration(proxyBeanMethods = false)
    static class FakeRemote {
        @Bean
        RemoteAnswerGrader fakeRemoteGrader() {
            return input -> {
                INSIDE_TRANSACTION.set(TransactionSynchronizationManager.isActualTransactionActive());
                return BEHAVIOR.get().apply(input);
            };
        }
    }

    record Login(String id, Cookie cookie, String csrf) {}

    @Autowired WebApplicationContext context;
    @Autowired AuthFilter filter;
    @Autowired JdbcTemplate jdbc;
    @Autowired ObjectMapper json;
    MockMvc mvc;
    Login owner;
    UUID session, first;

    @BeforeEach
    void setup() throws Exception {
        BEHAVIOR.set(input -> new AnswerGrader.Grade("correct", null));
        INSIDE_TRANSACTION.set(null);
        mvc = MockMvcBuilders.webAppContextSetup(context).addFilters(filter).build();
        owner = login();
        session = AssessmentFixtures.ready(jdbc, owner.id());
        var result = mvc.perform(post("/api/v1/sessions/" + session + "/problems").cookie(owner.cookie())
                .header("X-CSRF-Token", owner.csrf())).andReturn().getResponse();
        assertThat(result.getStatus()).isEqualTo(200);
        first = UUID.fromString(body(result).path("problems").get(0).path("problemId").asText());
    }

    @Test
    void theRemoteGraderRunsOutsideTheTransactionAndItsVerdictIsSaved() throws Exception {
        BEHAVIOR.set(input -> new AnswerGrader.Grade("wrong", "배분 이유를 다시 생각해 보세요."));
        var response = submit("k1", "아무 답");

        assertThat(response.getStatus()).isEqualTo(201);
        assertThat(body(response).path("judgement").asText()).isEqualTo("wrong");
        assertThat(INSIDE_TRANSACTION.get()).as("AI 채점은 DB 트랜잭션 밖에서 한다").isFalse();
        assertThat(jdbc.queryForObject("SELECT reteach_focus FROM teachmi.assessment_attempt WHERE problem_id=?",
                String.class, first)).isEqualTo("배분 이유를 다시 생각해 보세요.");
    }

    @Test
    void aFailedGradingKeepsTheAnswerAndTheSameKeyGradesItAgain() throws Exception {
        BEHAVIOR.set(input -> { throw new LearningException(503, "AI_GENERATION_UNCERTAIN", "다시 시도"); });
        var failed = submit("k1", "200만원");
        assertThat(failed.getStatus()).isEqualTo(503);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM teachmi.assessment_attempt WHERE problem_id=? AND judgement IS NULL",
                Long.class, first)).as("답안은 판정 없이 남는다").isEqualTo(1);

        code(submit("k2", "200만원"), 409, "FIRST_ATTEMPT_ALREADY_SUBMITTED");

        BEHAVIOR.set(input -> new AnswerGrader.Grade("correct", null));
        var retried = submit("k1", "200만원");
        assertThat(retried.getStatus()).isEqualTo(201);
        assertThat(body(retried).path("judgement").asText()).isEqualTo("correct");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM teachmi.assessment_attempt WHERE problem_id=?",
                Long.class, first)).as("같은 키는 시도를 늘리지 않는다").isEqualTo(1);
    }

    @Test
    void aChangeDuringGradingIsNotSaved() throws Exception {
        BEHAVIOR.set(input -> {
            jdbc.update("UPDATE teachmi.learning_session SET version=version+1 WHERE id=?", session);
            return new AnswerGrader.Grade("correct", null);
        });
        code(submit("k1", "200만원"), 409, "ASSESSMENT_EVIDENCE_STALE");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM teachmi.assessment_attempt WHERE problem_id=? AND judgement IS NULL",
                Long.class, first)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM teachmi.assessment_problem WHERE id=? AND confirmed_at IS NULL",
                Long.class, first)).as("문항도 확정되지 않는다").isEqualTo(1);
    }

    @Test
    void anAlreadyGradedAnswerIsReturnedWithoutGradingAgain() throws Exception {
        submit("k1", "200만원");
        BEHAVIOR.set(input -> { throw new IllegalStateException("다시 채점하면 안 된다"); });
        var replay = submit("k1", "200만원");
        assertThat(replay.getStatus()).isEqualTo(201);
        assertThat(body(replay).path("judgement").asText()).isEqualTo("correct");
    }

    @Test
    void anAbandonedPendingRetryIsRejectedWithoutCallingTheGrader() throws Exception {
        failFirstGrading();
        jdbc.update("UPDATE teachmi.learning_session SET status='abandoned',version=version+1 WHERE id=?",session);
        assertPendingRetryDoesNotCallGrader(null);
    }

    @Test
    void anUnreadyPendingRetryIsRejectedWithoutCallingTheGrader() throws Exception {
        failFirstGrading();
        jdbc.update("UPDATE teachmi.learning_session SET stage='chat',version=version+1 WHERE id=?",session);
        assertPendingRetryDoesNotCallGrader(null);
    }

    @Test
    void aStalePendingRetryIsRejectedWithoutCallingTheGrader() throws Exception {
        failFirstGrading();
        jdbc.update("UPDATE teachmi.learning_session SET version=version+1 WHERE id=?",session);
        assertPendingRetryDoesNotCallGrader("ASSESSMENT_EVIDENCE_STALE");
    }

    @Test
    void aHistoricalGradedReplayRemainsAvailableAfterSessionReplacement() throws Exception {
        var saved=submit("k1","200만원");
        assertThat(saved.getStatus()).isEqualTo(201);
        jdbc.update("UPDATE teachmi.learning_session SET status='abandoned',version=version+1 WHERE id=?",session);
        BEHAVIOR.set(input -> { throw new AssertionError("A historical replay must not call AI"); });
        var replay=submit("k1","200만원");
        assertThat(replay.getStatus()).isEqualTo(201);
        assertThat(body(replay)).isEqualTo(body(saved));
        code(submit("k1","changed answer"),409,"IDEMPOTENCY_KEY_REUSED");
    }

    @Test
    void concurrentSameKeyRequestsReturnTheSavedGradeEvenAfterSessionReplacement() throws Exception {
        var reached=new CountDownLatch(1);
        var release=new CountDownLatch(1);
        var calls=new AtomicInteger();
        BEHAVIOR.set(input -> {
            if (calls.incrementAndGet()==1) {
                reached.countDown();
                try {
                    if (!release.await(10,TimeUnit.SECONDS)) throw new AssertionError("Grading was not released");
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw new AssertionError(interrupted);
                }
            }
            return new AnswerGrader.Grade("correct",null);
        });
        try (var executor=Executors.newVirtualThreadPerTaskExecutor()) {
            var waiting=executor.submit(() -> submit("concurrent","200만원"));
            try {
                assertThat(reached.await(10,TimeUnit.SECONDS)).isTrue();
                var saved=submit("concurrent","200만원");
                assertThat(saved.getStatus()).isEqualTo(201);
                jdbc.update("UPDATE teachmi.learning_session SET status='abandoned',version=version+1 WHERE id=?",session);
                release.countDown();
                var replay=waiting.get(10,TimeUnit.SECONDS);
                assertThat(replay.getStatus()).isEqualTo(201);
                assertThat(body(replay)).isEqualTo(body(saved));
                assertThat(jdbc.queryForObject("SELECT count(*) FROM teachmi.assessment_attempt WHERE problem_id=?",
                        Long.class,first)).isEqualTo(1);
            } finally {
                release.countDown();
            }
        }
    }

    private void failFirstGrading() throws Exception {
        BEHAVIOR.set(input -> { throw new LearningException(502,"INVALID_AI_RESPONSE","invalid response"); });
        assertThat(submit("pending","200만원").getStatus()).isEqualTo(502);
    }

    private void assertPendingRetryDoesNotCallGrader(String expectedCode) throws Exception {
        var calls=new AtomicInteger();
        BEHAVIOR.set(input -> { calls.incrementAndGet(); return new AnswerGrader.Grade("correct",null); });
        var rejected=submit("pending","200만원");
        assertThat(rejected.getStatus()).isEqualTo(409);
        if (expectedCode != null) assertThat(body(rejected).path("code").asText()).isEqualTo(expectedCode);
        assertThat(calls.get()).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM teachmi.assessment_attempt WHERE problem_id=? AND judgement IS NULL",
                Long.class,first)).isEqualTo(1);
    }

    @Test
    void theGraderReceivesTheSavedProblemAndAnswer() throws Exception {
        var seen = new AtomicReference<RemoteAnswerGrader.Input>();
        BEHAVIOR.set(input -> { seen.set(input); return new AnswerGrader.Grade("correct", null); });
        submit("k1", "200만원");

        assertThat(seen.get().expectedAnswer()).isEqualTo("200만 원");
        assertThat(seen.get().answer()).isEqualTo("200만원");
        assertThat(seen.get().memberId()).isEqualTo(owner.id());
        assertThat(seen.get().session().sessionId()).isEqualTo(session);
    }

    private MockHttpServletResponse submit(String key, String answer) throws Exception {
        return mvc.perform(post("/api/v1/sessions/" + session + "/problems/" + first + "/attempts").cookie(owner.cookie())
                        .header("X-CSRF-Token", owner.csrf()).header("Idempotency-Key", key).contentType("application/json")
                        .content(json.writeValueAsString(Map.of("answer", answer, "work", "원가를 내용연수로 나눈다."))))
                .andReturn().getResponse();
    }

    private Login login() throws Exception {
        var credentials = json.writeValueAsString(Map.of("email", UUID.randomUUID() + "@example.test",
                "password", "correct-horse"));
        assertThat(mvc.perform(post("/api/v1/auth/signup").contentType("application/json").content(credentials))
                .andReturn().getResponse().getStatus()).isEqualTo(201);
        var response = mvc.perform(post("/api/v1/auth/login").contentType("application/json").content(credentials))
                .andReturn().getResponse();
        assertThat(response.getStatus()).isEqualTo(200);
        var data = body(response);
        return new Login(data.path("id").asText(), response.getCookie("TEACHMI_SESSION"), data.path("csrfToken").asText());
    }

    private void code(MockHttpServletResponse response, int status, String code) {
        assertThat(response.getStatus()).isEqualTo(status);
        assertThat(body(response).path("code").asText()).isEqualTo(code);
    }

    private JsonNode body(MockHttpServletResponse response) {
        return json.readTree(response.getContentAsByteArray());
    }
}
