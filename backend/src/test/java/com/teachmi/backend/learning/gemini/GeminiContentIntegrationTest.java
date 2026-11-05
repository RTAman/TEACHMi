package com.teachmi.backend.learning.gemini;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.boot.jdbc.autoconfigure.DataSourceProperties;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.context.WebApplicationContext;
import com.teachmi.backend.learning.*;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;

@SpringBootTest(classes = LearningTestApplication.class, properties = {
        "teachmi.gemini.enabled=true", "teachmi.gemini.api-key=unit-test-key",
        "teachmi.gemini.model=test-model", "spring.datasource.hikari.maximum-pool-size=2",
        "spring.datasource.hikari.minimum-idle=0"
})
@Import({GeminiConfiguration.class, GeminiContentIntegrationTest.Sources.class})
class GeminiContentIntegrationTest {
    @TestConfiguration(proxyBeanMethods = false)
    static class Sources {
        @Bean GeminiFixtures.Sources ownedSources() { return new GeminiFixtures.Sources(); }
    }

    @Autowired WebApplicationContext context;
    @Autowired JdbcTemplate jdbc;
    @Autowired ObjectMapper json;
    @Autowired GeminiContentGenerator generator;
    @Autowired GeminiSettings settings;
    @Autowired GeminiLedger ledger;
    @Autowired GeminiFixtures.Sources sources;
    @Autowired LearningSessionService sessions;
    @Autowired PlatformTransactionManager transactionManager;
    @Autowired DataSourceProperties database;
    @MockitoBean GeminiTransport transport;
    MockMvc mvc;
    String id;

    @BeforeEach
    void setup() throws Exception {
        jdbc.execute("TRUNCATE teachmi.learning_turn, teachmi.learning_support_event, teachmi.learning_ai_call, teachmi.learning_ai_generation, "
                + "teachmi.learning_session_request, teachmi.learning_message, teachmi.learning_session");
        sources.override = null;
        mvc = MockMvcBuilders.webAppContextSetup(context).build();
        doAnswer(call -> {
            var request = json.readTree(call.getArgument(0, String.class));
            var data = json.readTree(request.path("contents").get(0).path("parts").get(0).path("text").asText());
            assertThat(data.path("lectureExcerpts").get(0).path("text").asText()).isEqualTo(GeminiFixtures.SOURCE);
            if (data.path("operation").asText().equals("dialogue")) {
                assertThat(data.path("dialogue").path("targetQuestion").path("messageId").isString()).isTrue();
                return GeminiFixtures.dialogueResponse(json, data.path("dialogue").path("kind").asText().equals("answer"));
            }
            return GeminiFixtures.response(json, data.path("operation").asText().equals("summary"));
        }).when(transport).generate(anyString());
        var created = mvc.perform(post("/api/v1/sessions").principal(() -> "u1")
                .header("Idempotency-Key", "create").contentType("application/json")
                .content("{\"materialId\":\"m01\",\"conceptId\":\"c01\"}")).andReturn();
        assertThat(created.getResponse().getStatus()).isEqualTo(201);
        id = json.readTree(created.getResponse().getContentAsByteArray()).path("sessionId").asText();
    }

