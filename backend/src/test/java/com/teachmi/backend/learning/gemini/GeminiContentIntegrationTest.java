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
        // Golden v1 requests contain 2048; developer .env/output settings must not change their cache keys.
        "teachmi.gemini.max-output-tokens=2048",
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
    @MockitoBean ReteachProblemSource problems;
    MockMvc mvc;
    String id;

    @BeforeEach
    void setup() throws Exception {
        jdbc.execute("TRUNCATE teachmi.learning_reteach, teachmi.learning_turn, teachmi.learning_support_event, teachmi.learning_ai_call, teachmi.learning_ai_generation, "
                + "teachmi.learning_session_request, teachmi.learning_message, teachmi.learning_session");
        sources.override = null;
        doAnswer(call -> new ReteachProblemSource.Target(call.getArgument(0), call.getArgument(1),
                call.getArgument(2), call.getArgument(3), "wrong", "정액법에서 사용 기간에 배분하는 이유"))
                .when(problems).loadWrongAttempt(anyString(), org.mockito.ArgumentMatchers.any(UUID.class), anyString(), anyString());
        mvc = MockMvcBuilders.webAppContextSetup(context).build();
        doAnswer(call -> {
            var request = json.readTree(call.getArgument(0, String.class));
            var data = json.readTree(request.path("contents").get(0).path("parts").get(0).path("text").asText());
            assertThat(data.path("lectureExcerpts").get(0).path("text").asText()).isEqualTo(GeminiFixtures.SOURCE);
            if (data.path("operation").asText().equals("reteach_question")) {
                assertThat(data.path("reteach").size()).isEqualTo(2);
                assertThat(data.path("reteach").has("problemId")).isFalse();
                assertThat(data.path("reteach").has("solution")).isFalse();
                assertThat(data.path("reteach").path("focus").asText()).contains("배분");
            }
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

    @Test void historicalDialogueAndReteachSuccessesAreRestoredWithoutAnotherReservation() throws Exception {
        var content=LegacyDialogueRequestsTest.content();
        for(String kind:List.of("dialogue","reteach")) {
            String request=java.nio.file.Files.readString(java.nio.file.Path.of("src/test/resources/gemini-legacy/"+kind+"-v1.json"));
            assertThat(settings.maxOutputTokens()).as("Test output cap must match the saved legacy request")
                    .isEqualTo(json.readTree(request).path("generationConfig").path("maxOutputTokens").asInt());
            String key=java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(
                    ("teachmi-"+kind+"-v1\n"+settings.model()+"\n"+request).getBytes(java.nio.charset.StandardCharsets.UTF_8)));
            ledger.claim(key,content,request.getBytes(java.nio.charset.StandardCharsets.UTF_8).length);
            var envelope=json.readTree(kind.equals("dialogue")?GeminiFixtures.dialogueResponse(json,true):GeminiFixtures.response(json,false));
            ledger.succeed(key,envelope.path("candidates").get(0).path("content").path("parts").get(0).path("text").asText());
        }
        var before=jdbc.queryForList("SELECT * FROM teachmi.learning_ai_generation ORDER BY generation_key");
        assertThat(generator.reply(LegacyDialogueRequestsTest.dialogue()).accepted()).isTrue();
        assertThat(generator.opening(LegacyDialogueRequestsTest.reteach()).sourceChunkIds()).containsExactly("k01");
        assertThat(jdbc.queryForList("SELECT * FROM teachmi.learning_ai_generation ORDER BY generation_key")).isEqualTo(before);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM teachmi.learning_ai_call",Long.class)).isEqualTo(2);
        verifyNoInteractions(transport);
    }

    @Test void v2SuccessesAreRestoredByV3WithoutQuotaReservationOrRewritingRows() throws Exception {
        var content=LegacyDialogueRequestsTest.content();
        for(String kind:List.of("dialogue","reteach")) {
            String request=java.nio.file.Files.readString(java.nio.file.Path.of("src/test/resources/gemini-legacy/"+kind+"-v2.json"));
            String key=java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(
                    ("teachmi-"+kind+"-v2\n"+settings.model()+"\n"+request).getBytes(java.nio.charset.StandardCharsets.UTF_8)));
            ledger.claim(key,content,request.getBytes(java.nio.charset.StandardCharsets.UTF_8).length);
            var envelope=json.readTree(kind.equals("dialogue")?GeminiFixtures.dialogueResponse(json,true):GeminiFixtures.response(json,false));
            ledger.succeed(key,envelope.path("candidates").get(0).path("content").path("parts").get(0).path("text").asText());
        }
        var before=jdbc.queryForList("SELECT * FROM teachmi.learning_ai_generation ORDER BY generation_key");
        assertThat(generator.reply(LegacyDialogueRequestsTest.dialogue()).accepted()).isTrue();
        assertThat(generator.opening(LegacyDialogueRequestsTest.reteach()).sourceChunkIds()).containsExactly("k01");
        assertThat(jdbc.queryForList("SELECT * FROM teachmi.learning_ai_generation ORDER BY generation_key")).isEqualTo(before);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM teachmi.learning_ai_call",Long.class)).isEqualTo(2);
        verifyNoInteractions(transport);
    }

    @Test void v4SuccessesSurviveTheStagePolicyUpgradeWithoutAnotherCallOrPayloadRewrite() throws Exception {
        var content=LegacyDialogueRequestsTest.content();
        for(String kind:List.of("dialogue","reteach")) {
            String request=java.nio.file.Files.readString(java.nio.file.Path.of("src/test/resources/gemini-legacy/"+kind+"-v4.json"));
            String key=historicalKey("teachmi-"+kind+"-v4",request);
            ledger.claim(key,content,request.getBytes(java.nio.charset.StandardCharsets.UTF_8).length);
            var envelope=json.readTree(kind.equals("dialogue")?GeminiFixtures.dialogueResponse(json,true):GeminiFixtures.response(json,false));
            ledger.succeed(key,envelope.path("candidates").get(0).path("content").path("parts").get(0).path("text").asText());
        }
        var before=jdbc.queryForList("SELECT * FROM teachmi.learning_ai_generation ORDER BY generation_key");
        assertThat(generator.reply(LegacyDialogueRequestsTest.dialogue()).accepted()).isTrue();
        assertThat(generator.opening(LegacyDialogueRequestsTest.reteach()).sourceChunkIds()).containsExactly("k01");
        assertThat(jdbc.queryForList("SELECT * FROM teachmi.learning_ai_generation ORDER BY generation_key")).isEqualTo(before);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM teachmi.learning_ai_call",Long.class)).isEqualTo(2);
        verifyNoInteractions(transport);
    }

    @ParameterizedTest @ValueSource(strings={"uncertain","expired_running"})
    void aV4UnresolvedReceiptBlocksV5WithoutChangingItsRowOrCallingTheProvider(String state) throws Exception {
        var content=LegacyDialogueRequestsTest.content();
        String request=java.nio.file.Files.readString(java.nio.file.Path.of("src/test/resources/gemini-legacy/dialogue-v4.json"));
        String key=historicalKey("teachmi-dialogue-v4",request);
        ledger.claim(key,content,request.getBytes(java.nio.charset.StandardCharsets.UTF_8).length);
        if(state.equals("uncertain")) ledger.fail(key,true,0,"TRANSPORT_REQUEST_TIMEOUT");
        else jdbc.update("UPDATE teachmi.learning_ai_generation SET lease_until=now()-interval '1 second' WHERE generation_key=?",key);
        var before=jdbc.queryForList("SELECT * FROM teachmi.learning_ai_generation ORDER BY generation_key");
        var failure=catchThrowableOfType(() -> generator.reply(LegacyDialogueRequestsTest.dialogue()),LearningException.class);
        assertThat(failure.code()).isEqualTo("AI_GENERATION_UNCERTAIN");
        assertThat(failure.recovery().action()).isEqualTo("review_required");
        assertThat(failure.recovery().retryAllowed()).isFalse();
        assertThat(jdbc.queryForList("SELECT * FROM teachmi.learning_ai_generation ORDER BY generation_key")).isEqualTo(before);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM teachmi.learning_ai_call",Long.class)).isEqualTo(1);
        verifyNoInteractions(transport);
    }

    @Test void reasonAndWrapupKeepTheUserInControlAndOnlyFinalAcceptanceUnlocksProblems() throws Exception {
        mode("review","review");
        var policies=new java.util.ArrayList<JsonNode>();
        doAnswer(call -> {
            var request=json.readTree(call.getArgument(0,String.class));
            var input=json.readTree(request.path("contents").get(0).path("parts").get(0).path("text").asText());
            assertThat(input.path("promptVersion").asText()).isEqualTo("teachmi-dialogue-v5");
            String stage=input.path("dialogue").path("chatStage").asText();
            String kind=input.path("dialogue").path("kind").asText();
            var policy=input.path("responsePolicy");policies.add(policy);
            assertThat(policy.path("currentStage").asText()).isEqualTo(stage);
            boolean accepted=kind.equals("answer") && !input.path("dialogue").path("text").asText().equals("불충분");
            String text=!accepted?"근거를 보충해서 설명해 줄래요?":switch(stage) {
                case "case" -> "감가상각의 의미를 설명해 줄래요?";
                case "concept" -> "기간에 나누는 이유와 조건을 설명해 줄래요?";
                case "reason" -> "의미와 이유를 연결해 직접 마무리 설명을 해 줄래요?";
                default -> "설명을 이해했어요. 이제 응용문제를 풀어 보세요.";
            };
            return GeminiFixtures.envelope(json,json.writeValueAsString(Map.of("text",text,"accepted",accepted,
                    "sourceChunkIds",List.of("k01"),"evidence",List.of(Map.of("sourceChunkId","k01","quote",GeminiFixtures.QUOTE)))),"STOP");
        }).when(transport).generate(anyString());
        currentTurn("case","answer","사용 기간에 배분합니다.");
        currentTurn("concept","answer","원가를 비용으로 배분하는 것입니다.");
        var rejectedReason=currentTurn("reason-rejected","answer","불충분");
        assertThat(rejectedReason.path("chatStage").asText()).isEqualTo("reason");
        var reason=currentTurn("reason","answer","기간의 수익과 비용을 대응시킵니다.");
        assertThat(reason.path("chatStage").asText()).isEqualTo("wrapup");
        assertThat(reason.path("stage").asText()).isEqualTo("chat");
        assertThat(reason.path("finalExplanationId").isNull()).isTrue();
        assertThat(reason.path("messages").get(reason.path("messages").size()-1).path("text").asText()).contains("직접 마무리 설명");
        assertThat(reason.path("messages").get(reason.path("messages").size()-1).path("text").asText()).doesNotContain("이제 응용문제를 풀어 보세요.");
        var helped=currentTurn("wrapup-help","hint",null);
        assertThat(helped.path("chatStage").asText()).isEqualTo("wrapup");
        assertThat(helped.path("finalExplanationId").isNull()).isTrue();
        assertThat(helped.path("messages").get(helped.path("messages").size()-1).path("text").asText()).doesNotContain("이제 응용문제를 풀어 보세요.");
        var rejectedWrapup=currentTurn("wrapup-rejected","answer","불충분");
        assertThat(rejectedWrapup.path("stage").asText()).isEqualTo("chat");
        assertThat(rejectedWrapup.path("finalExplanationId").isNull()).isTrue();
        assertThat(rejectedWrapup.path("messages").get(rejectedWrapup.path("messages").size()-1).path("text").asText()).doesNotContain("이제 응용문제를 풀어 보세요.");
        String explanation="원가를 사용 기간에 나누어 비용으로 배분하고 기간별 수익에 대응시킵니다.";
        long version=sessions.get("u1",UUID.fromString(id)).version();
        var result=currentTurn("wrapup-final","answer",explanation);
        assertThat(result.path("stage").asText()).isEqualTo("problem");
        assertThat(result.path("status").asText()).isEqualTo("active");
        var user=result.path("messages").get(result.path("messages").size()-2);
        assertThat(user.path("text").asText()).isEqualTo(explanation);
        assertThat(result.path("finalExplanationId")).isEqualTo(user.path("messageId"));
        var loaded=json.readTree(mvc.perform(get("/api/v1/sessions/"+id).principal(() -> "u1")).andReturn().getResponse().getContentAsByteArray());
        assertThat(loaded).isEqualTo(result);
        var rows=jdbc.queryForList("SELECT * FROM teachmi.learning_ai_generation ORDER BY generation_key");
        var replay=mvc.perform(post("/api/v1/sessions/"+id+"/turns").principal(() -> "u1").header("Idempotency-Key","wrapup-final")
                .contentType("application/json").content(json.writeValueAsString(new TurnRequest("answer",explanation,version)))).andReturn();
        assertThat(replay.getResponse().getStatus()).isEqualTo(200);
        assertThat(json.readTree(replay.getResponse().getContentAsByteArray())).isEqualTo(result);
        assertThat(jdbc.queryForList("SELECT * FROM teachmi.learning_ai_generation ORDER BY generation_key")).isEqualTo(rows);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM teachmi.learning_ai_call",Long.class)).isEqualTo(8);
        assertThat(policies).hasSize(7);
        assertThat(policies.get(3).path("onAccepted").path("action").asText()).isEqualTo("REQUEST_USER_FINAL_EXPLANATION");
        assertThat(policies.get(3).path("onAccepted").path("dialogueComplete").asBoolean()).isFalse();
        assertThat(policies.get(6).path("onAccepted").path("action").asText()).isEqualTo("ACKNOWLEDGE_DIALOGUE_AND_INVITE_PROBLEMS");
        verify(transport,times(8)).generate(anyString());
    }

    private String historicalKey(String version,String request) throws Exception {
        return java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(
                (version+"\n"+settings.model()+"\n"+request).getBytes(java.nio.charset.StandardCharsets.UTF_8)));
    }

    private JsonNode currentTurn(String key,String kind,String text) throws Exception {
        var body=new TurnRequest(kind,text,sessions.get("u1",UUID.fromString(id)).version());
        var result=mvc.perform(post("/api/v1/sessions/"+id+"/turns").principal(() -> "u1").header("Idempotency-Key",key)
                .contentType("application/json").content(json.writeValueAsString(body))).andReturn();
        assertThat(result.getResponse().getStatus()).isEqualTo(200);
        return json.readTree(result.getResponse().getContentAsByteArray());
    }

    @ParameterizedTest @ValueSource(strings={"설명을 마쳤어요.","이제 응용문제를 풀어 보세요."})
    void wrapupGuidanceIsDeliveredOnceWithoutRewritingTheRawModelCache(String modelText) throws Exception {
        mode("review","review");
        for(String stage:List.of("case","concept","reason")) currentTurn("guide-"+stage,"answer","원가 배분의 의미와 이유를 설명합니다.");
        var requestJson=new java.util.concurrent.atomic.AtomicReference<String>();
        doAnswer(call -> {
            requestJson.set(call.getArgument(0,String.class));
            return GeminiFixtures.envelope(json,json.writeValueAsString(Map.of("text",modelText,"accepted",true,
                    "sourceChunkIds",List.of("k01"),"evidence",List.of(Map.of("sourceChunkId","k01","quote",GeminiFixtures.QUOTE)))),"STOP");
        }).when(transport).generate(anyString());
        long version=sessions.get("u1",UUID.fromString(id)).version();
        String explanation="사용 기간에 원가를 배분하여 수익과 비용을 대응시킵니다.";
        var result=currentTurn("guide-final","answer",explanation);
        var assistant=result.path("messages").get(result.path("messages").size()-1);
        String text=assistant.path("text").asText();
        assertThat(text).startsWith(modelText).endsWith("이제 응용문제를 풀어 보세요.");
        assertThat(text.split(java.util.regex.Pattern.quote("이제 응용문제를 풀어 보세요."),-1)).hasSize(2);
        assertThat(assistant.path("kind").asText()).isEqualTo("learning_complete");
        assertThat(assistant.path("sourceChunkIds").get(0).asText()).isEqualTo("k01");
        assertThat(result.path("messages").size()).isEqualTo(9);
        assertThat(result.path("stage").asText()).isEqualTo("problem");
        assertThat(result.path("status").asText()).isEqualTo("active");
        assertThat(result.path("availableActions").get(0).asText()).isEqualTo("start_problems");
        assertThat(result.path("finalExplanationId")).isEqualTo(result.path("messages").get(7).path("messageId"));
        assertThat(result.path("messages").get(7).path("text").asText()).isEqualTo(explanation);
        String key=historicalKey("teachmi-dialogue-v5",requestJson.get());
        String payload=jdbc.queryForObject("SELECT content_json FROM teachmi.learning_ai_generation WHERE generation_key=?",String.class,key);
        assertThat(json.readTree(payload).path("text").asText()).isEqualTo(modelText);
        var receipts=jdbc.queryForList("SELECT * FROM teachmi.learning_ai_generation ORDER BY generation_key");
        var calls=jdbc.queryForList("SELECT * FROM teachmi.learning_ai_call ORDER BY id");
        assertThat(json.readTree(mvc.perform(get("/api/v1/sessions/"+id).principal(() -> "u1")).andReturn().getResponse().getContentAsByteArray())).isEqualTo(result);
        var replay=mvc.perform(post("/api/v1/sessions/"+id+"/turns").principal(() -> "u1").header("Idempotency-Key","guide-final")
                .contentType("application/json").content(json.writeValueAsString(new TurnRequest("answer",explanation,version)))).andReturn();
        assertThat(replay.getResponse().getStatus()).isEqualTo(200);
        assertThat(json.readTree(replay.getResponse().getContentAsByteArray())).isEqualTo(result);
        assertThat(jdbc.queryForList("SELECT * FROM teachmi.learning_ai_generation ORDER BY generation_key")).isEqualTo(receipts);
        assertThat(jdbc.queryForList("SELECT * FROM teachmi.learning_ai_call ORDER BY id")).isEqualTo(calls);
        verify(transport,times(5)).generate(anyString());
    }

    @Test void repeatedCacheReadsReturnTheUnmodifiedModelTextWithoutNavigationOrAnotherReservation() {
        String modelText="설명을 마쳤어요.";
        doReturn(GeminiFixtures.envelope(json,json.writeValueAsString(Map.of("text",modelText,"accepted",true,
                "sourceChunkIds",List.of("k01"),"evidence",List.of(Map.of("sourceChunkId","k01","quote",GeminiFixtures.QUOTE)))),"STOP"))
                .when(transport).generate(anyString());
        var context=LegacyDialogueRequestsTest.dialogue();
        assertThat(generator.reply(context).text()).isEqualTo(modelText);
        var rows=jdbc.queryForList("SELECT * FROM teachmi.learning_ai_generation ORDER BY generation_key");
        assertThat(generator.reply(context).text()).isEqualTo(modelText);
        assertThat(jdbc.queryForList("SELECT * FROM teachmi.learning_ai_generation ORDER BY generation_key")).isEqualTo(rows);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM teachmi.learning_ai_call",Long.class)).isEqualTo(1);
        verify(transport).generate(anyString());
    }

    @ParameterizedTest @ValueSource(strings={"bad_quote","uncertain"})
    void failedWrapupCannotAcquireCompletionGuidanceOrAdvance(String failure) throws Exception {
        mode("review","review");
        for(String stage:List.of("case","concept","reason")) currentTurn("held-"+stage,"answer","원가 배분의 의미와 이유를 설명합니다.");
        if(failure.equals("uncertain")) doThrow(new GeminiCallFailure(true,503,"AI_GENERATION_UNCERTAIN",0,"TRANSPORT_REQUEST_TIMEOUT"))
                .when(transport).generate(anyString());
        else doReturn(GeminiFixtures.dialogueResponse(json,true).replace(GeminiFixtures.QUOTE,"원문에 없는 인용문입니다."))
                .when(transport).generate(anyString());
        long version=sessions.get("u1",UUID.fromString(id)).version();
        var response=mvc.perform(post("/api/v1/sessions/"+id+"/turns").principal(() -> "u1").header("Idempotency-Key","held-final")
                .contentType("application/json").content(json.writeValueAsString(new TurnRequest("answer","보존할 최종 설명입니다.",version)))).andReturn().getResponse();
        assertThat(response.getStatus()).isEqualTo(failure.equals("uncertain")?503:502);
        var state=sessions.get("u1",UUID.fromString(id));
        assertThat(state.stage()).isEqualTo("chat");
        assertThat(state.chatStage()).isEqualTo("wrapup");
        assertThat(state.finalExplanationId()).isNull();
        assertThat(state.messages()).hasSize(8);
        assertThat(state.messages().getLast().role()).isEqualTo("user");
        assertThat(state.messages()).noneMatch(message -> message.kind().equals("learning_complete"));
        var rows=jdbc.queryForList("SELECT * FROM teachmi.learning_ai_generation ORDER BY generation_key");
        assertThat(sessions.get("u1",UUID.fromString(id))).isEqualTo(state);
        assertThat(jdbc.queryForList("SELECT * FROM teachmi.learning_ai_generation ORDER BY generation_key")).isEqualTo(rows);
        if(failure.equals("uncertain")) {
            assertThat(state.recovery().action()).isEqualTo("review_required");
            assertThat(state.recovery().retryAllowed()).isFalse();
        }
        verify(transport,times(5)).generate(anyString());
    }

    @Test void repeatedExact55CharacterExampleStopsRetryImmediatelyAndSurvivesReconnect() throws Exception {
        mode("review","review");
        doReturn(GeminiFixtures.dialogueResponse(json,false).replace(GeminiFixtures.QUOTE,GeminiFixtures.SOURCE.substring(0,55)))
                .when(transport).generate(anyString());
        var first=turn("long-example","example",null).getResponse();
        assertThat(first.getStatus()).isEqualTo(502);
        assertThat(json.readTree(first.getContentAsByteArray()).path("recovery").path("retryAllowed").asBoolean()).isTrue();
        var second=turn("long-example","example",null).getResponse();
        assertThat(second.getStatus()).isEqualTo(502);
        var recovery=json.readTree(second.getContentAsByteArray()).path("recovery");
        assertThat(recovery.path("diagnosticCode").asText()).isEqualTo("EVIDENCE_QUOTE_TOO_LONG");
        assertThat(recovery.path("retryAllowed").asBoolean()).isFalse();
        assertThat(recovery.path("action").asText()).isEqualTo("limit_reached");
        var restored=sessions.get("u1",UUID.fromString(id));
        assertThat(restored.pendingTurn().errorCode()).isEqualTo("AI_ATTEMPT_LIMIT");
        assertThat(restored.availableActions()).containsExactly("wait");
        assertThat(restored.recovery().action()).isEqualTo("limit_reached");
        var before=jdbc.queryForList("SELECT * FROM teachmi.learning_ai_generation ORDER BY generation_key");
        assertThat(turn("long-example","example",null).getResponse().getStatus()).isEqualTo(429);
        assertThat(jdbc.queryForList("SELECT * FROM teachmi.learning_ai_generation ORDER BY generation_key")).isEqualTo(before);
        verify(transport,times(3)).generate(anyString());
    }

    @Test void historicalV3AndOpeningV1SuccessesAreUsedWithoutReservationsOrRowChanges() throws Exception {
        var content=LegacyDialogueRequestsTest.content();
        for(String kind:List.of("dialogue","reteach","question")) {
            String version=kind.equals("question")?"teachmi-content-v1":"teachmi-"+kind+"-v3";
            String file=kind.equals("question")?"question-v1":kind+"-v3";
            String request=java.nio.file.Files.readString(java.nio.file.Path.of("src/test/resources/gemini-legacy/"+file+".json"));
            String key=java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(
                    (version+"\n"+settings.model()+"\n"+request).getBytes(java.nio.charset.StandardCharsets.UTF_8)));
            ledger.claim(key,content,request.getBytes(java.nio.charset.StandardCharsets.UTF_8).length);
            var envelope=json.readTree(kind.equals("dialogue")?GeminiFixtures.dialogueResponse(json,true):GeminiFixtures.response(json,false));
            ledger.succeed(key,envelope.path("candidates").get(0).path("content").path("parts").get(0).path("text").asText());
        }
        var before=jdbc.queryForList("SELECT * FROM teachmi.learning_ai_generation ORDER BY generation_key");
        assertThat(generator.reply(LegacyDialogueRequestsTest.dialogue()).accepted()).isTrue();
        assertThat(generator.opening(LegacyDialogueRequestsTest.reteach()).sourceChunkIds()).containsExactly("k01");
        assertThat(generator.openingQuestion(content).sourceChunkIds()).containsExactly("k01");
        assertThat(jdbc.queryForList("SELECT * FROM teachmi.learning_ai_generation ORDER BY generation_key")).isEqualTo(before);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM teachmi.learning_ai_call",Long.class)).isEqualTo(3);
        verifyNoInteractions(transport);
    }

    @Test void standaloneSummaryKeepsItsV1RequestAndReadsAnotherJvmFieldOrderWithoutRegeneration() throws Exception {
        var content=LegacyDialogueRequestsTest.content();
        String request=org.springframework.test.util.ReflectionTestUtils.invokeMethod(generator,"request",content,"summary",
                List.of(new LearningSourceReader.Chunk("k01",GeminiFixtures.SOURCE)),null,null,true);
        var root=(tools.jackson.databind.node.ObjectNode)json.readTree(request);
        var part=(tools.jackson.databind.node.ObjectNode)root.path("contents").get(0).path("parts").get(0);
        var data=json.readTree(part.path("text").asText());var names=new java.util.ArrayList<>(data.properties().stream().map(Map.Entry::getKey).toList());
        java.util.Collections.rotate(names,1);var otherJvm=new java.util.LinkedHashMap<String,JsonNode>();
        for(String name:names)otherJvm.put(name,data.path(name));
        part.put("text",json.writer().without(tools.jackson.databind.SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS).writeValueAsString(otherJvm));
        String historical=json.writer().with(tools.jackson.databind.SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS).writeValueAsString(json.convertValue(root,Object.class));
        assertThat(historical).isNotEqualTo(request);
        String key=java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(
                ("teachmi-content-v1\n"+settings.model()+"\n"+historical).getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        ledger.claim(key,content,historical.length());
        var envelope=json.readTree(GeminiFixtures.response(json,true));
        ledger.succeed(key,envelope.path("candidates").get(0).path("content").path("parts").get(0).path("text").asText());
        var before=jdbc.queryForList("SELECT * FROM teachmi.learning_ai_generation");
        assertThat(generator.summarize(content).sourceChunkIds()).containsExactly("k01");
        assertThat(jdbc.queryForList("SELECT * FROM teachmi.learning_ai_generation")).isEqualTo(before);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM teachmi.learning_ai_call",Long.class)).isEqualTo(1);
        verifyNoInteractions(transport);
    }

    @ParameterizedTest @ValueSource(strings={"question","dialogue","reteach"})
    void oldUncertainReceiptsCannotBeBypassedByNewQuestionOrDialogueVersions(String kind) throws Exception {
        var content=LegacyDialogueRequestsTest.content();
        String version=kind.equals("question")?"teachmi-content-v1":"teachmi-"+kind+"-v3";
        String file=kind.equals("question")?"question-v1":kind+"-v3";
        String request=java.nio.file.Files.readString(java.nio.file.Path.of("src/test/resources/gemini-legacy/"+file+".json"));
        String key=java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(
                (version+"\n"+settings.model()+"\n"+request).getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        ledger.claim(key,content,request.length());ledger.fail(key,true,0,"TRANSPORT_REQUEST_TIMEOUT");
        var before=jdbc.queryForList("SELECT * FROM teachmi.learning_ai_generation ORDER BY generation_key");
        var failure=catchThrowableOfType(() -> {
            switch(kind) {
                case "question" -> generator.openingQuestion(content);
                case "dialogue" -> generator.reply(LegacyDialogueRequestsTest.dialogue());
                default -> generator.opening(LegacyDialogueRequestsTest.reteach());
            }
        },LearningException.class);
        assertThat(failure.code()).isEqualTo("AI_GENERATION_UNCERTAIN");
        assertThat(failure.recovery().retryAllowed()).isFalse();
        assertThat(jdbc.queryForList("SELECT * FROM teachmi.learning_ai_generation ORDER BY generation_key")).isEqualTo(before);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM teachmi.learning_ai_call",Long.class)).isEqualTo(1);
        verifyNoInteractions(transport);
    }

    @Test void openingWhitespaceMismatchIsRejectedWithoutNormalizingTheQuoteThenExplicitRetryCanRecover() throws Exception {
        doReturn(GeminiFixtures.response(json,false).replace(GeminiFixtures.QUOTE,GeminiFixtures.QUOTE.replace(" ","  ")))
                .when(transport).generate(anyString());
        var failed=mvc.perform(put("/api/v1/sessions/"+id+"/mode").principal(() -> "u1").header("Idempotency-Key","opening")
                .contentType("application/json").content("{\"mode\":\"review\",\"expectedVersion\":0}")).andReturn().getResponse();
        assertThat(failed.getStatus()).isEqualTo(502);
        assertThat(json.readTree(failed.getContentAsByteArray()).path("recovery").path("diagnosticCode").asText()).isEqualTo("EVIDENCE_QUOTE_NOT_EXACT");
        assertThat(sessions.get("u1",UUID.fromString(id)).messages()).isEmpty();
        doAnswer(call -> {
            var request=json.readTree(call.getArgument(0,String.class));
            var data=json.readTree(request.path("contents").get(0).path("parts").get(0).path("text").asText());
            assertThat(data.path("promptVersion").asText()).isEqualTo("teachmi-question-v2");
            String quote=data.path("citationCandidates").get(0).path("quote").asText();
            return GeminiFixtures.response(json,false).replace(GeminiFixtures.QUOTE,quote);
        }).when(transport).generate(anyString());
        assertThat(mode("review","opening").path("stage").asText()).isEqualTo("chat");
        verify(transport,times(2)).generate(anyString());
    }

    @Test void interactiveBudgetIsRecordedAccuratelyAndSummaryKeepsTheGlobalBudget() {
        var limits=new GeminiSettings(settings.apiKey(),settings.model(),settings.connectTimeout(),settings.requestTimeout(),10,100,20,2,16000,6144);
        var beans=new StaticListableBeanFactory();beans.addBean("sources",sources);
        try(var reserved=new GeminiLedger(database,limits)) {
            var service=new GeminiGenerationService(limits,beans.getBeanProvider(LearningSourceReader.class),transport,reserved,json);
            var bounded=new GeminiContentGenerator(service,limits,json,1024);var content=GeminiFixtures.context("budget-member");
            bounded.openingQuestion(content);bounded.summarize(content);
        }
        assertThat(jdbc.queryForList("SELECT max_output_tokens FROM teachmi.learning_ai_call ORDER BY max_output_tokens",Integer.class)).containsExactly(1024,6144);
        var requests=org.mockito.ArgumentCaptor.forClass(String.class);verify(transport,times(2)).generate(requests.capture());
        assertThat(requests.getAllValues().stream().map(request -> json.readTree(request).path("generationConfig").path("maxOutputTokens").asInt()).toList())
                .containsExactly(1024,6144);
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
        doThrow(new GeminiCallFailure(true, 503, "AI_GENERATION_UNCERTAIN", 0,"TRANSPORT_REQUEST_TIMEOUT"))
                .when(transport).generate(anyString());
        var failed=turn("answer", "answer", "저장할 답변");
        assertThat(failed.getResponse().getStatus()).isEqualTo(503);
        var recovery=json.readTree(failed.getResponse().getContentAsByteArray()).path("recovery");
        assertThat(recovery.path("diagnosticCode").asText()).isEqualTo("TRANSPORT_REQUEST_TIMEOUT");
        assertThat(recovery.path("retryAllowed").asBoolean()).isFalse();
        assertThat(recovery.path("action").asText()).isEqualTo("review_required");
        var pending = sessions.get("u1", UUID.fromString(id));
        assertThat(pending.messages()).hasSize(2);
        assertThat(pending.pendingTurn().state()).isEqualTo("failed");
        assertThat(pending.availableActions()).containsExactly("wait");
        assertThat(pending.recovery().action()).isEqualTo("review_required");
        assertThat(pending.recovery().retryAllowed()).isFalse();
        var restored=json.readTree(mvc.perform(get("/api/v1/sessions/"+id).principal(() -> "u1")).andReturn().getResponse().getContentAsByteArray());
        assertThat(restored.path("recovery").path("action").asText()).isEqualTo("review_required");
        var turnBefore=jdbc.queryForList("SELECT * FROM teachmi.learning_turn");
        var ledgerBefore=jdbc.queryForList("SELECT * FROM teachmi.learning_ai_generation ORDER BY generation_key");
        assertThat(turn("answer", "answer", "저장할 답변").getResponse().getStatus()).isEqualTo(503);
        assertThat(jdbc.queryForList("SELECT * FROM teachmi.learning_turn")).isEqualTo(turnBefore);
        assertThat(jdbc.queryForList("SELECT * FROM teachmi.learning_ai_generation ORDER BY generation_key")).isEqualTo(ledgerBefore);
        verify(transport, times(2)).generate(anyString());
    }

    @Test void aBadQuotePreservesTheAnswerAndAnExplicitBoundedRetryCanRecover() throws Exception {
        mode("review","review");
        doReturn(GeminiFixtures.dialogueResponse(json,true).replace(GeminiFixtures.QUOTE,"복사하지 않은 인용문입니다."))
                .when(transport).generate(anyString());
        var failed=turn("quote","answer","저장된 사용자 답변");
        assertThat(failed.getResponse().getStatus()).isEqualTo(502);
        var body=json.readTree(failed.getResponse().getContentAsByteArray());
        assertThat(body.path("recovery").path("diagnosticCode").asText()).isEqualTo("EVIDENCE_QUOTE_NOT_EXACT");
        assertThat(body.path("recovery").path("retryAllowed").asBoolean()).isTrue();
        assertThat(sessions.get("u1",UUID.fromString(id)).pendingTurn()).isNotNull();
        doReturn(GeminiFixtures.dialogueResponse(json,true)).when(transport).generate(anyString());
        assertThat(turn("quote","answer","저장된 사용자 답변").getResponse().getStatus()).isEqualTo(200);
        assertThat(sessions.get("u1",UUID.fromString(id)).messages().stream().filter(m -> m.role().equals("user"))).hasSize(1);
        verify(transport,times(3)).generate(anyString());
    }

    @Test void actualLocalBodyTimeoutPreservesTheAnswerAndUncertainReceiptWithoutAnotherRequest() throws Exception {
        mode("review","review");
        var server=com.sun.net.httpserver.HttpServer.create(new java.net.InetSocketAddress("127.0.0.1",0),0);
        var executor=Executors.newVirtualThreadPerTaskExecutor();var calls=new java.util.concurrent.atomic.AtomicInteger();
        server.setExecutor(executor);
        server.createContext("/",exchange -> {
            calls.incrementAndGet();exchange.getRequestBody().readAllBytes();
            try {
                exchange.sendResponseHeaders(200,2);exchange.getResponseBody().write('{');exchange.getResponseBody().flush();
                Thread.sleep(1500);exchange.getResponseBody().write('}');
            } catch(InterruptedException interrupted) {Thread.currentThread().interrupt();}
            finally {exchange.close();}
        });
        server.start();
        try {
            var timeout=new GeminiSettings("unit-test-key","test-model",java.time.Duration.ofSeconds(1),java.time.Duration.ofMillis(500),10,100,20,2,16000,2048);
            var network=new GeminiHttpTransport(timeout,java.net.URI.create("http://127.0.0.1:"+server.getAddress().getPort()+"/generate"));
            doAnswer(call -> network.generate(call.getArgument(0,String.class))).when(transport).generate(anyString());
            var failed=turn("body-timeout","answer","타임아웃 회귀용으로 저장할 설명").getResponse();
            assertThat(failed.getStatus()).isEqualTo(503);
            assertThat(json.readTree(failed.getContentAsByteArray()).path("recovery").path("diagnosticCode").asText()).isEqualTo("TRANSPORT_REQUEST_TIMEOUT");
            var before=jdbc.queryForList("SELECT * FROM teachmi.learning_ai_generation ORDER BY generation_key");
            var restored=sessions.get("u1",UUID.fromString(id));
            assertThat(restored.pendingTurn().errorCode()).isEqualTo("AI_GENERATION_UNCERTAIN");
            assertThat(restored.messages().get(1).text()).isEqualTo("타임아웃 회귀용으로 저장할 설명");
            assertThat(restored.recovery().action()).isEqualTo("review_required");
            assertThat(turn("body-timeout","answer","타임아웃 회귀용으로 저장할 설명").getResponse().getStatus()).isEqualTo(503);
            assertThat(jdbc.queryForList("SELECT * FROM teachmi.learning_ai_generation ORDER BY generation_key")).isEqualTo(before);
            assertThat(jdbc.queryForObject("SELECT count(*) FROM teachmi.learning_ai_generation WHERE state='uncertain'",Long.class)).isEqualTo(1);
            assertThat(calls.get()).isEqualTo(1);
        } finally {server.stop(0);executor.shutdownNow();}
    }

    @Test void anUncertainReteachOpeningWaitsWithoutReopeningItsClaim() throws Exception {
        mode("review","review");
        jdbc.update("UPDATE teachmi.learning_session SET stage='problem' WHERE id=?",UUID.fromString(id));
        doThrow(new GeminiCallFailure(true,503,"AI_GENERATION_UNCERTAIN",0,"UPSTREAM_HTTP_503")).when(transport).generate(anyString());
        String payload="{\"problemId\":\"p01\",\"attemptId\":\"a01\",\"expectedVersion\":1}";
        var request=post("/api/v1/sessions/"+id+"/reteach").principal(() -> "u1").header("Idempotency-Key","reteach").contentType("application/json").content(payload);
        assertThat(mvc.perform(request).andReturn().getResponse().getStatus()).isEqualTo(503);
        var before=jdbc.queryForList("SELECT * FROM teachmi.learning_reteach");
        assertThat(sessions.get("u1",UUID.fromString(id)).availableActions()).containsExactly("wait");
        assertThat(sessions.get("u1",UUID.fromString(id)).recovery().action()).isEqualTo("review_required");
        assertThat(mvc.perform(request).andReturn().getResponse().getStatus()).isEqualTo(503);
        assertThat(jdbc.queryForList("SELECT * FROM teachmi.learning_reteach")).isEqualTo(before);
        verify(transport,times(2)).generate(anyString());
    }

    @Test void attemptLimitIsTerminalOnTheFirstErrorAndAfterReconnectWithoutAnotherCall() throws Exception {
        mode("review","review");
        doReturn("invalid response").when(transport).generate(anyString());
        assertThat(turn("limited","answer","보존할 입력").getResponse().getStatus()).isEqualTo(502);
        var second=turn("limited","answer","보존할 입력").getResponse();
        assertThat(second.getStatus()).isEqualTo(502);
        assertThat(json.readTree(second.getContentAsByteArray()).path("recovery").path("action").asText()).isEqualTo("limit_reached");
        assertThat(sessions.get("u1",UUID.fromString(id)).recovery().action()).isEqualTo("limit_reached");
        var limited=turn("limited","answer","보존할 입력").getResponse();
        assertThat(limited.getStatus()).isEqualTo(429);
        var body=json.readTree(limited.getContentAsByteArray());
        assertThat(body.path("recovery").path("action").asText()).isEqualTo("limit_reached");
        assertThat(body.path("recovery").path("retryAllowed").asBoolean()).isFalse();
        var view=sessions.get("u1",UUID.fromString(id));
        assertThat(view.availableActions()).containsExactly("wait");
        assertThat(view.recovery().action()).isEqualTo("limit_reached");
        assertThat(view.messages().get(1).text()).isEqualTo("보존할 입력");
        var ledgerBefore=jdbc.queryForList("SELECT * FROM teachmi.learning_ai_generation ORDER BY generation_key");
        var turnBefore=jdbc.queryForList("SELECT * FROM teachmi.learning_turn");
        assertThat(turn("limited","answer","보존할 입력").getResponse().getStatus()).isEqualTo(429);
        assertThat(jdbc.queryForList("SELECT * FROM teachmi.learning_ai_generation ORDER BY generation_key")).isEqualTo(ledgerBefore);
        assertThat(jdbc.queryForList("SELECT * FROM teachmi.learning_turn")).isEqualTo(turnBefore);
        verify(transport,times(3)).generate(anyString());
    }

    @Test void reteachAttemptLimitRestoresALimitNoticeAndKeepsTheOpeningClaim() throws Exception {
        mode("review","review");
        jdbc.update("UPDATE teachmi.learning_session SET stage='problem' WHERE id=?",UUID.fromString(id));
        doReturn("invalid response").when(transport).generate(anyString());
        var request=post("/api/v1/sessions/"+id+"/reteach").principal(() -> "u1").header("Idempotency-Key","limited-reteach")
                .contentType("application/json").content("{\"problemId\":\"p01\",\"attemptId\":\"a01\",\"expectedVersion\":1}");
        assertThat(mvc.perform(request).andReturn().getResponse().getStatus()).isEqualTo(502);
        var second=mvc.perform(request).andReturn().getResponse();
        assertThat(second.getStatus()).isEqualTo(502);
        assertThat(json.readTree(second.getContentAsByteArray()).path("recovery").path("action").asText()).isEqualTo("limit_reached");
        assertThat(sessions.get("u1",UUID.fromString(id)).recovery().action()).isEqualTo("limit_reached");
        var limited=mvc.perform(request).andReturn().getResponse();
        assertThat(limited.getStatus()).isEqualTo(429);
        assertThat(json.readTree(limited.getContentAsByteArray()).path("recovery").path("action").asText()).isEqualTo("limit_reached");
        var view=sessions.get("u1",UUID.fromString(id));
        assertThat(view.availableActions()).containsExactly("wait");
        assertThat(view.activeReteach().errorCode()).isEqualTo("AI_ATTEMPT_LIMIT");
        assertThat(view.recovery().retryAllowed()).isFalse();
        assertThat(view.recovery().action()).isEqualTo("limit_reached");
        var before=jdbc.queryForList("SELECT * FROM teachmi.learning_reteach");
        assertThat(mvc.perform(request).andReturn().getResponse().getStatus()).isEqualTo(429);
        assertThat(jdbc.queryForList("SELECT * FROM teachmi.learning_reteach")).isEqualTo(before);
        verify(transport,times(3)).generate(anyString());
    }

    @Test void newDialogueRejectsDuplicateEvidenceEvenWhenEachQuoteIsGrounded() throws Exception {
        mode("review","review");
        var envelope=(tools.jackson.databind.node.ObjectNode)json.readTree(GeminiFixtures.dialogueResponse(json,true));
        var payload=(tools.jackson.databind.node.ObjectNode)json.readTree(envelope.path("candidates").get(0).path("content").path("parts").get(0).path("text").asText());
        ((tools.jackson.databind.node.ArrayNode)payload.get("evidence")).add(payload.get("evidence").get(0).deepCopy());
        doReturn(GeminiFixtures.envelope(json,json.writeValueAsString(payload),"STOP")).when(transport).generate(anyString());
        var result=turn("duplicates","answer","설명입니다.");
        assertThat(result.getResponse().getStatus()).isEqualTo(502);
        assertThat(json.readTree(result.getResponse().getContentAsByteArray()).path("recovery").path("diagnosticCode").asText()).isEqualTo("CITATION_CARDINALITY_INVALID");
    }

    private org.springframework.test.web.servlet.MvcResult turn(String key, String kind, String text) throws Exception {
        return mvc.perform(post("/api/v1/sessions/" + id + "/turns").principal(() -> "u1")
                .header("Idempotency-Key", key).contentType("application/json")
                .content(json.writeValueAsString(new TurnRequest(kind, text, 1L)))).andReturn();
    }

    @Test
    void geminiReteachOpeningAndHelpUseOnlyTheNewRemediationTranscript() throws Exception {
        mode("review", "review");
        jdbc.update("UPDATE teachmi.learning_session SET stage='problem' WHERE id=?", UUID.fromString(id));
        var begun = mvc.perform(post("/api/v1/sessions/" + id + "/reteach").principal(() -> "u1")
                .header("Idempotency-Key", "reteach").contentType("application/json")
                .content("{\"problemId\":\"p01\",\"attemptId\":\"a01\",\"expectedVersion\":1}")).andReturn();
        assertThat(begun.getResponse().getStatus()).isEqualTo(200);
        var prepared = json.readTree(begun.getResponse().getContentAsByteArray());
        assertThat(prepared.path("activeReteach").path("problemId").asText()).isEqualTo("p01");
        var helped = mvc.perform(post("/api/v1/sessions/" + id + "/turns").principal(() -> "u1")
                .header("Idempotency-Key", "reteach-help").contentType("application/json")
                .content(json.writeValueAsString(new TurnRequest("hint", null, prepared.path("version").asLong()))))
                .andReturn();
        assertThat(helped.getResponse().getStatus()).isEqualTo(200);
        var state = json.readTree(helped.getResponse().getContentAsByteArray());
        assertThat(state.path("supportEvents").get(0).path("reteachId"))
                .isEqualTo(prepared.path("activeReteach").path("reteachId"));
        verify(transport, times(3)).generate(anyString());
    }

    @Test
    void reteachOpeningReusesTheSavedGeminiResultAfterCChangesAndRecovers() throws Exception {
        mode("review", "review");
        jdbc.update("UPDATE teachmi.learning_session SET stage='problem' WHERE id=?", UUID.fromString(id));
        var wrong = new ReteachProblemSource.Target("u1", UUID.fromString(id), "p01", "a01", "wrong",
                "정액법에서 사용 기간에 배분하는 이유");
        var corrected = new ReteachProblemSource.Target("u1", UUID.fromString(id), "p01", "a01", "correct", wrong.focus());
        doReturn(wrong, corrected, wrong).when(problems).loadWrongAttempt(
                anyString(), org.mockito.ArgumentMatchers.any(UUID.class), anyString(), anyString());
        var request = new StartReteachRequest("p01", "a01", 1L);
        var endpoint = "/api/v1/sessions/" + id + "/reteach";
        var failed = mvc.perform(post(endpoint).principal(() -> "u1").header("Idempotency-Key", "retry-reteach")
                .contentType("application/json").content(json.writeValueAsString(request))).andReturn();
        assertThat(failed.getResponse().getStatus()).isEqualTo(409);
        var retried = mvc.perform(post(endpoint).principal(() -> "u1").header("Idempotency-Key", "retry-reteach")
                .contentType("application/json").content(json.writeValueAsString(request))).andReturn();
        assertThat(retried.getResponse().getStatus()).isEqualTo(200);
        verify(transport, times(2)).generate(anyString());
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
