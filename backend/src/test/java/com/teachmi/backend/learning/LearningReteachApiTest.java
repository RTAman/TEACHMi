package com.teachmi.backend.learning;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.context.WebApplicationContext;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;

@SpringBootTest(classes = LearningTestApplication.class)
@Import({LearningSessionApiTest.Sources.class, LearningModeTransitionTest.Generation.class,
        LearningTurnsApiTest.Dialogue.class, LearningReteachApiTest.Providers.class})
class LearningReteachApiTest {
    @TestConfiguration(proxyBeanMethods = false)
    static class Providers {
        @Bean Problems problems() { return new Problems(); }
        @Bean Opening opening() { return new Opening(); }
    }
    static class Problems implements ReteachProblemSource {
        final String firstWrongAnswer = "C_FIRST_WRONG_ANSWER";
        final String privateSolution = "C_PRIVATE_SOLUTION";
        String member = "member-1";
        UUID session;
        String problem = "p01";
        String attempt = "a01";
        String judgement = "wrong";
        String focus = "정액법에서 원가를 사용 기간에 배분하는 이유";
        @Override public Target loadWrongAttempt(String memberId, UUID sessionId, String problemId, String attemptId) {
            return new Target(member, session, problem, attempt, judgement, focus);
        }
    }
    static class Opening implements LearningReteachGenerator {
        AtomicInteger calls = new AtomicInteger();
        boolean fail;
        boolean wrongEvidence;
        CountDownLatch entered;
        CountDownLatch release;
        Context last;
        @Override public LearningContentGenerator.Question opening(Context context) {
            calls.incrementAndGet(); last = context;
            if (entered != null) entered.countDown();
            if (release != null) {
                try {
                    if (!release.await(10, TimeUnit.SECONDS)) throw new IllegalStateException("test timeout");
                } catch (InterruptedException ex) {
                    Thread.currentThread().interrupt(); throw new IllegalStateException("interrupted");
                }
            }
            if (fail) throw new LearningException(503, "TEST_GENERATION_FAILED", "생성 중단");
            return new LearningContentGenerator.Question("비용을 사용하는 기간에 나누는 이유를 새 예로 설명해 줄래?",
                    List.of(wrongEvidence ? "wrong-source" : "k01"));
        }
    }

    @Autowired WebApplicationContext context;
    @Autowired ObjectMapper json;
    @Autowired JdbcTemplate jdbc;
    @Autowired Problems problems;
    @Autowired Opening opening;
    @Autowired LearningTurnsApiTest.FixtureDialogue dialogue;
    @Autowired LearningModeTransitionTest.FixtureGenerator preparation;
    @Autowired LearningSessionApiTest.FixtureConceptSource concepts;
    @Autowired LearningReteachService reteachService;
    @Autowired LearningReteachTransactions reteachTransactions;
    @Autowired PlatformTransactionManager transactionManager;
    MockMvc mvc;
    String id;
    JsonNode original;

    @BeforeEach void setup() throws Exception {
        jdbc.execute("TRUNCATE teachmi.learning_reteach, teachmi.learning_turn, teachmi.learning_support_event, "
                + "teachmi.learning_session_request, teachmi.learning_message, teachmi.learning_session");
        concepts.unavailable = false; preparation.fail = false; preparation.unknownEvidence = false;
        dialogue.fail = false; dialogue.sufficient = true; dialogue.invalidHelp = false;
        dialogue.wrongEvidence = false; dialogue.entered = null; dialogue.release = null;
        opening.calls.set(0); opening.fail = false; opening.wrongEvidence = false;
        opening.entered = null; opening.release = null;
        problems.member = "member-1"; problems.problem = "p01"; problems.attempt = "a01";
        problems.judgement = "wrong"; problems.focus = "정액법에서 원가를 사용 기간에 배분하는 이유";
        mvc = MockMvcBuilders.webAppContextSetup(context).build();
        id = body(mvc.perform(post("/api/v1/sessions").principal(() -> "member-1")
                .header("Idempotency-Key", "create").contentType("application/json")
                .content("{\"materialId\":\"m01\",\"conceptId\":\"c01\"}")).andReturn()).path("sessionId").asText();
        mvc.perform(put("/api/v1/sessions/" + id + "/mode").principal(() -> "member-1")
                .header("Idempotency-Key", "mode").contentType("application/json")
                .content("{\"mode\":\"review\",\"expectedVersion\":0}")).andReturn();
        for (int i=0; i<4; i++) {
            assertThat(turn("normal-" + i, "answer", "최초 학습 설명", load().path("version").asLong())
                    .getResponse().getStatus()).isEqualTo(200);
        }
        original = load();
        assertThat(original.path("stage").asText()).isEqualTo("problem");
        problems.session = UUID.fromString(id);
        dialogue.calls.set(0);
    }