    @Test
    void realAdapterValidatesAndPersistsSummaryAndStudentQuestion() throws Exception {
        var prepared = mode("first", "summary");
        assertThat(prepared.path("summary").path("text").asText()).contains("사용 기간");
        var started = mvc.perform(post("/api/v1/sessions/" + id + "/chat/start").principal(() -> "u1")
                .header("Idempotency-Key", "question").contentType("application/json")
                .content("{\"expectedVersion\":1}")).andReturn();
        assertThat(started.getResponse().getStatus()).isEqualTo(200);
        var result = json.readTree(started.getResponse().getContentAsByteArray());
        assertThat(result.path("messages").get(0).path("text").asText()).contains("처리할까요");
        var loaded = mvc.perform(get("/api/v1/sessions/" + id).principal(() -> "u1")).andReturn();
        assertThat(json.readTree(loaded.getResponse().getContentAsByteArray())).isEqualTo(result);
        verify(transport, times(2)).generate(anyString());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM teachmi.learning_ai_generation WHERE state='succeeded'",
                Long.class)).isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM teachmi.learning_ai_call", Long.class)).isEqualTo(2);
    }

    @Test
    void reviewUsesStudentCaseQuestionAndReceiptReplayDoesNotCallGemini() throws Exception {
        var result = mode("review", "review");
        assertThat(result.path("summary").isNull()).isTrue();
        assertThat(result.path("stage").asText()).isEqualTo("chat");
        assertThat(result.path("messages").size()).isEqualTo(1);
        assertThat(mode("review", "review")).isEqualTo(result);
        verify(transport).generate(anyString());
    }

    @Test
    void geminiReplyUsesSavedUserInputAndAdvancesTheServerStage() throws Exception {
        mode("review", "review");
        var reply = turn("answer", "answer", "600만 원을 3년에 걸쳐 매년 200만 원씩 배분합니다.");
        assertThat(reply.getResponse().getStatus()).isEqualTo(200);
        var state = json.readTree(reply.getResponse().getContentAsByteArray());
        assertThat(state.path("chatStage").asText()).isEqualTo("concept");
        assertThat(state.path("initialAnswerId")).isEqualTo(state.path("messages").get(1).path("messageId"));
        verify(transport, times(2)).generate(anyString());
    }

    @ParameterizedTest
    @ValueSource(strings = {"hint", "example", "dont_know"})
    void geminiHelpStaysOnTheStageAndIsRestorable(String kind) throws Exception {
        mode("review", "review");
        var reply = turn(kind, kind, null);
        assertThat(reply.getResponse().getStatus()).isEqualTo(200);
        var state = json.readTree(reply.getResponse().getContentAsByteArray());
        assertThat(state.path("chatStage").asText()).isEqualTo("case");
        assertThat(state.path("supportEvents").size()).isEqualTo(1);
        assertThat(state.path("messages").size()).isEqualTo(3);
        assertThat(json.readTree(turn(kind, kind, null).getResponse().getContentAsByteArray())).isEqualTo(state);
        verify(transport, times(2)).generate(anyString());
    }

    @Test
    void geminiCannotAdvanceAStageThroughHelpAndTheInvalidResultIsNotCachedAsSuccess() throws Exception {
        mode("review", "review");
        doReturn(GeminiFixtures.dialogueResponse(json, true)).when(transport).generate(anyString());
        assertThat(turn("help", "hint", null).getResponse().getStatus()).isEqualTo(502);
        doReturn(GeminiFixtures.dialogueResponse(json, false)).when(transport).generate(anyString());
        assertThat(turn("help", "hint", null).getResponse().getStatus()).isEqualTo(200);
        assertThat(sessions.get("u1", UUID.fromString(id)).chatStage()).isEqualTo("case");
        verify(transport, times(3)).generate(anyString());
    }

    @Test
    void uncertainGeminiReplyPreservesTheAnswerAndDoesNotGenerateAgainOnRetry() throws Exception {
        mode("review", "review");
        doThrow(new GeminiCallFailure(true, 503, "AI_GENERATION_UNCERTAIN", 0))
                .when(transport).generate(anyString());
        assertThat(turn("answer", "answer", "저장할 답변").getResponse().getStatus()).isEqualTo(503);
        var pending = sessions.get("u1", UUID.fromString(id));
        assertThat(pending.messages()).hasSize(2);
        assertThat(pending.pendingTurn().state()).isEqualTo("failed");
        assertThat(turn("answer", "answer", "저장할 답변").getResponse().getStatus()).isEqualTo(503);
        verify(transport, times(2)).generate(anyString());
    }

    private org.springframework.test.web.servlet.MvcResult turn(String key, String kind, String text) throws Exception {
        return mvc.perform(post("/api/v1/sessions/" + id + "/turns").principal(() -> "u1")
                .header("Idempotency-Key", key).contentType("application/json")
                .content(json.writeValueAsString(new TurnRequest(kind, text, 1L)))).andReturn();
    }

    @Test
    void aSessionRollbackReusesTheDurablySavedAiResult() throws Exception {
        var transaction = new TransactionTemplate(transactionManager);
        transaction.executeWithoutResult(status -> {
            sessions.chooseMode("u1", UUID.fromString(id), "retry", new ChooseModeRequest("first", 0L));
            status.setRollbackOnly();
        });
        assertThat(sessions.get("u1", UUID.fromString(id)).stage()).isEqualTo("mode");
        mode("first", "retry");
        verify(transport).generate(anyString());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM teachmi.learning_ai_call", Long.class)).isEqualTo(1);
    }

    @ParameterizedTest
    @ValueSource(strings = {"not-json", "blocked", "truncated", "unknown-id", "bad-quote", "missing-evidence", "wrong-type", "extra"})
    void malformedOrUngroundedResponsesDoNotChangeTheSession(String variant) throws Exception {
        String valid = GeminiFixtures.response(json, true);
        String response = switch (variant) {
            case "not-json" -> "not json";
            case "blocked" -> "{\"promptFeedback\":{\"blockReason\":\"SAFETY\"}}";
            case "truncated" -> valid.replace("STOP", "MAX_TOKENS");
            case "unknown-id" -> valid.replace("k01", "another-users-source");
            case "bad-quote" -> valid.replace(GeminiFixtures.QUOTE, "this quote is absent");
            case "missing-evidence" -> GeminiFixtures.envelope(json, "{\"text\":\"x\",\"sourceChunkIds\":[\"k01\"]}", "STOP");
            case "wrong-type" -> GeminiFixtures.envelope(json,
                    "{\"text\":123,\"examples\":[],\"keyTerms\":[],\"reflectionPrompt\":\"x\",\"sourceChunkIds\":[],\"evidence\":[]}", "STOP");
            default -> GeminiFixtures.envelope(json,
                    "{\"text\":\"x\",\"examples\":[],\"keyTerms\":[],\"reflectionPrompt\":\"x\",\"sourceChunkIds\":[],\"evidence\":[],\"unexpected\":true}", "STOP");
        };
        doReturn(response).when(transport).generate(anyString());
        var failed = mvc.perform(put("/api/v1/sessions/" + id + "/mode").principal(() -> "u1")
                .header("Idempotency-Key", "invalid").contentType("application/json")
                .content("{\"mode\":\"first\",\"expectedVersion\":0}")).andReturn();
        assertThat(failed.getResponse().getStatus()).isEqualTo(502);
        assertThat(sessions.get("u1", UUID.fromString(id)).version()).isZero();
        assertThat(sessions.get("u1", UUID.fromString(id)).summary()).isNull();
    }

    @Test
    void invalidResultsCanBeRetriedOnceButAttemptsAreBounded() {
        doReturn("invalid").when(transport).generate(anyString());
        var content = GeminiFixtures.context("u1");
        assertThatThrownBy(() -> generator.summarize(content)).isInstanceOf(LearningException.class);
        assertThatThrownBy(() -> generator.summarize(content)).isInstanceOf(LearningException.class);
        assertThatThrownBy(() -> generator.summarize(content)).hasMessageContaining("시도 한도");
        verify(transport, times(2)).generate(anyString());
    }

    @Test
    void timeoutDoesNotAutomaticallyRepeatAnAmbiguouslyBilledRequest() {
        doThrow(new GeminiCallFailure(true, 503, "AI_GENERATION_UNCERTAIN", 0))
                .when(transport).generate(anyString());
        var content = GeminiFixtures.context("u1");
        assertThatThrownBy(() -> generator.summarize(content)).isInstanceOf(LearningException.class);
        assertThatThrownBy(() -> generator.summarize(content)).hasMessageContaining("자동 재호출");
        verify(transport).generate(anyString());
    }

    @Test
    void rateLimitResponseIsRecordedAndRespectedOnRetry() {
        doThrow(new GeminiCallFailure(false, 429, "GEMINI_RATE_LIMITED", 60))
                .when(transport).generate(anyString());
        var content = GeminiFixtures.context("u1");
        assertThatThrownBy(() -> generator.summarize(content)).isInstanceOf(LearningException.class);
        assertThatThrownBy(() -> generator.summarize(content)).hasMessageContaining("잠시 후");
        verify(transport).generate(anyString());
    }

    @Test
    void missingAConnectionOrWrongOwnershipNeverSendsLectureText() {
        var absent = new GeminiContentGenerator(settings,
                new StaticListableBeanFactory().getBeanProvider(LearningSourceReader.class), transport, ledger, json);
        assertThatThrownBy(() -> absent.summarize(GeminiFixtures.context("u1")))
                .hasMessageContaining("A의 자료 소유권");
        sources.override = new LearningSourceReader.OwnedSource("other-user", "m01", "c01",
                List.of(new LearningSourceReader.Chunk("k01", GeminiFixtures.SOURCE)));
        assertThatThrownBy(() -> generator.summarize(GeminiFixtures.context("u1"))).isInstanceOf(LearningException.class);
        verifyNoInteractions(transport);
    }

    @Test
    void mismatchedChunksAndOverlargeSourcesNeverCallGemini() {
        sources.override = new LearningSourceReader.OwnedSource("u1", "m01", "c01",
                List.of(new LearningSourceReader.Chunk("wrong-id", GeminiFixtures.SOURCE)));
        assertThatThrownBy(() -> generator.summarize(GeminiFixtures.context("u1"))).hasMessageContaining("원문 목록");
        sources.override = new LearningSourceReader.OwnedSource("u1", "m01", "c01",
                List.of(new LearningSourceReader.Chunk("k01", "가".repeat(settings.maxSourceChars()+1))));
        assertThatThrownBy(() -> generator.summarize(GeminiFixtures.context("u1"))).hasMessageContaining("범위를 줄여");
        verifyNoInteractions(transport);
    }

    @Test
    void concurrentGenerationClaimsAllowOnlyOneUpstreamRequest() throws Exception {
        var content = GeminiFixtures.context("u1");
        var barrier = new CyclicBarrier(2);
        doAnswer(call -> {
            Thread.sleep(150);
            return GeminiFixtures.response(json, true);
        }).when(transport).generate(anyString());
        try (var executor = Executors.newFixedThreadPool(2)) {
            var a = executor.submit(() -> { barrier.await(5, TimeUnit.SECONDS); return generateOrBusy(content); });
            var b = executor.submit(() -> { barrier.await(5, TimeUnit.SECONDS); return generateOrBusy(content); });
            var results = List.of(a.get(15, TimeUnit.SECONDS), b.get(15, TimeUnit.SECONDS));
            assertThat(results).contains("success");
            verify(transport).generate(anyString());
        }
    }

    @Test
    void sameMemberRetriesDoNotStarveTheLedgerWhenTheSessionPoolIsFull() throws Exception {
        var barrier = new CyclicBarrier(2);
        doAnswer(call -> {
            Thread.sleep(150);
            return GeminiFixtures.response(json, true);
        }).when(transport).generate(anyString());
        try (var executor = Executors.newFixedThreadPool(2)) {
            var a = executor.submit(() -> { barrier.await(5, TimeUnit.SECONDS); return mode("first", "same"); });
            var b = executor.submit(() -> { barrier.await(5, TimeUnit.SECONDS); return mode("first", "same"); });
            assertThat(a.get(15, TimeUnit.SECONDS)).isEqualTo(b.get(15, TimeUnit.SECONDS));
            verify(transport).generate(anyString());
        }
    }

    @Test
    void ledgerEnforcesGlobalMinuteDailyAndMemberBudgets() {
        for (String budget : List.of("minute", "daily", "member")) {
            jdbc.execute("TRUNCATE teachmi.learning_ai_call, teachmi.learning_ai_generation");
            var limit = new GeminiSettings("test", "test-model", settings.connectTimeout(), settings.requestTimeout(),
                    budget.equals("minute") ? 1 : 100,
                    budget.equals("daily") ? 1 : 100,
                    budget.equals("member") ? 1 : 100, 2, 16000, 2048);
            try (var scoped = new GeminiLedger(database, limit)) {
                scoped.claim("first-" + budget, GeminiFixtures.context("u1"), 500);
                assertThatThrownBy(() -> scoped.claim("second-" + budget,
                        GeminiFixtures.context(budget.equals("member") ? "u1" : "u2"), 500))
                        .hasMessageContaining("요청 한도");
            }
        }
    }

    @Test
    void expiredLeasesAreNeverBlindlyTakenOver() {
        var content = GeminiFixtures.context("u1");
        ledger.claim("expired", content, 500);
        jdbc.update("UPDATE teachmi.learning_ai_generation SET lease_until=now()-interval '1 second' WHERE generation_key='expired'");
        assertThatThrownBy(() -> ledger.claim("expired", content, 500)).hasMessageContaining("자동 재호출");
        verifyNoInteractions(transport);
    }

    @Test
    void configurationNeverPrintsCredentials() {
        assertThat(settings.toString()).doesNotContain("unit-test-key");
    }

    private String generateOrBusy(LearningContentGenerator.Context content) {
        try { generator.summarize(content); return "success"; }
        catch (LearningException ex) { return "busy"; }
    }

    private JsonNode mode(String mode, String key) throws Exception {
        var result = mvc.perform(put("/api/v1/sessions/" + id + "/mode").principal(() -> "u1")
                .header("Idempotency-Key", key).contentType("application/json")
                .content(json.writeValueAsString(Map.of("mode", mode, "expectedVersion", 0)))).andReturn();
        assertThat(result.getResponse().getStatus()).isEqualTo(200);
        return json.readTree(result.getResponse().getContentAsByteArray());
    }
}
