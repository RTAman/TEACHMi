package com.teachmi.backend.learning;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
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
import org.springframework.web.context.WebApplicationContext;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;

@SpringBootTest(classes = LearningTestApplication.class)
@Import({LearningSessionApiTest.Sources.class, LearningModeTransitionTest.Generation.class,
        LearningTurnsApiTest.Dialogue.class})
class LearningTurnsApiTest {
    @TestConfiguration(proxyBeanMethods = false)
    static class Dialogue {
        @Bean FixtureDialogue dialogue() { return new FixtureDialogue(); }
    }

    static class FixtureDialogue implements LearningDialogueGenerator {
        AtomicInteger calls = new AtomicInteger();
        boolean sufficient = true;
        boolean fail;
        boolean invalidHelp;
        boolean wrongEvidence;
        CountDownLatch entered;
        CountDownLatch release;
        Context last;

        @Override public Reply reply(Context context) {
            calls.incrementAndGet();
            last = context;
            if (entered != null) entered.countDown();
            if (release != null) {
                try {
                    if (!release.await(10, TimeUnit.SECONDS)) throw new IllegalStateException("test timeout");
                } catch (InterruptedException ex) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException("interrupted");
                }
            }
            if (fail) throw new LearningException(503, "TEST_GENERATION_FAILED", "테스트 생성 중단");
            return new Reply("선택한 개념의 이유와 조건을 설명해 줄래?",
                    context.kind().equals("answer") ? sufficient : invalidHelp,
                    List.of(wrongEvidence ? "unknown-source" : "k01"));
        }
    }

    @Autowired WebApplicationContext context;
    @Autowired JdbcTemplate jdbc;
    @Autowired ObjectMapper json;
    @Autowired FixtureDialogue generator;
    @Autowired LearningSessionApiTest.FixtureConceptSource sources;
    @Autowired LearningModeTransitionTest.FixtureGenerator preparation;
    @Autowired LearningTurnTransactions transactions;
    MockMvc mvc;
    String id;

    @BeforeEach void setup() throws Exception {
        jdbc.execute("TRUNCATE teachmi.learning_reteach, teachmi.learning_turn, teachmi.learning_support_event, teachmi.learning_session_request, "
                + "teachmi.learning_message, teachmi.learning_session");
        generator.calls.set(0);
        generator.sufficient = true;
        generator.fail = false;
        generator.invalidHelp = false;
        generator.wrongEvidence = false;
        generator.entered = null;
        generator.release = null;
        sources.unavailable = false;
        preparation.fail = false;
        preparation.unknownEvidence = false;
        mvc = MockMvcBuilders.webAppContextSetup(context).build();
        id = body(mvc.perform(post("/api/v1/sessions").principal(() -> "member-1")
                .header("Idempotency-Key", "create").contentType("application/json")
                .content("{\"materialId\":\"m01\",\"conceptId\":\"c01\"}")).andReturn())
                .path("sessionId").asText();
        assertThat(mvc.perform(put("/api/v1/sessions/" + id + "/mode").principal(() -> "member-1")
                .header("Idempotency-Key", "mode").contentType("application/json")
                .content("{\"mode\":\"review\",\"expectedVersion\":0}")).andReturn().getResponse().getStatus()).isEqualTo(200);
    }

    @Test void sufficientAnswersAdvanceOneStageAndHandOffEvidenceToC() throws Exception {
        long version = 1;
        String initial = null;
        for (String expected : List.of("concept", "reason", "wrapup", "wrapup")) {
            var result = send("answer-" + version, "answer", "개념의 적용 조건과 이유를 설명합니다.", version);
            assertThat(result.getResponse().getStatus()).isEqualTo(200);
            var state = body(result);
            assertThat(state.path("version").asLong()).isEqualTo(version + 2);
            assertThat(state.path("chatStage").asText()).isEqualTo(expected);
            if (initial == null) initial = state.path("initialAnswerId").asText();
            assertThat(state.path("initialAnswerId").asText()).isEqualTo(initial);
            version = state.path("version").asLong();
        }
        var state = load();
        assertThat(state.path("stage").asText()).isEqualTo("problem");
        assertThat(state.path("availableActions").get(0).asText()).isEqualTo("start_problems");
        assertThat(state.path("finalExplanationId").asText())
                .isEqualTo(state.path("messages").get(7).path("messageId").asText());
        assertThat(state.path("messages").size()).isEqualTo(9);
        for (int i = 0; i < 9; i++) {
            assertThat(state.path("messages").get(i).path("sequence").asLong()).isEqualTo(i + 1);
        }
        assertThat(send("after-complete", "hint", null, version).getResponse().getStatus()).isEqualTo(409);
    }

    @Test void insufficientAnswerStaysOnTheSameStageAndPreservesTheFirstAnswer() throws Exception {
        generator.sufficient = false;
        var first = body(send("first", "answer", "잘못된 설명", 1));
        assertThat(first.path("chatStage").asText()).isEqualTo("case");
        assertThat(first.path("messages").get(2).path("kind").asText()).isEqualTo("followup_question");
        generator.sufficient = true;
        var second = body(send("second", "answer", "수정한 설명", first.path("version").asLong()));
        assertThat(second.path("initialAnswerId")).isEqualTo(first.path("initialAnswerId"));
        assertThat(second.path("chatStage").asText()).isEqualTo("concept");
    }

    @ParameterizedTest @ValueSource(strings = {"hint", "example", "dont_know"})
    void helpStaysOnStageAndLinksTheQuestionAndTheAssistedAnswer(String kind) throws Exception {
        var original = load();
        var help = body(send("help", kind, null, 1));
        assertThat(help.path("chatStage").asText()).isEqualTo("case");
        assertThat(help.path("initialAnswerId").isNull()).isTrue();
        var event = help.path("supportEvents").get(0);
        assertThat(event.path("kind").asText()).isEqualTo(kind);
        assertThat(event.path("targetMessageId")).isEqualTo(original.path("messages").get(0).path("messageId"));
        assertThat(event.path("requestMessageId")).isEqualTo(help.path("messages").get(1).path("messageId"));
        assertThat(event.path("responseMessageId")).isEqualTo(help.path("messages").get(2).path("messageId"));
        var answered = body(send("answer", "answer", "도움을 참고한 설명", help.path("version").asLong()));
        assertThat(answered.path("messages").get(3).path("supportEventId")).isEqualTo(event.path("eventId"));
        assertThat(generator.last.targetQuestion().messageId().toString())
                .isEqualTo(original.path("messages").get(0).path("messageId").asText());
    }

    @Test void savedConversationAndSupportEventsRestoreInANewApplication() throws Exception {
        var helped = body(send("help", "example", null, 1));
        var answered = body(send("answer", "answer", "내 설명", helped.path("version").asLong()));
        try (var restarted = new SpringApplicationBuilder(LearningTestApplication.class)
                .web(WebApplicationType.NONE).run()) {
            var restored = restarted.getBean(LearningSessionService.class).get("member-1", UUID.fromString(id));
            assertThat(json.readTree(json.writeValueAsString(restored))).isEqualTo(answered);
        }
    }

    @Test void generationFailureKeepsInputAndOriginalRetryCoordinatesWithoutDuplicates() throws Exception {
        generator.fail = true;
        var failed = send("retry", "answer", "서버에 남길 설명", 1);
        assertThat(failed.getResponse().getStatus()).isEqualTo(503);
        var saved = load();
        assertThat(saved.path("messages").size()).isEqualTo(2);
        assertThat(saved.path("messages").get(1).path("text").asText()).isEqualTo("서버에 남길 설명");
        assertThat(saved.path("chatStage").asText()).isEqualTo("case");
        assertThat(saved.path("pendingTurn").path("state").asText()).isEqualTo("failed");
        assertThat(saved.path("pendingTurn").path("idempotencyKey").asText()).isEqualTo("retry");
        assertThat(saved.path("pendingTurn").path("expectedVersion").asLong()).isEqualTo(1);
        assertThat(saved.path("availableActions").get(0).asText()).isEqualTo("retry_turn");
        try (var restarted = new SpringApplicationBuilder(LearningTestApplication.class)
                .web(WebApplicationType.NONE).run()) {
            var restored = restarted.getBean(LearningSessionService.class).get("member-1", UUID.fromString(id));
            assertThat(json.readTree(json.writeValueAsString(restored))).isEqualTo(saved);
        }
        assertThat(send("another", "answer", "또 다른 설명", saved.path("version").asLong())
                .getResponse().getStatus()).isEqualTo(409);
        generator.fail = false;
        var completed = send("retry", "answer", "서버에 남길 설명", 1);
        assertThat(completed.getResponse().getStatus()).isEqualTo(200);
        assertThat(body(completed).path("messages").size()).isEqualTo(3);
        assertThat(body(completed).path("pendingTurn").isNull()).isTrue();
        assertThat(body(send("retry", "answer", "서버에 남길 설명", 1))).isEqualTo(body(completed));
        assertThat(generator.calls.get()).isEqualTo(2);
    }

    @Test void failedHelpRetriesDoNotIncrementSupportCounts() throws Exception {
        generator.fail = true;
        assertThat(send("help", "dont_know", null, 1).getResponse().getStatus()).isEqualTo(503);
        assertThat(load().path("supportEvents").size()).isEqualTo(1);
        generator.fail = false;
        var complete = body(send("help", "dont_know", null, 1));
        assertThat(complete.path("supportEvents").size()).isEqualTo(1);
        assertThat(complete.path("messages").size()).isEqualTo(3);
    }

    @Test void badGeneratedEvidenceAndHelpAdvancementDoNotChangeTheStage() throws Exception {
        generator.wrongEvidence = true;
        assertThat(send("bad", "hint", null, 1).getResponse().getStatus()).isEqualTo(502);
        generator.wrongEvidence = false;
        generator.invalidHelp = true;
        assertThat(send("bad", "hint", null, 1).getResponse().getStatus()).isEqualTo(502);
        assertThat(load().path("chatStage").asText()).isEqualTo("case");
        assertThat(load().path("messages").size()).isEqualTo(2);
    }

    @Test void rejectsStaleVersionsAndKeysReusedForOtherOperations() throws Exception {
        assertThat(send("stale", "answer", "설명", 0).getResponse().getStatus()).isEqualTo(409);
        assertThat(send("create", "answer", "설명", 1).getResponse().getStatus()).isEqualTo(409);
        generator.fail = true;
        assertThat(send("pending", "answer", "원래 설명", 1).getResponse().getStatus()).isEqualTo(503);
        assertThat(send("pending", "answer", "변경된 설명", 1).getResponse().getStatus()).isEqualTo(409);
        var reuse = mvc.perform(post("/api/v1/sessions").principal(() -> "member-1")
                .header("Idempotency-Key", "pending").contentType("application/json")
                .content(json.writeValueAsString(new CreateSessionRequest("m01", "c02",
                        UUID.fromString(id), load().path("version").asLong())))).andReturn();
        assertThat(reuse.getResponse().getStatus()).isEqualTo(409);
    }

    @Test void inputIsCommittedBeforeGenerationAndConcurrentSameKeyRunsTheModelOnce() throws Exception {
        generator.entered = new CountDownLatch(1);
        generator.release = new CountDownLatch(1);
        try (var executor = Executors.newSingleThreadExecutor()) {
            var first = executor.submit(() -> send("same", "answer", "내 설명", 1));
            try {
                assertThat(generator.entered.await(10, TimeUnit.SECONDS)).isTrue();
                assertThat(load().path("pendingTurn").path("state").asText()).isEqualTo("generating");
                assertThat(load().path("messages").size()).isEqualTo(2);
                assertThat(send("same", "answer", "내 설명", 1).getResponse().getStatus()).isEqualTo(503);
                assertThat(send("different", "hint", null, 1).getResponse().getStatus()).isEqualTo(409);
            } finally {
                generator.release.countDown();
            }
            assertThat(first.get(10, TimeUnit.SECONDS).getResponse().getStatus()).isEqualTo(200);
            assertThat(generator.calls.get()).isEqualTo(1);
            assertThat(load().path("messages").size()).isEqualTo(3);
        }
    }

    @Test void replacementWhileGeneratingCannotAppendAnAssistantToTheAbandonedSession() throws Exception {
        generator.entered = new CountDownLatch(1);
        generator.release = new CountDownLatch(1);
        try (var executor = Executors.newSingleThreadExecutor()) {
            var first = executor.submit(() -> send("answer", "answer", "내 설명", 1));
            try {
                assertThat(generator.entered.await(10, TimeUnit.SECONDS)).isTrue();
                var replaced = mvc.perform(post("/api/v1/sessions").principal(() -> "member-1")
                        .header("Idempotency-Key", "replace").contentType("application/json")
                        .content(json.writeValueAsString(new CreateSessionRequest("m01", "c02", UUID.fromString(id),
                                load().path("version").asLong())))).andReturn();
                assertThat(replaced.getResponse().getStatus()).isEqualTo(201);
            } finally { generator.release.countDown(); }
            assertThat(first.get(10, TimeUnit.SECONDS).getResponse().getStatus()).isEqualTo(409);
            assertThat(load().path("status").asText()).isEqualTo("abandoned");
            assertThat(load().path("messages").size()).isEqualTo(2);
        }
    }

    @Test void expiredProcessingLeaseCanResumeWithAFreshClaim() throws Exception {
        generator.fail = true;
        send("retry", "answer", "설명", 1);
        jdbc.update("UPDATE teachmi.learning_turn SET state='generating',lease_until=now()-interval '1 second'");
        generator.fail = false;
        assertThat(send("retry", "answer", "설명", 1).getResponse().getStatus()).isEqualTo(200);
    }

    @Test void aStaleWorkerCannotCommitAfterItsLeaseWasReclaimed() throws Exception {
        var request = new TurnRequest("answer", "내 설명", 1L);
        var first = transactions.begin("member-1", UUID.fromString(id), "lease", request);
        jdbc.update("UPDATE teachmi.learning_turn SET lease_until=now()-interval '1 second'");
        var second = transactions.begin("member-1", UUID.fromString(id), "lease", request);
        var reply = new LearningDialogueGenerator.Reply("개념을 설명해 줄래?", true, List.of("k01"));
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> transactions.complete(first, reply))
                .isInstanceOf(LearningException.class);
        var completed = transactions.complete(second, reply);
        assertThat(completed.messages()).hasSize(3);
        assertThat(completed.chatStage()).isEqualTo("concept");
    }

    @ParameterizedTest @ValueSource(strings = {"mode", "summary", "problem", "report"})
    void onlyTheChatStageAcceptsTurns(String stage) throws Exception {
        jdbc.update("UPDATE teachmi.learning_session SET stage=? WHERE id=?", stage, UUID.fromString(id));
        assertThat(send("not-chat", "hint", null, 1).getResponse().getStatus()).isEqualTo(409);
        assertThat(generator.calls.get()).isZero();
    }

    @Test void validatesInputAndHidesOtherMembersConversation() throws Exception {
        assertThat(send("too-long", "answer", "가".repeat(4001), 1).getResponse().getStatus()).isEqualTo(422);
        assertThat(send("null-byte", "answer", "설명\0", 1).getResponse().getStatus()).isEqualTo(422);
        for (String payload : List.of("{}", "{\"kind\":\"answer\",\"text\":\" \",\"expectedVersion\":1}",
                "{\"kind\":\"hint\",\"text\":\"discarded?\",\"expectedVersion\":1}",
                "{\"kind\":\"unknown\",\"expectedVersion\":1}", "{\"kind\":\"hint\",\"expectedVersion\":-1}")) {
            assertThat(mvc.perform(post(path()).principal(() -> "member-1").header("Idempotency-Key", "bad")
                    .contentType("application/json").content(payload)).andReturn().getResponse().getStatus()).isEqualTo(422);
        }
        String valid = json.writeValueAsString(new TurnRequest("answer", "설명", 1L));
        assertThat(mvc.perform(post(path()).contentType("application/json").content(valid))
                .andReturn().getResponse().getStatus()).isEqualTo(401);
        assertThat(mvc.perform(post(path()).principal(() -> "member-1").contentType("application/json").content(valid))
                .andReturn().getResponse().getStatus()).isEqualTo(422);
        assertThat(mvc.perform(post(path()).principal(() -> "member-2").header("Idempotency-Key", "other")
                .contentType("application/json").content(valid)).andReturn().getResponse().getStatus()).isEqualTo(404);
        assertThat(load().path("messages").size()).isEqualTo(1);
    }

    @Test void modeReceiptsWithLegacyMessageFieldsStillReplay() throws Exception {
        var old = (tools.jackson.databind.node.ObjectNode) load();
        for (String field : List.of("initialAnswerId", "finalExplanationId", "supportEvents", "pendingTurn", "availableActions")) {
            old.remove(field);
        }
        for (var message : old.path("messages")) {
            ((tools.jackson.databind.node.ObjectNode) message).remove("supportEventId");
        }
        String originalMessageId = old.path("messages").get(0).path("messageId").asText();
        jdbc.update("UPDATE teachmi.learning_session_request SET response_body=? WHERE member_id='member-1' AND request_key='mode'",
                json.writeValueAsString(old));
        var replay = mvc.perform(put("/api/v1/sessions/" + id + "/mode").principal(() -> "member-1")
                .header("Idempotency-Key", "mode").contentType("application/json")
                .content("{\"mode\":\"review\",\"expectedVersion\":0}")).andReturn();
        assertThat(replay.getResponse().getStatus()).isEqualTo(200);
        assertThat(body(replay).path("messages").get(0).path("messageId").asText()).isEqualTo(originalMessageId);
        assertThat(body(replay).path("messages").get(0).path("supportEventId").isNull()).isTrue();
        assertThat(body(replay).path("supportEvents").size()).isZero();
    }

    private String path() { return "/api/v1/sessions/" + id + "/turns"; }
    private MvcResult send(String key, String kind, String text, long version) throws Exception {
        return mvc.perform(post(path()).principal(() -> "member-1").header("Idempotency-Key", key)
                .contentType("application/json").content(json.writeValueAsString(new TurnRequest(kind, text, version))))
                .andReturn();
    }
    private JsonNode body(MvcResult result) throws Exception { return json.readTree(result.getResponse().getContentAsByteArray()); }
    private JsonNode load() throws Exception {
        var result = mvc.perform(get("/api/v1/sessions/" + id).principal(() -> "member-1")).andReturn();
        assertThat(result.getResponse().getStatus()).isEqualTo(200);
        return body(result);
    }
}