    @Test void reteachesAndReturnsToTheSameProblemWithoutOverwritingTheOriginalEvidence() throws Exception {
        var begun = start("start", "p01", "a01", version());
        assertThat(begun.getResponse().getStatus()).isEqualTo(200);
        var state = body(begun);
        String episode = state.path("activeReteach").path("reteachId").asText();
        assertThat(state.path("chatStage").asText()).isEqualTo("case");
        assertThat(state.path("messages").get(9).path("kind").asText()).isEqualTo("reteach_question");
        assertThat(state.path("messages").get(9).path("reteachId").asText()).isEqualTo(episode);
        var helped = body(turn("help", "hint", null, version()));
        assertThat(helped.path("supportEvents").get(0).path("reteachId").asText()).isEqualTo(episode);
        finish("r1");
        var returned = load();
        assertThat(returned.path("stage").asText()).isEqualTo("problem");
        assertThat(returned.path("activeReteach").isNull()).isTrue();
        assertThat(returned.path("problemReturn").path("problemId").asText()).isEqualTo("p01");
        assertThat(returned.path("problemReturn").path("originAttemptId").asText()).isEqualTo("a01");
        assertThat(returned.path("availableActions").get(0).asText()).isEqualTo("retry_problem");
        assertThat(returned.path("messages").get(returned.path("messages").size()-1).path("text").asText())
                .endsWith("같은 문제에 다시 도전해 보세요.").doesNotContain("이제 응용문제를 풀어 보세요.");
        assertThat(returned.path("initialAnswerId")).isEqualTo(original.path("initialAnswerId"));
        assertThat(returned.path("finalExplanationId")).isEqualTo(original.path("finalExplanationId"));
        for (int i=0; i<9; i++) assertThat(returned.path("messages").get(i)).isEqualTo(original.path("messages").get(i));
        var history = returned.path("reteachHistory").get(0);
        assertThat(history.path("state").asText()).isEqualTo("returned");
        assertThat(history.path("finalExplanationId")).isNotEqualTo(original.path("finalExplanationId"));
        assertThat(problems.firstWrongAnswer).isEqualTo("C_FIRST_WRONG_ANSWER");
        assertThat(problems.judgement).isEqualTo("wrong");
        assertThat(dialogue.last.reteach().reteachId().toString()).isEqualTo(episode);
        assertThat(dialogue.last.messages()).allMatch(message -> episode.equals(message.reteachId().toString()));
        assertThat(turn("problem-help", "hint", null, version()).getResponse().getStatus()).isEqualTo(409);
    }

    @Test void returnedReteachGuidanceRestoresWithoutReplayingOrChangingOriginalEvidence() throws Exception {
        start("guidance","p01","a01",version());finish("guidance");
        var expected=load();
        assertThat(expected.path("messages").get(expected.path("messages").size()-1).path("text").asText())
                .endsWith("같은 문제에 다시 도전해 보세요.");
        assertThat(expected.path("problemReturn").path("problemId").asText()).isEqualTo("p01");
        assertThat(expected.path("problemReturn").path("originAttemptId").asText()).isEqualTo("a01");
        assertThat(expected.path("finalExplanationId")).isEqualTo(original.path("finalExplanationId"));
        int calls=dialogue.calls.get();
        try(var restarted=new SpringApplicationBuilder(LearningTestApplication.class).web(WebApplicationType.NONE).run()) {
            var restored=restarted.getBean(LearningSessionService.class).get("member-1",UUID.fromString(id));
            assertThat(json.readTree(json.writeValueAsString(restored))).isEqualTo(expected);
        }
        assertThat(dialogue.calls.get()).isEqualTo(calls);
        for(int i=0;i<9;i++)assertThat(expected.path("messages").get(i)).isEqualTo(original.path("messages").get(i));
    }

