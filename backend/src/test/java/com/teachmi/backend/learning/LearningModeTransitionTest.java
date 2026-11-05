package com.teachmi.backend.learning;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CyclicBarrier;
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
import org.springframework.web.context.WebApplicationContext;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;

@SpringBootTest(classes = LearningTestApplication.class)
@Import({LearningSessionApiTest.Sources.class, LearningModeTransitionTest.Generation.class})
class LearningModeTransitionTest {
    @Autowired WebApplicationContext context;
    @Autowired JdbcTemplate jdbc;
    @Autowired ObjectMapper json;
    @Autowired FixtureGenerator generator;
    @Autowired LearningSessionApiTest.FixtureConceptSource source;
    MockMvc mvc;
    String id;

    @TestConfiguration(proxyBeanMethods = false)
    static class Generation {
        @Bean FixtureGenerator fixtureGenerator() {
            return new FixtureGenerator();
        }
    }

    static class FixtureGenerator implements LearningContentGenerator {
        final AtomicInteger summaries = new AtomicInteger();
        final AtomicInteger questions = new AtomicInteger();
        boolean fail;
        boolean unknownEvidence;

        private List<String> evidence() {
            if (fail) {
                throw new IllegalStateException("test provider unavailable");
            }
            return List.of(unknownEvidence ? "other-material" : "k01");
        }

        @Override public Summary summarize(Context context) {
            summaries.incrementAndGet();
            return new Summary("취득원가를 내용연수에 걸쳐 배분합니다.",
                    List.of("600만 원 컴퓨터, 3년, 잔존가치 0원이면 매년 200만 원입니다."),
                    List.of("내용연수", "정액법"), "사용 기간에 나누는 이유는 무엇일까요?", evidence());
        }

        @Override public Question openingQuestion(Context context) {
            questions.incrementAndGet();
            return new Question("3년 쓰는 컴퓨터의 구입비를 첫해에 모두 비용 처리할까요?", evidence());
        }
    }

    @BeforeEach
    void setup() throws Exception {
        mvc = MockMvcBuilders.webAppContextSetup(context).build();
        jdbc.execute("TRUNCATE teachmi.learning_turn, teachmi.learning_support_event, teachmi.learning_session_request, teachmi.learning_message, teachmi.learning_session");
        source.unavailable = false;
        generator.fail = false;
        generator.unknownEvidence = false;
        generator.summaries.set(0);
        generator.questions.set(0);
        id = body(mvc.perform(post("/api/v1/sessions").principal(() -> "member-1")
                .header("Idempotency-Key", "create").contentType("application/json")
                .content("{\"materialId\":\"m01\",\"conceptId\":\"c01\"}")).andReturn())
                .path("sessionId").asText();
    }

    @Test
    void firstModeSavesPreparationThenStartsOneCaseQuestion() throws Exception {
        var preparation = mode("choose", "first", 0);
        assertThat(preparation.getResponse().getStatus()).isEqualTo(200);
        var summary = body(preparation);
        assertThat(summary.path("stage").asText()).isEqualTo("summary");
        assertThat(summary.path("version").asLong()).isEqualTo(1);
        assertThat(summary.path("summary").path("examples").size()).isEqualTo(1);
        assertThat(summary.path("summary").path("keyTerms").size()).isEqualTo(2);
        assertThat(summary.path("messages").size()).isZero();
        var started = chat("start", 1);
        assertThat(started.getResponse().getStatus()).isEqualTo(200);
        assertChat(body(started), 2);
        assertThat(generator.summaries.get()).isEqualTo(1);
        assertThat(generator.questions.get()).isEqualTo(1);
        assertThat(body(mvc.perform(get("/api/v1/sessions/" + id)
                .principal(() -> "member-1")).andReturn())).isEqualTo(body(started));
    }

    @Test
    void reviewModeSkipsSummaryAndPersistsTheFirstQuestion() throws Exception {
        var result = mode("review", "review", 0);
        assertThat(result.getResponse().getStatus()).isEqualTo(200);
        assertChat(body(result), 1);
        assertThat(body(result).path("summary").isNull()).isTrue();
        assertThat(generator.summaries.get()).isZero();
        assertThat(generator.questions.get()).isEqualTo(1);
        assertThat(chat("unused", 1).getResponse().getStatus()).isEqualTo(409);
    }

