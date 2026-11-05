package com.teachmi.backend.learning;

import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.aopalliance.intercept.MethodInterceptor;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.aop.framework.Advised;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.context.WebApplicationContext;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;

@SpringBootTest(classes = LearningTestApplication.class)
@Import({LearningSessionApiTest.Sources.class, LearningModeTransitionTest.Generation.class,
        LearningTurnsApiTest.Dialogue.class, LearningReteachApiTest.Providers.class})
class LearningEvidenceApiTest {
    @Autowired WebApplicationContext context;
    @Autowired JdbcTemplate jdbc;
    @Autowired ObjectMapper json;
    @Autowired LearningSessionApiTest.FixtureConceptSource concepts;
    @Autowired LearningModeTransitionTest.FixtureGenerator preparation;
    @Autowired LearningTurnsApiTest.FixtureDialogue dialogue;
    @Autowired LearningReteachApiTest.Opening opening;
    @Autowired LearningReteachApiTest.Problems problems;
    @Autowired LearningEvidenceService evidenceService;
    @Autowired LearningSessionService sessionService;
    @Autowired PlatformTransactionManager transactionManager;
    @Autowired LearningSessionRepository repository;
    MockMvc mvc;
    UUID id;

    @BeforeEach void setup() throws Exception {
        jdbc.execute("TRUNCATE teachmi.learning_reteach, teachmi.learning_turn, teachmi.learning_support_event, "
                + "teachmi.learning_session_request, teachmi.learning_message, teachmi.learning_session");
        concepts.unavailable = false;
        preparation.fail = false; preparation.unknownEvidence = false;
        preparation.questions.set(0); preparation.summaries.set(0);
        dialogue.calls.set(0); dialogue.sufficient = true; dialogue.fail = false;
        dialogue.invalidHelp = false; dialogue.wrongEvidence = false; dialogue.entered = null; dialogue.release = null;
        opening.calls.set(0); opening.fail = false; opening.wrongEvidence = false; opening.entered = null; opening.release = null;
        problems.member = "member-1"; problems.problem = "p01"; problems.attempt = "a01";
        problems.judgement = "wrong"; problems.focus = "사용 기간에 비용을 배분하는 이유";
        mvc = MockMvcBuilders.webAppContextSetup(context).build();
        id = UUID.fromString(body(mvc.perform(post("/api/v1/sessions").principal(() -> "member-1")
                .header("Idempotency-Key", "create").contentType("application/json")
                .content("{\"materialId\":\"m01\",\"conceptId\":\"c01\"}")).andReturn()).path("sessionId").asText());
        problems.session = id;
    }

    @Test void notStartedEvidenceIsDeterministicAndReadOnly() throws Exception {
        var before = sessionService.get("member-1", id);
        long receipts = jdbc.queryForObject("SELECT count(*) FROM teachmi.learning_session_request", Long.class);
        var first = evidence();
        assertThat(first.path("schemaVersion").asInt()).isEqualTo(1);
        assertThat(first.path("sessionId").asText()).isEqualTo(id.toString());
        assertThat(first.path("originalLearning").path("state").asText()).isEqualTo("not_started");
        assertThat(original(first).path("initialAnswer").isNull()).isTrue();
        assertThat(original(first).path("finalExplanation").isNull()).isTrue();
        assertThat(original(first).path("support").path("delivered").path("total").asInt()).isZero();
        assertThat(first.path("reteachHistory").isEmpty()).isTrue();
        assertThat(first.path("pendingGeneration").isNull()).isTrue();
        assertThat(evidence()).isEqualTo(first);
        assertThat(sessionService.get("member-1", id)).isEqualTo(before);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM teachmi.learning_session_request", Long.class)).isEqualTo(receipts);
        assertThat(dialogue.calls.get() + opening.calls.get() + preparation.questions.get() + preparation.summaries.get()).isZero();
        assertThat(first.has("score")).isFalse();
    }

    @Test void firstModeSummaryDoesNotClaimAnExplanationWasCompleted() throws Exception {
        mode("first");
        var state = evidence();
        assertThat(state.path("stage").asText()).isEqualTo("summary");
        assertThat(state.path("originalLearning").path("state").asText()).isEqualTo("not_started");
        assertThat(original(state).path("finalExplanation").isNull()).isTrue();
    }

