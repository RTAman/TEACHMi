package com.teachmi.backend.learning;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.aopalliance.intercept.MethodInterceptor;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.aop.framework.Advised;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

@SpringBootTest(classes = LearningTestApplication.class)
@Import({LearningSessionApiTest.Sources.class, LearningModeTransitionTest.Generation.class,
        LearningTurnsApiTest.Dialogue.class, LearningReteachApiTest.Providers.class})
class LearningActiveSessionApiTest {
    @Autowired WebApplicationContext context;
    @Autowired JdbcTemplate jdbc;
    @Autowired ObjectMapper json;
    @Autowired LearningSessionService sessions;
    @Autowired LearningSessionRepository repository;
    @Autowired LearningTurnService turns;
    @Autowired LearningTurnTransactions turnTransactions;
    @Autowired LearningReteachTransactions reteachTransactions;
    @Autowired PlatformTransactionManager transactionManager;
    @Autowired LearningSessionApiTest.FixtureConceptSource concepts;
    @Autowired LearningModeTransitionTest.FixtureGenerator preparation;
    @Autowired LearningTurnsApiTest.FixtureDialogue dialogue;
    @Autowired LearningReteachApiTest.Opening opening;
    @Autowired LearningReteachApiTest.Problems problems;
    MockMvc mvc;

    @BeforeEach void setup() {
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
    }

    @Test void noActiveSessionReturnsAnEmptyUncacheable204WithoutCreatingAnything() throws Exception {
        var response = active("member-1");
        assertThat(response.getResponse().getStatus()).isEqualTo(204);
        assertThat(response.getResponse().getContentAsByteArray()).isEmpty();
        assertThat(response.getResponse().getHeader("Cache-Control")).isEqualTo("no-store");
        assertThat(sessions.getActive("member-1")).isEmpty();
        assertThat(repository.count()).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM teachmi.learning_session_request", Long.class)).isZero();
        assertNoGeneration();
    }

    @Test void authenticationAndMembershipControlDiscoveryAndTheLiteralRouteWinsOverUuidBinding() throws Exception {
        UUID own = create();
        var other = repository.saveAndFlush(new LearningSession("member-2", new CreateSessionRequest("m02", "c02", null, null),
                "{\"name\":\"다른 회원의 개념\",\"scope\":\"범위\",\"sourceChunkIds\":[\"k02\"]}"));
        assertThat(mvc.perform(get("/api/v1/sessions/active")).andReturn().getResponse().getStatus()).isEqualTo(401);
        for (String invalid : List.of(" ", "x".repeat(129))) {
            assertThat(active(invalid).getResponse().getStatus()).isEqualTo(401);
        }
        assertThat(active("member-3").getResponse().getStatus()).isEqualTo(204);
        assertThat(body(active("member-2")).path("sessionId").asText()).isEqualTo(other.id.toString());
        var requestedOtherMember = mvc.perform(get("/api/v1/sessions/active").principal(() -> "member-1")
                .param("memberId", "member-2")).andReturn();
        assertThat(requestedOtherMember.getResponse().getStatus()).isEqualTo(200);
        assertThat(body(requestedOtherMember).path("sessionId").asText()).isEqualTo(own.toString());
        assertThat(body(requestedOtherMember).path("resume").path("path").asText()).isEqualTo("/api/v1/sessions/" + own);
        assertMatches(own);
        assertNoGeneration();
    }

    @Test void completedAndAbandonedSessionsAreExcludedWhileAnActiveReportStillHasAResumeTarget() throws Exception {
        UUID id = create();
        for (String status : List.of("completed", "abandoned")) {
            // C will own real completion. These fixtures only exercise the existing status contract.
            jdbc.update("UPDATE teachmi.learning_session SET status=? WHERE id=?", status, id);
            assertThat(active("member-1").getResponse().getStatus()).isEqualTo(204);
        }
        jdbc.update("UPDATE teachmi.learning_session SET status='active',stage='report' WHERE id=?", id);
        var report = assertMatches(id);
        assertThat(report.path("availableActions").get(0).asText()).isEqualTo("view_report");
        assertNoGeneration();
    }