    @Test
    void aNewApplicationRestoresSavedSummaryAndQuestionWithoutAGenerator() throws Exception {
        mode("choose", "first", 0);
        var original = body(chat("start", 1));
        try (var restoredApp = new SpringApplicationBuilder(LearningTestApplication.class)
                .web(WebApplicationType.NONE).run()) {
            var restored = restoredApp.getBean(LearningSessionService.class).get("member-1", UUID.fromString(id));
            JsonNode restoredJson = json.readTree(json.writeValueAsString(restored));
            assertThat(restoredJson).isEqualTo(original);
        }
    }

    @Test
    void staleVersionsAndSkippedStagesHaveNoSideEffects() throws Exception {
        assertThat(chat("early", 0).getResponse().getStatus()).isEqualTo(409);
        assertThat(mode("future", "first", 3).getResponse().getStatus()).isEqualTo(409);
        mode("valid", "first", 0);
        var stale = chat("stale", 0);
        assertThat(stale.getResponse().getStatus()).isEqualTo(409);
        assertThat(body(stale).path("currentSession").path("stage").asText()).isEqualTo("summary");
        assertThat(body(stale).path("currentSession").path("version").asLong()).isEqualTo(1);
        assertThat(mode("change", "review", 1).getResponse().getStatus()).isEqualTo(409);
        assertThat(generator.summaries.get()).isEqualTo(1);
        assertThat(generator.questions.get()).isZero();
    }

    @Test
    void rejectsCompletedAndAbandonedSessions() throws Exception {
        for (String status : List.of("completed", "abandoned")) {
            jdbc.update("UPDATE teachmi.learning_session SET status=? WHERE id=?", status, UUID.fromString(id));
            assertThat(mode(status, "review", 0).getResponse().getStatus()).isEqualTo(409);
            assertThat(chat(status, 0).getResponse().getStatus()).isEqualTo(409);
        }
        assertThat(generator.questions.get()).isZero();
    }

    @Test
    void retriesReturnSavedResponsesAndKeysCannotCrossOperations() throws Exception {
        var summary = body(mode("choose", "first", 0));
        var started = body(chat("start", 1));
        assertThat(body(mode("choose", "first", 0))).isEqualTo(summary);
        assertThat(body(chat("start", 1))).isEqualTo(started);
        assertThat(mode("choose", "review", 0).getResponse().getStatus()).isEqualTo(409);
        assertThat(chat("choose", 0).getResponse().getStatus()).isEqualTo(409);
        assertThat(mode("create", "first", 0).getResponse().getStatus()).isEqualTo(409);
        assertThat(generator.summaries.get()).isEqualTo(1);
        assertThat(generator.questions.get()).isEqualTo(1);
    }

    @Test
    void reopeningFirstModeChatDoesNotRegenerateOrAdvanceTheVersion() throws Exception {
        mode("choose", "first", 0);
        var started = body(chat("start", 1));
        assertThat(body(chat("new-key", 2))).isEqualTo(started);
        assertThat(generator.questions.get()).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM teachmi.learning_message", Long.class)).isEqualTo(1);
    }

    @Test
    void generationFailureLeavesTheModeAndVersionUnchangedAndTheSameKeyCanRetry() throws Exception {
        generator.fail = true;
        var failure = mode("retry", "first", 0);
        assertThat(failure.getResponse().getStatus()).isEqualTo(502);
        assertThat(body(failure).path("code").asText()).isEqualTo("LEARNING_CONTENT_FAILED");
        var current = body(mvc.perform(get("/api/v1/sessions/" + id).principal(() -> "member-1")).andReturn());
        assertThat(current.path("stage").asText()).isEqualTo("mode");
        assertThat(current.path("version").asLong()).isZero();
        generator.fail = false;
        assertThat(mode("retry", "first", 0).getResponse().getStatus()).isEqualTo(200);
        generator.fail = true;
        assertThat(chat("question", 1).getResponse().getStatus()).isEqualTo(502);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM teachmi.learning_message", Long.class)).isZero();
        generator.fail = false;
        assertThat(chat("question", 1).getResponse().getStatus()).isEqualTo(200);
    }