    @Test void completedEvidencePreservesTheActualFirstAndFinalAnswersAndDeliveredHelpByKind() throws Exception {
        mode("review");
        send("hint", "hint", null);
        dialogue.sufficient = false;
        send("first", "answer", "처음에는 잘 모르겠지만 사용 기간 때문이라고 생각해요.");
        var first = original(evidence()).path("initialAnswer");
        assertThat(first.path("text").asText()).isEqualTo("처음에는 잘 모르겠지만 사용 기간 때문이라고 생각해요.");
        assertThat(first.path("supportEventId").isNull()).isFalse();
        dialogue.sufficient = true;
        send("case", "answer", "기간에 비용을 나눕니다.");
        send("example", "example", null);
        send("unknown", "dont_know", null);
        send("concept", "answer", "뜻을 설명합니다.");
        send("reason", "answer", "이유를 설명합니다.");
        send("final", "answer", "최종 설명: 원가를 사용 기간에 배분해 기간별 비용을 대응합니다.");
        var result = evidence();
        assertThat(result.path("status").asText()).isEqualTo("active");
        assertThat(result.path("stage").asText()).isEqualTo("problem");
        assertThat(result.path("originalLearning").path("state").asText()).isEqualTo("completed");
        assertThat(original(result).path("initialAnswer")).isEqualTo(first);
        assertThat(original(result).path("finalExplanation").path("text").asText()).startsWith("최종 설명:");
        var support = original(result).path("support");
        assertThat(support.path("requested")).isEqualTo(support.path("delivered"));
        assertThat(support.path("delivered")).isEqualTo(json.readTree("{\"total\":3,\"hint\":1,\"example\":1,\"dontKnow\":1}"));
        assertThat(support.path("events").size()).isEqualTo(3);
        var session = sessionService.get("member-1", id);
        assertThat(original(result).path("finalExplanation").path("messageId").asText()).isEqualTo(session.finalExplanationId().toString());
        assertThat(result.path("version").asLong()).isEqualTo(session.version());
        assertThat(result.path("concept")).isEqualTo(json.readTree(json.writeValueAsString(session.concept())));
    }

    @Test void failedHelpIsRequestedButNotDeliveredAndASuccessfulRetryCountsOnce() throws Exception {
        mode("review");
        long version = version();
        dialogue.fail = true;
        assertThat(turn("retry", "hint", null, version).getResponse().getStatus()).isEqualTo(503);
        var failed = evidence();
        var help = original(failed).path("support");
        assertThat(help.path("requested").path("hint").asInt()).isEqualTo(1);
        assertThat(help.path("delivered").path("total").asInt()).isZero();
        assertThat(failed.path("pendingGeneration").path("operation").asText()).isEqualTo("turn");
        assertThat(failed.path("pendingGeneration").path("state").asText()).isEqualTo("failed");
        assertThat(failed.toString()).doesNotContain("idempotencyKey", "expectedVersion");
        int calls = dialogue.calls.get();
        assertThat(evidence()).isEqualTo(failed);
        assertThat(dialogue.calls.get()).isEqualTo(calls);
        dialogue.fail = false;
        assertThat(turn("retry", "hint", null, version).getResponse().getStatus()).isEqualTo(200);
        assertThat(turn("retry", "hint", null, version).getResponse().getStatus()).isEqualTo(200);
        var retried = evidence();
        var delivered = original(retried).path("support");
        assertThat(delivered.path("requested").path("total").asInt()).isEqualTo(1);
        assertThat(delivered.path("delivered").path("hint").asInt()).isEqualTo(1);
        assertThat(delivered.path("events").get(0).path("eventId")).isEqualTo(help.path("events").get(0).path("eventId"));
        assertThat(retried.path("pendingGeneration").isNull()).isTrue();
    }