    @Test void modeSummaryChatAndProblemReuseTheExistingResumeContractWithoutAdditionalCallsOrWrites() throws Exception {
        UUID id = create();
        assertThat(assertMatches(id).path("availableActions").get(0).asText()).isEqualTo("select_mode");
        sessions.chooseMode("member-1", id, "mode", new ChooseModeRequest("first", 0L));
        assertThat(assertMatches(id).path("summary").path("text").asText()).isNotBlank();
        assertThat(assertMatches(id).path("availableActions").get(0).asText()).isEqualTo("start_chat");
        sessions.startChat("member-1", id, "chat", new StartChatRequest(version(id)));
        assertThat(assertMatches(id).path("chatStage").asText()).isEqualTo("case");
        finish(id, "normal");
        var before = sessions.get("member-1", id);
        long receipts = jdbc.queryForObject("SELECT count(*) FROM teachmi.learning_session_request", Long.class);
        int calls = dialogue.calls.get();
        var result = assertMatches(id);
        assertThat(result.path("stage").asText()).isEqualTo("problem");
        assertThat(result.path("availableActions").get(0).asText()).isEqualTo("start_problems");
        assertMatches(id);
        assertThat(sessions.get("member-1", id)).isEqualTo(before);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM teachmi.learning_session_request", Long.class)).isEqualTo(receipts);
        assertThat(preparation.summaries.get()).isEqualTo(1);
        assertThat(preparation.questions.get()).isEqualTo(1);
        assertThat(dialogue.calls.get()).isEqualTo(calls);
        assertThat(opening.calls.get()).isZero();
    }

    @Test void generatingFailedAndExpiredTurnsKeepTheirSavedInputAndOriginalRetryCredentials() throws Exception {
        UUID id = review();
        long originalVersion = version(id);
        var claim = turnTransactions.begin("member-1", id, "saved-answer", new TurnRequest("answer", "저장된 설명", originalVersion));
        var generating = assertMatches(id);
        assertThat(generating.path("pendingTurn").path("state").asText()).isEqualTo("generating");
        assertThat(generating.path("availableActions").get(0).asText()).isEqualTo("wait");
        assertThat(generating.has("recovery")).as("normal processing is distinct from a terminal hold").isFalse();
        turnTransactions.fail(claim, "TEST_GENERATION_FAILED");
        var failed = assertMatches(id);
        assertThat(failed.path("availableActions").get(0).asText()).isEqualTo("retry_turn");
        assertThat(failed.has("recovery")).as("ordinary retriable failure keeps the original response shape").isFalse();
        assertThat(failed.path("pendingTurn").path("idempotencyKey").asText()).isEqualTo("saved-answer");
        assertThat(failed.path("pendingTurn").path("expectedVersion").asLong()).isEqualTo(originalVersion);
        assertThat(failed.path("messages").get(1).path("text").asText()).isEqualTo("저장된 설명");
        jdbc.update("UPDATE teachmi.learning_turn SET state='generating',lease_until=now()-interval '1 second'");
        assertThat(assertMatches(id).path("pendingTurn").path("state").asText()).isEqualTo("interrupted");
        assertThat(dialogue.calls.get()).isZero();
    }

    @Test void reteachOpeningRecoveryAndTheSameProblemReturnAreDiscoverableWithoutRememberingAnId() throws Exception {
        UUID id = review(); finish(id, "normal"); problems.session = id;
        long originalVersion = version(id);
        var request = new StartReteachRequest("p01", "a01", originalVersion);
        var claim = reteachTransactions.begin("member-1", id, "reteach", request);
        assertThat(assertMatches(id).path("activeReteach").path("state").asText()).isEqualTo("generating");
        reteachTransactions.fail(claim, "TEST_GENERATION_FAILED");
        var failed = assertMatches(id);
        assertThat(failed.path("availableActions").get(0).asText()).isEqualTo("retry_reteach");
        assertThat(failed.path("activeReteach").path("problemId").asText()).isEqualTo("p01");
        assertThat(failed.path("activeReteach").path("idempotencyKey").asText()).isEqualTo("reteach");
        assertThat(failed.path("activeReteach").path("expectedVersion").asLong()).isEqualTo(originalVersion);
        var retried = reteachTransactions.begin("member-1", id, "reteach", request);
        jdbc.update("UPDATE teachmi.learning_reteach SET lease_until=now()-interval '1 second'");
        assertThat(assertMatches(id).path("activeReteach").path("state").asText()).isEqualTo("interrupted");
        reteachTransactions.complete(retried, new LearningContentGenerator.Question("새 사례에서 비용을 배분하는 이유는?", List.of("k01")));
        assertThat(assertMatches(id).path("chatStage").asText()).isEqualTo("case");
        finish(id, "reteach");
        var returned = assertMatches(id);
        assertThat(returned.path("problemReturn").path("problemId").asText()).isEqualTo("p01");
        assertThat(returned.path("problemReturn").path("originAttemptId").asText()).isEqualTo("a01");
        assertThat(returned.path("availableActions").get(0).asText()).isEqualTo("retry_problem");
        assertThat(opening.calls.get()).isZero();
    }