    @Test void interruptedReteachAndItsProblemTargetRestoreInANewApplication() throws Exception {
        start("start", "p01", "a01", version());
        turn("step", "answer", "다시 설명", version());
        var expected = load();
        try (var restarted = new SpringApplicationBuilder(LearningTestApplication.class)
                .web(WebApplicationType.NONE).run()) {
            var state = restarted.getBean(LearningSessionService.class).get("member-1", UUID.fromString(id));
            assertThat(json.readTree(json.writeValueAsString(state))).isEqualTo(expected);
        }
    }

    @Test void cAcknowledgesAnExistingProblemReturnAtomicallyAndCanRetryAcknowledgement() throws Exception {
        start("start", "p01", "a01", version()); finish("r");
        var returned = load();
        UUID episode = UUID.fromString(returned.path("problemReturn").path("reteachId").asText());
        long version = version();
        problems.judgement = "correct";
        assertThatThrownBy(() -> reteachService.acknowledgeProblemReturn("member-1", UUID.fromString(id), episode, version))
                .isInstanceOf(LearningException.class);
        assertThat(load().path("problemReturn").isNull()).isFalse();
        problems.judgement = "wrong";
        new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            reteachService.acknowledgeProblemReturn("member-1", UUID.fromString(id), episode, version);
            status.setRollbackOnly();
        });
        assertThat(load().path("problemReturn").isNull()).isFalse();
        var acknowledged = reteachService.acknowledgeProblemReturn("member-1", UUID.fromString(id), episode, version);
        assertThat(acknowledged.problemReturn()).isNull();
        assertThat(acknowledged.reteachHistory()).hasSize(1);
        assertThat(reteachService.acknowledgeProblemReturn("member-1", UUID.fromString(id), episode, version))
                .isEqualTo(acknowledged);
    }

    @Test void anotherWrongAttemptCreatesADistinctReteachForTheSameProblem() throws Exception {
        start("first", "p01", "a01", version()); finish("first");
        var first = load();
        UUID firstId = UUID.fromString(first.path("problemReturn").path("reteachId").asText());
        reteachService.acknowledgeProblemReturn("member-1", UUID.fromString(id), firstId, version());
        assertThat(start("duplicate-attempt", "p01", "a01", version()).getResponse().getStatus()).isEqualTo(409);
        problems.attempt = "a02";
        assertThat(start("second", "p01", "a02", version()).getResponse().getStatus()).isEqualTo(200);
        assertThatThrownBy(() -> reteachService.acknowledgeProblemReturn("member-1", UUID.fromString(id), firstId, 0))
                .isInstanceOf(LearningException.class);
        finish("second");
        var second = load();
        assertThat(second.path("reteachHistory").size()).isEqualTo(2);
        assertThat(second.path("problemReturn").path("problemId").asText()).isEqualTo("p01");
        assertThat(second.path("problemReturn").path("originAttemptId").asText()).isEqualTo("a02");
        assertThat(second.path("initialAnswerId")).isEqualTo(original.path("initialAnswerId"));
        assertThat(second.path("finalExplanationId")).isEqualTo(original.path("finalExplanationId"));
    }

    @Test void missingOrChangedWrongAttemptCannotStartOrFinishReteach() throws Exception {
        problems.judgement = "correct";
        assertThat(start("correct", "p01", "a01", version()).getResponse().getStatus()).isEqualTo(409);
        problems.judgement = "pending_review";
        assertThat(start("pending", "p01", "a01", version()).getResponse().getStatus()).isEqualTo(409);
        problems.judgement = "wrong";
        assertThat(start("wrong-problem", "p02", "a01", version()).getResponse().getStatus()).isEqualTo(404);
        assertThat(start("old-attempt", "p01", "old", version()).getResponse().getStatus()).isEqualTo(409);
        assertThat(load().path("stage").asText()).isEqualTo("problem");
        assertThat(opening.calls.get()).isZero();
        start("good", "p01", "a01", version());
        problems.judgement = "correct";
        assertThat(turn("changed", "answer", "설명", version()).getResponse().getStatus()).isEqualTo(409);
    }

    @Test void generationFailureKeepsTheOriginalTargetAndRetryDoesNotDuplicateTheOpening() throws Exception {
        long originalVersion = version();
        opening.fail = true;
        assertThat(start("retry", "p01", "a01", originalVersion).getResponse().getStatus()).isEqualTo(503);
        var failed = load();
        assertThat(failed.path("activeReteach").path("state").asText()).isEqualTo("failed");
        assertThat(failed.path("activeReteach").path("expectedVersion").asLong()).isEqualTo(originalVersion);
        assertThat(failed.path("availableActions").get(0).asText()).isEqualTo("retry_reteach");
        assertThat(turn("before-opening", "answer", "설명", version()).getResponse().getStatus()).isEqualTo(409);
        opening.fail = false;
        opening.wrongEvidence = true;
        assertThat(start("retry", "p01", "a01", originalVersion).getResponse().getStatus()).isEqualTo(502);
        opening.wrongEvidence = false;
        var completed = start("retry", "p01", "a01", originalVersion);
        assertThat(completed.getResponse().getStatus()).isEqualTo(200);
        assertThat(body(start("retry", "p01", "a01", originalVersion))).isEqualTo(body(completed));
        assertThat(load().path("messages").size()).isEqualTo(10);
        assertThat(load().path("reteachHistory").size()).isEqualTo(1);
        assertThat(opening.calls.get()).isEqualTo(3);
    }

    @Test void missingReteachGeneratorIsExplicitlyPending() throws Exception {
        var empty = new org.springframework.beans.factory.support.StaticListableBeanFactory()
                .getBeanProvider(LearningReteachGenerator.class);
        var service = new LearningReteachService(reteachTransactions, empty);
        long expected = version();
        assertThatThrownBy(() -> service.start("member-1", UUID.fromString(id), "no-generator",
                new StartReteachRequest("p01", "a01", expected))).isInstanceOf(LearningException.class);
        assertThat(load().path("activeReteach").path("state").asText()).isEqualTo("failed");
        assertThat(opening.calls.get()).isZero();
    }

    @Test void cOriginIsRecheckedAfterTheModelFinishes() throws Exception {
        opening.entered = new CountDownLatch(1); opening.release = new CountDownLatch(1);
        long expected = version();
        try (var executor = Executors.newSingleThreadExecutor()) {
            var future = executor.submit(() -> start("start", "p01", "a01", expected));
            try {
                assertThat(opening.entered.await(10, TimeUnit.SECONDS)).isTrue();
                problems.attempt = "a02";
            } finally { opening.release.countDown(); }
            assertThat(future.get(10, TimeUnit.SECONDS).getResponse().getStatus()).isEqualTo(409);
            assertThat(load().path("messages").size()).isEqualTo(9);
        }
    }

    @Test void helpAndFailedTurnRecoveryStayInsideTheReteach() throws Exception {
        start("start", "p01", "a01", version());
        long expected = version();
        dialogue.fail = true;
        assertThat(turn("retry-turn", "answer", "저장할 재설명", expected).getResponse().getStatus()).isEqualTo(503);
        var pending = load();
        assertThat(pending.path("activeReteach").path("problemId").asText()).isEqualTo("p01");
        assertThat(pending.path("pendingTurn").path("expectedVersion").asLong()).isEqualTo(expected);
        dialogue.fail = false;
        assertThat(turn("retry-turn", "answer", "저장할 재설명", expected).getResponse().getStatus()).isEqualTo(200);
        assertThat(load().path("initialAnswerId")).isEqualTo(original.path("initialAnswerId"));
    }

    @Test void finalTurnRevalidatesCBeforeReturningAndPreservesInputOnConflict() throws Exception {
        start("start", "p01", "a01", version());
        for (int i=0;i<3;i++) turn("step-"+i,"answer","재설명",version());
        dialogue.entered = new CountDownLatch(1); dialogue.release = new CountDownLatch(1);
        long expected = version();
        try (var executor = Executors.newSingleThreadExecutor()) {
            var future = executor.submit(() -> turn("final", "answer", "최종 재설명", expected));
            try {
                assertThat(dialogue.entered.await(10, TimeUnit.SECONDS)).isTrue();
                problems.judgement = "correct";
            } finally { dialogue.release.countDown(); }
            assertThat(future.get(10,TimeUnit.SECONDS).getResponse().getStatus()).isEqualTo(409);
            assertThat(load().path("problemReturn").isNull()).isTrue();
            assertThat(load().path("finalExplanationId")).isEqualTo(original.path("finalExplanationId"));
        }
    }

    @Test void concurrentStartAndStaleWorkersCannotDuplicateTheOpening() throws Exception {
        long expected = version();
        opening.entered = new CountDownLatch(1); opening.release = new CountDownLatch(1);
        try(var executor=Executors.newSingleThreadExecutor()) {
            var first=executor.submit(()->start("same","p01","a01",expected));
            try {
                assertThat(opening.entered.await(10,TimeUnit.SECONDS)).isTrue();
                assertThat(start("same","p01","a01",expected).getResponse().getStatus()).isEqualTo(503);
                assertThat(start("different","p01","a01",expected).getResponse().getStatus()).isEqualTo(409);
            } finally { opening.release.countDown(); }
            assertThat(first.get(10,TimeUnit.SECONDS).getResponse().getStatus()).isEqualTo(200);
            assertThat(opening.calls.get()).isEqualTo(1);
        }
    }

    @Test void expiredOpeningClaimCannotCommitAfterReclaim() throws Exception {
        var request=new StartReteachRequest("p01","a01",version());
        var first=reteachTransactions.begin("member-1",UUID.fromString(id),"lease",request);
        jdbc.update("UPDATE teachmi.learning_reteach SET lease_until=now()-interval '1 second'");
        var second=reteachTransactions.begin("member-1",UUID.fromString(id),"lease",request);
        var question=new LearningContentGenerator.Question("배분하는 이유는 무엇일까요?",List.of("k01"));
        assertThatThrownBy(()->reteachTransactions.complete(first,question)).isInstanceOf(LearningException.class);
        assertThat(reteachTransactions.complete(second,question).messages()).hasSize(10);
    }

    @Test void versionStageOwnershipAndRequestKeysAreEnforced() throws Exception {
        assertThat(start("stale","p01","a01",version()-1).getResponse().getStatus()).isEqualTo(409);
        assertThat(start("mode","p01","a01",version()).getResponse().getStatus()).isEqualTo(409);
        problems.member="member-2";
        assertThat(start("not-owned","p01","a01",version()).getResponse().getStatus()).isEqualTo(404);
        problems.member="member-1";
        opening.fail=true;
        long expected=version();
        start("pending","p01","a01",expected);
        assertThat(start("pending","p01","a02",expected).getResponse().getStatus()).isEqualTo(409);
        assertThat(turn("pending","answer","설명",version()).getResponse().getStatus()).isEqualTo(409);
    }

    @Test void invalidInputsAndUnauthenticatedOrOtherMembersRequestsDoNotExposeTargets() throws Exception {
        for(String payload:List.of("{}","{\"problemId\":\"../p\",\"attemptId\":\"a01\",\"expectedVersion\":9}",
                "{\"problemId\":\"p01\",\"attemptId\":\"a01\",\"expectedVersion\":-1}")) {
            assertThat(mvc.perform(post(path()).principal(()->"member-1").header("Idempotency-Key","bad")
                    .contentType("application/json").content(payload)).andReturn().getResponse().getStatus()).isEqualTo(422);
        }
        String valid=json.writeValueAsString(new StartReteachRequest("p01","a01",version()));
        assertThat(mvc.perform(post(path()).contentType("application/json").content(valid)).andReturn()
                .getResponse().getStatus()).isEqualTo(401);
        assertThat(mvc.perform(post(path()).principal(()->"member-2").header("Idempotency-Key","other")
                .contentType("application/json").content(valid)).andReturn().getResponse().getStatus()).isEqualTo(404);
    }

    private void finish(String prefix) throws Exception {
        for(int i=0;i<4;i++) assertThat(turn(prefix+i,"answer","재설명의 의미와 이유",version())
                .getResponse().getStatus()).isEqualTo(200);
    }
    private String path(){return "/api/v1/sessions/"+id+"/reteach";}
    private long version() throws Exception{return load().path("version").asLong();}
    private JsonNode load() throws Exception{return body(mvc.perform(get("/api/v1/sessions/"+id).principal(()->"member-1")).andReturn());}
    private JsonNode body(MvcResult result)throws Exception{return json.readTree(result.getResponse().getContentAsByteArray());}
    private MvcResult start(String key,String problem,String attempt,long version)throws Exception{
        return mvc.perform(post(path()).principal(()->"member-1").header("Idempotency-Key",key).contentType("application/json")
                .content(json.writeValueAsString(new StartReteachRequest(problem,attempt,version)))).andReturn();
    }
    private MvcResult turn(String key,String kind,String text,long version)throws Exception{
        return mvc.perform(post("/api/v1/sessions/"+id+"/turns").principal(()->"member-1").header("Idempotency-Key",key)
                .contentType("application/json").content(json.writeValueAsString(new TurnRequest(kind,text,version)))).andReturn();
    }
}