    @Test void aSavedFinalInputIsNotAFinalExplanationUntilItsTurnSucceeds() throws Exception {
        mode("review");
        for (int i = 0; i < 3; i++) send("step-" + i, "answer", "설명");
        long version = version();
        dialogue.fail = true;
        assertThat(turn("final", "answer", "완료 전 설명", version).getResponse().getStatus()).isEqualTo(503);
        var pending = evidence();
        assertThat(pending.path("originalLearning").path("state").asText()).isEqualTo("in_progress");
        assertThat(original(pending).path("finalExplanation").isNull()).isTrue();
        dialogue.fail = false;
        assertThat(turn("final", "answer", "완료 전 설명", version).getResponse().getStatus()).isEqualTo(200);
        assertThat(evidence().path("originalLearning").path("state").asText()).isEqualTo("completed");
    }

    @Test void generatingHelpIsVisibleWithoutInventingADeliveredResponse() throws Exception {
        mode("review");
        long version = version();
        dialogue.entered = new CountDownLatch(1); dialogue.release = new CountDownLatch(1);
        try (var executor = Executors.newSingleThreadExecutor()) {
            var request = executor.submit(() -> turn("slow-help", "example", null, version));
            try {
                assertThat(dialogue.entered.await(10, TimeUnit.SECONDS)).isTrue();
                var pending = evidence();
                assertThat(pending.path("pendingGeneration").path("state").asText()).isEqualTo("generating");
                assertThat(original(pending).path("support").path("requested").path("example").asInt()).isEqualTo(1);
                assertThat(original(pending).path("support").path("delivered").path("total").asInt()).isZero();
            } finally { dialogue.release.countDown(); }
            assertThat(request.get(10, TimeUnit.SECONDS).getResponse().getStatus()).isEqualTo(200);
            assertThat(original(evidence()).path("support").path("delivered").path("example").asInt()).isEqualTo(1);
        }
    }

    @Test void roundsRemainSeparateAcrossRepeatedWrongAttemptsAndAnApplicationRestart() throws Exception {
        completeOriginal();
        var original = evidence().path("originalLearning");
        startReteach("round-1");
        send("r1-help", "dont_know", null);
        finish("r1");
        problems.attempt = "a02";
        startReteach("round-2");
        send("r2-help", "hint", null);
        send("r2-first", "answer", "두 번째 회차의 첫 설명");
        var result = evidence();
        assertThat(result.path("originalLearning")).isEqualTo(original);
        var rounds = result.path("reteachHistory");
        assertThat(rounds.size()).isEqualTo(2);
        assertThat(rounds.get(0).path("state").asText()).isEqualTo("returned");
        assertThat(rounds.get(0).path("originAttemptId").asText()).isEqualTo("a01");
        assertThat(rounds.get(1).path("originAttemptId").asText()).isEqualTo("a02");
        assertThat(rounds.get(0).path("problemId")).isEqualTo(rounds.get(1).path("problemId"));
        assertThat(rounds.get(0).path("evidence").path("support").path("delivered").path("dontKnow").asInt()).isEqualTo(1);
        assertThat(rounds.get(1).path("evidence").path("support").path("delivered").path("hint").asInt()).isEqualTo(1);
        assertThat(rounds.get(1).path("evidence").path("support").path("delivered").path("dontKnow").asInt()).isZero();
        assertThat(rounds.get(1).path("evidence").path("initialAnswer").path("text").asText()).isEqualTo("두 번째 회차의 첫 설명");
        assertThat(rounds.get(1).path("evidence").path("finalExplanation").isNull()).isTrue();
        try (var restarted = new SpringApplicationBuilder(LearningTestApplication.class).web(WebApplicationType.NONE).run()) {
            var restored = restarted.getBean(LearningEvidenceService.class).get("member-1", id);
            assertThat(json.readTree(json.writeValueAsString(restored))).isEqualTo(result);
        }
    }