    @Test
    void reviewGenerationFailureCanRetryWithoutAnOrphanMessage() throws Exception {
        generator.fail = true;
        assertThat(mode("review", "review", 0).getResponse().getStatus()).isEqualTo(502);
        generator.fail = false;
        assertChat(body(mode("review", "review", 0)), 1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM teachmi.learning_message", Long.class)).isEqualTo(1);
    }

    @Test
    void rejectsContentThatCitesAnotherMaterial() throws Exception {
        generator.unknownEvidence = true;
        assertThat(mode("first", "first", 0).getResponse().getStatus()).isEqualTo(502);
        assertThat(mode("review", "review", 0).getResponse().getStatus()).isEqualTo(502);
        generator.unknownEvidence = false;
        mode("first", "first", 0);
        generator.unknownEvidence = true;
        assertThat(chat("start", 1).getResponse().getStatus()).isEqualTo(502);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM teachmi.learning_message", Long.class)).isZero();
    }

    @Test
    void requiresAuthenticationAndConcealsOtherMembersSessions() throws Exception {
        assertThat(mvc.perform(put("/api/v1/sessions/" + id + "/mode")
                .header("Idempotency-Key", "x").contentType("application/json")
                .content("{\"mode\":\"first\",\"expectedVersion\":0}"))
                .andReturn().getResponse().getStatus()).isEqualTo(401);
        for (String target : List.of(id, UUID.randomUUID().toString())) {
            assertThat(mvc.perform(put("/api/v1/sessions/" + target + "/mode")
                    .principal(() -> "member-2").header("Idempotency-Key", "x")
                    .contentType("application/json").content("{\"mode\":\"first\",\"expectedVersion\":0}"))
                    .andReturn().getResponse().getStatus()).isEqualTo(404);
            assertThat(mvc.perform(post("/api/v1/sessions/" + target + "/chat/start")
                    .principal(() -> "member-2").header("Idempotency-Key", "x")
                    .contentType("application/json").content("{\"expectedVersion\":0}"))
                    .andReturn().getResponse().getStatus()).isEqualTo(404);
        }
    }

    @Test
    void validatesModeVersionAndIdempotencyHeaders() throws Exception {
        for (String body : List.of("{\"mode\":\"other\",\"expectedVersion\":0}",
                "{\"mode\":\"first\"}", "{\"mode\":\"review\",\"expectedVersion\":-1}", "{}")) {
            assertThat(mvc.perform(put("/api/v1/sessions/" + id + "/mode").principal(() -> "member-1")
                    .header("Idempotency-Key", "x").contentType("application/json").content(body))
                    .andReturn().getResponse().getStatus()).isEqualTo(422);
        }
        assertThat(mvc.perform(put("/api/v1/sessions/" + id + "/mode").principal(() -> "member-1")
                .contentType("application/json").content("{\"mode\":\"first\",\"expectedVersion\":0}"))
                .andReturn().getResponse().getStatus()).isEqualTo(422);
        assertThat(mvc.perform(post("/api/v1/sessions/" + id + "/chat/start").principal(() -> "member-1")
                .header("Idempotency-Key", "x").contentType("application/json").content("{}"))
                .andReturn().getResponse().getStatus()).isEqualTo(422);
    }

    @Test
    void concurrentIdenticalModeRequestsGenerateOnce() throws Exception {
        var result = together(() -> mode("same", "review", 0), () -> mode("same", "review", 0));
        assertThat(result.stream().map(r -> r.getResponse().getStatus()).toList()).containsExactly(200, 200);
        assertThat(body(result.get(0))).isEqualTo(body(result.get(1)));
        assertThat(generator.questions.get()).isEqualTo(1);
    }

    @Test
    void concurrentDifferentModesHaveOneWinner() throws Exception {
        var result = together(() -> mode("first", "first", 0), () -> mode("review", "review", 0));
        assertThat(result.stream().map(r -> r.getResponse().getStatus()).toList())
                .containsExactlyInAnyOrder(200, 409);
        assertThat(generator.questions.get() + generator.summaries.get()).isEqualTo(1);
    }

    @Test
    void concurrentChatStartsCannotCreateDuplicateOpeningMessages() throws Exception {
        mode("choose", "first", 0);
        var result = together(() -> chat("left", 1), () -> chat("right", 1));
        assertThat(result.stream().map(r -> r.getResponse().getStatus()).toList())
                .containsExactlyInAnyOrder(200, 409);
        assertThat(generator.questions.get()).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM teachmi.learning_message", Long.class)).isEqualTo(1);
    }

    @Test
    void concurrentModeSelectionAndReplacementCannotAdvanceAnAbandonedSession() throws Exception {
        var results = together(() -> mode("mode", "review", 0),
                () -> mvc.perform(post("/api/v1/sessions").principal(() -> "member-1")
                        .header("Idempotency-Key", "replace").contentType("application/json")
                        .content(json.writeValueAsString(new CreateSessionRequest("m01", "c02",
                                UUID.fromString(id), 0L)))).andReturn());
        var statuses = results.stream().map(r -> r.getResponse().getStatus()).toList();
        assertThat(statuses).contains(409);
        assertThat(statuses.stream().filter(s -> s == 200 || s == 201).count()).isEqualTo(1);
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM teachmi.learning_session WHERE status='active'", Long.class))
                .isEqualTo(1);
        assertThat(jdbc.queryForObject(
                "SELECT version FROM teachmi.learning_session WHERE id=?", Long.class, UUID.fromString(id)))
                .isEqualTo(1);
    }

    @Test
    void oldCreationReceiptsRemainReadableAfterResponseExtension() throws Exception {
        String oldReceipt = """
                {"sessionId":"%s","materialId":"m01","conceptId":"c01",
                 "concept":{"name":"감가상각","scope":"정액법","sourceChunkIds":["k01"]},
                 "mode":null,"stage":"mode","status":"active","version":0,
                 "createdAt":"2026-11-05T00:00:00Z","updatedAt":"2026-11-05T00:00:00Z","resume":null}
                """.formatted(id);
        jdbc.update("UPDATE teachmi.learning_session_request SET response_body=? WHERE request_key='create'", oldReceipt);
        var replay = mvc.perform(post("/api/v1/sessions").principal(() -> "member-1")
                .header("Idempotency-Key", "create").contentType("application/json")
                .content("{\"materialId\":\"m01\",\"conceptId\":\"c01\"}")).andReturn();
        assertThat(replay.getResponse().getStatus()).isEqualTo(201);
        assertThat(body(replay).path("messages").size()).isZero();
        assertThat(body(replay).path("summary").isNull()).isTrue();
    }

    private MvcResult mode(String key, String mode, long version) throws Exception {
        return mvc.perform(put("/api/v1/sessions/" + id + "/mode").principal(() -> "member-1")
                .header("Idempotency-Key", key).contentType("application/json")
                .content(json.writeValueAsString(new ChooseModeRequest(mode, version)))).andReturn();
    }

    private MvcResult chat(String key, long version) throws Exception {
        return mvc.perform(post("/api/v1/sessions/" + id + "/chat/start").principal(() -> "member-1")
                .header("Idempotency-Key", key).contentType("application/json")
                .content(json.writeValueAsString(new StartChatRequest(version)))).andReturn();
    }

    private void assertChat(JsonNode body, long version) {
        assertThat(body.path("stage").asText()).isEqualTo("chat");
        assertThat(body.path("chatStage").asText()).isEqualTo("case");
        assertThat(body.path("resume").path("stage").asText()).isEqualTo("chat");
        assertThat(body.path("version").asLong()).isEqualTo(version);
        assertThat(body.path("messages").size()).isEqualTo(1);
        assertThat(body.path("messages").get(0).path("sequence").asLong()).isEqualTo(1);
        assertThat(body.path("messages").get(0).path("role").asText()).isEqualTo("assistant");
    }

    private JsonNode body(MvcResult result) throws Exception {
        return json.readTree(result.getResponse().getContentAsByteArray());
    }

    private List<MvcResult> together(Callable<MvcResult> left, Callable<MvcResult> right) throws Exception {
        var barrier = new CyclicBarrier(2);
        try (var pool = Executors.newFixedThreadPool(2)) {
            var a = pool.submit(() -> { barrier.await(10, TimeUnit.SECONDS); return left.call(); });
            var b = pool.submit(() -> { barrier.await(10, TimeUnit.SECONDS); return right.call(); });
            return List.of(a.get(20, TimeUnit.SECONDS), b.get(20, TimeUnit.SECONDS));
        }
    }
}