    @Test void replacementIsDiscoverableAndANewApplicationCanReadItWithoutSourceOrGeneratorAdapters() throws Exception {
        UUID old = review();
        var replacement = sessions.create("member-1", "replace", new CreateSessionRequest("m01", "c02", old, version(old)));
        assertMatches(replacement.sessionId());
        assertThat(sessions.get("member-1", old).status()).isEqualTo("abandoned");
        try (var restarted = new SpringApplicationBuilder(LearningTestApplication.class).web(WebApplicationType.NONE).run()) {
            var restored = restarted.getBean(LearningSessionService.class).getActive("member-1");
            assertThat(restored).contains(replacement);
        }
    }

    @Test void concurrentReplacementCannotMixOldAndNewStateEvenWhenCAlreadyHasATransaction() throws Exception {
        UUID old = review();
        var expected = sessions.get("member-1", old);
        var reached = new CountDownLatch(1); var release = new CountDownLatch(1); var once = new AtomicBoolean();
        var proxy = (Advised) repository;
        MethodInterceptor pause = invocation -> {
            Object result = invocation.proceed();
            if (invocation.getMethod().getName().equals("findActiveByMemberId") && once.compareAndSet(false, true)) {
                reached.countDown();
                if (!release.await(10, TimeUnit.SECONDS)) throw new IllegalStateException("read timeout");
            }
            return result;
        };
        proxy.addAdvice(0, pause);
        var outer = new TransactionTemplate(transactionManager);
        outer.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
        UUID replacement;
        try (var executor = Executors.newSingleThreadExecutor()) {
            var reading = executor.submit(() -> outer.execute(status -> {
                assertThat(jdbc.queryForObject("SHOW transaction_isolation", String.class)).isEqualTo("read committed");
                return sessions.getActive("member-1");
            }));
            try {
                if (!reached.await(10, TimeUnit.SECONDS)) {
                    reading.get(1, TimeUnit.SECONDS);
                    fail("Active lookup never reached the root query");
                }
                replacement = sessions.create("member-1", "replace", new CreateSessionRequest("m01", "c02", old, expected.version())).sessionId();
            } finally { release.countDown(); }
            assertThat(reading.get(10, TimeUnit.SECONDS)).contains(expected);
        } finally { proxy.removeAdvice(pause); }
        assertMatches(replacement);
    }

    private UUID create() {
        return sessions.create("member-1", "create", new CreateSessionRequest("m01", "c01", null, null)).sessionId();
    }
    private UUID review() {
        UUID id = create();
        sessions.chooseMode("member-1", id, "mode", new ChooseModeRequest("review", 0L));
        return id;
    }
    private long version(UUID id) { return sessions.get("member-1", id).version(); }
    private void finish(UUID id, String prefix) {
        for (int i = 0; i < 4; i++) {
            turns.send("member-1", id, prefix + i, new TurnRequest("answer", "의미와 이유를 설명합니다.", version(id)));
        }
    }
    private MvcResult active(String member) throws Exception {
        return mvc.perform(get("/api/v1/sessions/active").principal(() -> member)).andReturn();
    }
    private JsonNode assertMatches(UUID id) throws Exception {
        var response = active("member-1");
        assertThat(response.getResponse().getStatus()).isEqualTo(200);
        assertThat(response.getResponse().getHeader("Cache-Control")).isEqualTo("no-store");
        var result = body(response);
        assertThat(result).isEqualTo(json.readTree(json.writeValueAsString(sessions.get("member-1", id))));
        return result;
    }
    private JsonNode body(MvcResult result) throws Exception { return json.readTree(result.getResponse().getContentAsByteArray()); }
    private void assertNoGeneration() {
        assertThat(preparation.questions.get() + preparation.summaries.get() + dialogue.calls.get() + opening.calls.get()).isZero();
    }
}