    @Test void failedAndExpiredReteachOpeningsStayIncompleteWithTheirOriginalTarget() throws Exception {
        completeOriginal();
        opening.fail = true;
        assertThat(start("failed-opening").getResponse().getStatus()).isEqualTo(503);
        var result = evidence();
        var round = result.path("reteachHistory").get(0);
        assertThat(round.path("state").asText()).isEqualTo("failed");
        assertThat(round.path("originAttemptId").asText()).isEqualTo("a01");
        assertThat(round.path("evidence").path("finalExplanation").isNull()).isTrue();
        assertThat(result.path("pendingGeneration").path("operation").asText()).isEqualTo("reteach");
        assertThat(result.path("originalLearning").path("state").asText()).isEqualTo("completed");
        jdbc.update("UPDATE teachmi.learning_reteach SET state='generating',lease_until=now()-interval '1 second'");
        assertThat(evidence().path("pendingGeneration").path("state").asText()).isEqualTo("interrupted");
    }

    @Test void abandonedWorkPreservesDeliveredHelpButCannotLookLikeCompletedLearning() throws Exception {
        mode("review");
        send("help", "hint", null);
        send("initial", "answer", "중단 전 설명");
        replaceSession();
        var result = evidence();
        assertThat(result.path("status").asText()).isEqualTo("abandoned");
        assertThat(result.path("originalLearning").path("state").asText()).isEqualTo("interrupted");
        assertThat(original(result).path("initialAnswer").path("text").asText()).isEqualTo("중단 전 설명");
        assertThat(original(result).path("finalExplanation").isNull()).isTrue();
        assertThat(original(result).path("support").path("delivered").path("hint").asInt()).isEqualTo(1);
        assertThat(result.path("pendingGeneration").isNull()).isTrue();
    }

    @Test void abandonedReteachDoesNotChangeACompletedOriginalLearning() throws Exception {
        completeOriginal();
        startReteach("start");
        send("reteach-help", "example", null);
        replaceSession();
        var result = evidence();
        assertThat(result.path("originalLearning").path("state").asText()).isEqualTo("completed");
        assertThat(result.path("reteachHistory").get(0).path("state").asText()).isEqualTo("interrupted");
        assertThat(result.path("reteachHistory").get(0).path("evidence").path("support").path("delivered").path("example").asInt()).isEqualTo(1);
    }

    @Test void evidenceIsOneCommittedSnapshotEvenInsideCsReadCommittedTransaction() throws Exception {
        mode("review");
        var before = evidence();
        var readReached = new CountDownLatch(1);
        var allowRead = new CountDownLatch(1);
        var once = new AtomicBoolean();
        // Pause the real repository query after its root read, before the related evidence queries.
        var proxy = (Advised) repository;
        MethodInterceptor pause = invocation -> {
            Object result = invocation.proceed();
            if (invocation.getMethod().getName().equals("findByIdAndMemberId")
                    && id.equals(invocation.getArguments()[0]) && once.compareAndSet(false, true)) {
                readReached.countDown();
                if (!allowRead.await(10, TimeUnit.SECONDS)) throw new IllegalStateException("read timeout");
            }
            return result;
        };
        proxy.addAdvice(0, pause);
        var outer = new TransactionTemplate(transactionManager);
        outer.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
        try (var executor = Executors.newSingleThreadExecutor()) {
            var reading = executor.submit(() -> outer.execute(status -> {
                assertThat(jdbc.queryForObject("SHOW transaction_isolation", String.class)).isEqualTo("read committed");
                return evidenceService.get("member-1", id);
            }));
            try {
                if (!readReached.await(10, TimeUnit.SECONDS)) {
                    reading.get(1, TimeUnit.SECONDS); // Surface a worker failure instead of hiding it behind the latch.
                    fail("Evidence query never reached the session read");
                }
                send("concurrent-help", "hint", null);
            } finally { allowRead.countDown(); }
            assertThat(json.readTree(json.writeValueAsString(reading.get(10, TimeUnit.SECONDS)))).isEqualTo(before);
        } finally { proxy.removeAdvice(pause); }
        var after = evidence();
        assertThat(after.path("version").asLong()).isGreaterThan(before.path("version").asLong());
        assertThat(original(after).path("support").path("delivered").path("hint").asInt()).isEqualTo(1);
    }

    @Test void anotherMembersOrMissingSessionNeverExposesEvidence() throws Exception {
        assertThat(mvc.perform(get(path())).andReturn().getResponse().getStatus()).isEqualTo(401);
        assertThat(mvc.perform(get(path()).principal(() -> "member-2")).andReturn().getResponse().getStatus()).isEqualTo(404);
        assertThat(mvc.perform(get("/api/v1/sessions/" + UUID.randomUUID() + "/evidence").principal(() -> "member-1"))
                .andReturn().getResponse().getStatus()).isEqualTo(404);
        assertThat(mvc.perform(get("/api/v1/sessions/not-a-uuid/evidence").principal(() -> "member-1"))
                .andReturn().getResponse().getStatus()).isEqualTo(400);
    }

    @Test void brokenAnswerOrSupportReferencesFailInsteadOfPublishingFalseEvidence() throws Exception {
        completeOriginal();
        var initial = sessionService.get("member-1", id).initialAnswerId();
        jdbc.update("UPDATE teachmi.learning_session SET final_explanation_id=? WHERE id=?", initial, id);
        assertInconsistent();
        jdbc.update("UPDATE teachmi.learning_session SET final_explanation_id=NULL WHERE id=?", id);
        // A failed support has no response. A forged non-null response must not count as delivered.
        UUID event = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO teachmi.learning_support_event
                (id,session_id,kind,target_message_id,request_message_id,response_message_id,created_at)
                VALUES (?,?,'hint',?,?,?,now())
                """, event, id, initial, initial, initial);
        assertInconsistent();
    }

    private void assertInconsistent() throws Exception {
        var result = mvc.perform(get(path()).principal(() -> "member-1")).andReturn();
        assertThat(result.getResponse().getStatus()).isEqualTo(500);
        assertThat(body(result).path("code").asText()).isEqualTo("LEARNING_EVIDENCE_INCONSISTENT");
    }
    private void replaceSession() {
        sessionService.create("member-1", "replace", new CreateSessionRequest("m01", "c02", id, version()));
    }
    private void mode(String mode) throws Exception {
        assertThat(mvc.perform(put("/api/v1/sessions/" + id + "/mode").principal(() -> "member-1")
                .header("Idempotency-Key", "mode").contentType("application/json")
                .content(json.writeValueAsString(new ChooseModeRequest(mode, version())))).andReturn().getResponse().getStatus()).isEqualTo(200);
    }
    private void completeOriginal() throws Exception { mode("review"); finish("normal"); }
    private void finish(String prefix) throws Exception {
        for (int i = 0; i < 4; i++) send(prefix + i, "answer", "의미와 이유를 설명합니다.");
    }
    private void send(String key, String kind, String text) throws Exception {
        assertThat(turn(key, kind, text, version()).getResponse().getStatus()).isEqualTo(200);
    }
    private MvcResult turn(String key, String kind, String text, long version) throws Exception {
        return mvc.perform(post("/api/v1/sessions/" + id + "/turns").principal(() -> "member-1")
                .header("Idempotency-Key", key).contentType("application/json")
                .content(json.writeValueAsString(new TurnRequest(kind, text, version)))).andReturn();
    }
    private void startReteach(String key) throws Exception { assertThat(start(key).getResponse().getStatus()).isEqualTo(200); }
    private MvcResult start(String key) throws Exception {
        return mvc.perform(post("/api/v1/sessions/" + id + "/reteach").principal(() -> "member-1")
                .header("Idempotency-Key", key).contentType("application/json")
                .content(json.writeValueAsString(new StartReteachRequest("p01", problems.attempt, version())))).andReturn();
    }
    private long version() { return sessionService.get("member-1", id).version(); }
    private String path() { return "/api/v1/sessions/" + id + "/evidence"; }
    private JsonNode evidence() throws Exception {
        var response = mvc.perform(get(path()).principal(() -> "member-1")).andReturn();
        assertThat(response.getResponse().getStatus()).isEqualTo(200);
        assertThat(response.getResponse().getHeader("Cache-Control")).isEqualTo("no-store");
        return body(response);
    }
    private JsonNode body(MvcResult result) throws Exception { return json.readTree(result.getResponse().getContentAsByteArray()); }
    private JsonNode original(JsonNode root) { return root.path("originalLearning").path("evidence"); }
}
