package com.teachmi.backend.learning.gemini;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import com.teachmi.backend.TeachmiApplication;
import com.teachmi.backend.ai.GeminiGenerationPort;
import com.teachmi.backend.ai.GeminiGenerationPort.*;
import com.teachmi.backend.auth.AuthFilter;
import com.teachmi.backend.learning.*;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.jdbc.autoconfigure.DataSourceProperties;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.context.WebApplicationContext;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;

/** Real A ownership/raw source adapter and B/C beans; only the external HTTP boundary is replaced. */
@SpringBootTest(classes=TeachmiApplication.class,properties={
        "teachmi.gemini.enabled=true","teachmi.gemini.api-key=common-test-key","teachmi.gemini.model=common-test-model",
        "spring.datasource.hikari.maximum-pool-size=2","spring.datasource.hikari.minimum-idle=0"})
class GeminiCommonPortIntegrationTest {
    @Autowired WebApplicationContext context;
    @Autowired AuthFilter auth;
    @Autowired ObjectMapper json;
    @Autowired JdbcTemplate jdbc;
    @Autowired GeminiGenerationPort port;
    @Autowired GeminiContentGenerator legacy;
    @Autowired GeminiSettings settings;
    @Autowired GeminiLedger ledger;
    @Autowired DataSourceProperties database;
    @Autowired PlatformTransactionManager transactions;
    @Autowired LearningCommands commands;
    @Autowired LearningSessionService sessions;
    @MockitoBean GeminiTransport transport;
    Scope scope;
    LearningContentGenerator.Context bContext;
    Cookie cookie;
    String csrf;
    MockMvc mvc;

    @BeforeEach void setup() throws Exception {
        jdbc.execute("TRUNCATE teachmi.learning_ai_call,teachmi.learning_ai_generation");
        mvc=MockMvcBuilders.webAppContextSetup(context).addFilters(auth).build();
        var credentials=Map.of("email",UUID.randomUUID()+"@example.test","password","correct-horse");
        assertThat(mvc.perform(post("/api/v1/auth/signup").contentType("application/json").content(json.writeValueAsString(credentials)))
                .andReturn().getResponse().getStatus()).isEqualTo(201);
        var login=mvc.perform(post("/api/v1/auth/login").contentType("application/json").content(json.writeValueAsString(credentials)))
                .andReturn().getResponse();
        var member=json.readTree(login.getContentAsByteArray()).path("id").asText();
        cookie=login.getCookie("TEACHMI_SESSION");csrf=json.readTree(login.getContentAsByteArray()).path("csrfToken").asText();
        var material=mvc.perform(post("/api/v1/materials").cookie(cookie).header("X-CSRF-Token",csrf)
                .header("Idempotency-Key","material").contentType("application/json").content(json.writeValueAsString(Map.of(
                        "text",GeminiFixtures.SOURCE,"concept",Map.of("name","감가상각","scope","정액법")))))
                .andReturn().getResponse();
        assertThat(material.getStatus()).isEqualTo(201);
        var data=json.readTree(material.getContentAsByteArray());
        var materialId=data.path("materialId").asText();var conceptId=data.path("concepts").get(0).path("conceptId").asText();
        var chunkId=data.path("concepts").get(0).path("sourceChunkIds").get(0).asText();
        var session=mvc.perform(post("/api/v1/sessions").cookie(cookie).header("X-CSRF-Token",csrf)
                .header("Idempotency-Key","session").contentType("application/json")
                .content(json.writeValueAsString(Map.of("materialId",materialId,"conceptId",conceptId))))
                .andReturn().getResponse();
        assertThat(session.getStatus()).isEqualTo(201);
        scope=new Scope(member,UUID.fromString(json.readTree(session.getContentAsByteArray()).path("sessionId").asText()),
                materialId,conceptId,List.of(chunkId));
        bContext=new LearningContentGenerator.Context(member,scope.sessionId(),materialId,conceptId,"first",
                new ConceptSource.Snapshot("감가상각","정액법",List.of(chunkId)),null);
        doAnswer(call -> {
            var req=json.readTree(call.getArgument(0,String.class));
            var input=json.readTree(req.path("contents").get(0).path("parts").get(0).path("text").asText());
            assertThat(input.path("lectureExcerpts").get(0).path("text").asText()).contains("취득원가");
            if(input.path("operation").asText().equals("summary")) return envelope(legacyPayload());
            return envelope(payload("원가 배분"));
        }).when(transport).generate(anyString());
    }
    Request request(String operation) {
        return new Request(scope,operation,"v1","한국어로 원문의 원리를 설명하세요.",
                json.readTree("{\"task\":\"설명\"}"),json.readTree("""
                {"type":"object","properties":{"result":{"type":"string","minLength":1,"maxLength":2000}},
                 "required":["result"],"additionalProperties":false}
                """));
    }
    JsonNode payload(Object result) {
        return json.valueToTree(Map.of("result",result,"sourceChunkIds",scope.sourceChunkIds(),
                "evidence",List.of(Map.of("sourceChunkId",scope.sourceChunkIds().getFirst(),"quote",GeminiFixtures.QUOTE))));
    }
    JsonNode legacyPayload() {
        var fixture=json.readTree(GeminiFixtures.response(json,true));
        var text=fixture.path("candidates").get(0).path("content").path("parts").get(0).path("text").asText();
        return json.readTree(text.replace("\"k01\"","\""+scope.sourceChunkIds().getFirst()+"\""));
    }
    String envelope(JsonNode value) { return GeminiFixtures.envelope(json,json.writeValueAsString(value),"STOP"); }
    String generate(Request request) { return port.generate(request,node -> node.path("result").asText()); }
    Request shortRequest() {
        var old=request("assessment/problems");
        return new Request(scope,old.operation(),"v5",old.instruction(),old.input(),old.schema(),CitationPolicy.SHORT_EXACT);
    }
    void shortResponse() {
        doAnswer(call -> {
            var request=json.readTree(call.getArgument(0,String.class));
            var data=json.readTree(request.path("contents").get(0).path("parts").get(0).path("text").asText());
            var candidate=data.path("citationCandidates").get(0);
            var value=(tools.jackson.databind.node.ObjectNode)payload("원가 배분");
            value.set("evidence",json.valueToTree(List.of(Map.of("sourceChunkId",candidate.path("sourceChunkId").asText(),
                    "quote",candidate.path("quote").asText()))));
            return envelope(value);
        }).when(transport).generate(anyString());
    }
    long calls() { return jdbc.queryForObject("SELECT count(*) FROM teachmi.learning_ai_call",Long.class); }
    void error(Runnable action,String code) {
        assertThatThrownBy(action::run).isInstanceOfSatisfying(LearningException.class,e -> assertThat(e.code()).isEqualTo(code));
    }
    GeminiGenerationPort scoped(GeminiSettings config,GeminiLedger store) {
        return new GeminiPortAdapter(new GeminiGenerationService(config,context.getBeanProvider(LearningSourceReader.class),transport,store,json,
                context.getBeanProvider(LearningSessionService.class)));
    }
    GeminiSettings limits(String budget) {
        return new GeminiSettings(settings.apiKey(),settings.model(),settings.connectTimeout(),settings.requestTimeout(),
                budget.equals("minute")?1:100,budget.equals("day")?1:100,budget.equals("member")?1:100,
                settings.maxAttempts(),settings.maxSourceChars(),settings.maxOutputTokens());
    }

    @Test void shortCandidatesAreAuthorizedBoundedAndDurableWithoutADuplicateReservation() {
        shortResponse();
        var request=shortRequest();
        assertThat(generate(request)).isEqualTo("원가 배분");
        var rows=jdbc.queryForList("SELECT * FROM teachmi.learning_ai_generation");
        assertThat(scoped(settings,ledger).<String>generate(request,node -> node.path("result").asText())).isEqualTo("원가 배분");
        assertThat(jdbc.queryForList("SELECT * FROM teachmi.learning_ai_generation")).isEqualTo(rows);
        var captured=org.mockito.ArgumentCaptor.forClass(String.class);
        verify(transport).generate(captured.capture());
        var wire=json.readTree(captured.getValue());
        var data=json.readTree(wire.path("contents").get(0).path("parts").get(0).path("text").asText());
        var choices=wire.path("generationConfig").path("responseJsonSchema").path("properties").path("evidence").path("items").path("properties").path("quote");
        assertThat(choices.path("maxLength").asInt()).isEqualTo(40);
        assertThat(data.path("citationCandidates").size()).isBetween(1,8);
        for(var candidate:data.path("citationCandidates")) {
            assertThat(candidate.path("sourceChunkId").asText()).isEqualTo(scope.sourceChunkIds().getFirst());
            String quote=candidate.path("quote").asText();
            assertThat(quote.length()).isBetween(6,40);
            assertThat(GeminiFixtures.SOURCE).contains(quote);
            assertThat(choices.path("enum")).contains(json.valueToTree(quote));
        }
        assertThat(calls()).isEqualTo(1);
        // Even a cached hit must recheck A ownership.
        var other=UUID.randomUUID();
        jdbc.update("INSERT INTO teachmi.app_user(id,email,password_hash,created_at) VALUES (?,?,'unused-test-hash',now())",other,other+"@example.test");
        jdbc.update("UPDATE teachmi.material SET user_id=? WHERE id=?::uuid",other,scope.materialId());
        error(() -> generate(request),"NOT_FOUND");
        verify(transport).generate(anyString());
    }

    @ParameterizedTest @ValueSource(strings={"long","not-candidate","not-exact","unknown-id","wrong-schema"})
    void shortResponseFailuresRemainSpecificAndNeverBecomeSuccess(String defect) {
        var value=(tools.jackson.databind.node.ObjectNode)payload("원가 배분");
        String quote=switch(defect) {
            case "long" -> GeminiFixtures.SOURCE.substring(0,42);
            case "not-candidate" -> GeminiFixtures.SOURCE.substring(1,20);
            case "not-exact" -> "존재하지 않는 인용문입니다.";
            default -> ShortCitationCandidates.from(List.of(new LearningSourceReader.Chunk(scope.sourceChunkIds().getFirst(),GeminiFixtures.SOURCE))).getFirst().quote();
        };
        value.set("evidence",json.valueToTree(List.of(Map.of("sourceChunkId",scope.sourceChunkIds().getFirst(),"quote",quote))));
        if(defect.equals("unknown-id")) value.set("sourceChunkIds",json.valueToTree(List.of("unowned")));
        if(defect.equals("wrong-schema")) value.put("result",true);
        doReturn(envelope(value)).when(transport).generate(anyString());
        var failure=catchThrowableOfType(() -> generate(shortRequest()),LearningException.class);
        assertThat(failure.code()).isEqualTo("INVALID_GEMINI_RESPONSE");
        assertThat(failure.recovery().diagnosticCode()).isEqualTo(switch(defect) {
            case "long" -> "EVIDENCE_QUOTE_TOO_LONG";
            case "not-candidate" -> "EVIDENCE_CANDIDATE_INVALID";
            case "not-exact" -> "EVIDENCE_QUOTE_NOT_EXACT";
            case "unknown-id" -> "CITATION_ID_INVALID";
            default -> "JSON_SCHEMA_INVALID";
        });
        assertThat(jdbc.queryForObject("SELECT state FROM teachmi.learning_ai_generation",String.class)).isEqualTo("failed");
        assertThat(calls()).isEqualTo(1);
        verify(transport).generate(anyString());
    }

    @Test void aValidHistoricalStandardSuccessIsReadWithoutAnyNewReceiptOrQuotaEvenAtTheLimit() {
        var previous=request("assessment/problems");
        assertThat(generate(previous)).isEqualTo("원가 배분"); // Old exact quote is valid but is not a candidate.
        var rows=jdbc.queryForList("SELECT * FROM teachmi.learning_ai_generation");
        var budget=limits("minute");
        try(var store=new GeminiLedger(database,budget)) {
            assertThat(scoped(budget,store).<String>generateWithPreviousSuccess(shortRequest(),previous,node -> node.path("result").asText())).isEqualTo("원가 배분");
        }
        assertThat(jdbc.queryForList("SELECT * FROM teachmi.learning_ai_generation")).isEqualTo(rows);
        assertThat(calls()).isEqualTo(1);
        verify(transport).generate(anyString());
    }

    @ParameterizedTest @ValueSource(strings={"uncertain","expired"})
    void shortPolicyNeverBypassesAnUnresolvedStandardRequestOrChangesIt(String state) {
        var previous=request("assessment/problems");
        if(state.equals("uncertain")) {
            doThrow(new GeminiCallFailure(true,503,"AI_GENERATION_UNCERTAIN",0,"TRANSPORT_REQUEST_TIMEOUT")).when(transport).generate(anyString());
            error(() -> generate(previous),"AI_GENERATION_UNCERTAIN");
        } else {
            ledger.claim("historical",scope.memberId(),scope.sessionId(),100);
            jdbc.update("UPDATE teachmi.learning_ai_generation SET lease_until=now()-interval '1 second'");
        }
        var rows=jdbc.queryForList("SELECT * FROM teachmi.learning_ai_generation");
        error(() -> port.generateWithPreviousSuccess(shortRequest(),previous,node -> node),"AI_GENERATION_UNCERTAIN");
        assertThat(jdbc.queryForList("SELECT * FROM teachmi.learning_ai_generation")).isEqualTo(rows);
        assertThat(calls()).isEqualTo(1);
        verify(transport,times(state.equals("uncertain")?1:0)).generate(anyString());
    }

    @Test void noUsableCandidateFailsBeforeReservationAndNeverInventsOrExtendsText() {
        jdbc.update("UPDATE teachmi.source_chunk SET text='짧음' WHERE id=?::uuid",scope.sourceChunkIds().getFirst());
        error(() -> generate(shortRequest()),"LEARNING_SOURCE_INCOMPLETE");
        assertThat(calls()).isZero();
        verifyNoInteractions(transport);
    }

    @Test void historicalFallbackCannotCrossScopeOrOperation() {
        var previous=request("assessment/grade");
        error(() -> port.generateWithPreviousSuccess(shortRequest(),previous,node -> node),"INVALID_AI_REQUEST");
        assertThat(calls()).isZero();
        verifyNoInteractions(transport);
    }

    @Test void historicalFallbackCannotRestoreADifferentInputOrSchema() {
        var base=request("assessment/problems");
        var changed=new Request(scope,base.operation(),"v4",base.instruction(),json.readTree("{\"task\":\"다른 설명\"}"),base.schema());
        error(() -> port.generateWithPreviousSuccess(shortRequest(),changed,node -> node),"INVALID_AI_REQUEST");
        assertThat(calls()).isZero();
        verifyNoInteractions(transport);
    }

    @Test void anExplicitNewPromptDoesNotRewriteThePreviouslyFailedLongQuoteReceipt() {
        var base=request("assessment/problems");
        var previous=new Request(scope,base.operation(),"v4",base.instruction(),base.input(),base.schema());
        doReturn(envelope(payload("원가 배분"))).when(transport).generate(anyString());
        error(() -> port.generate(previous,node -> {throw new GeminiResponseFailure("EVIDENCE_QUOTE_TOO_LONG");}),"INVALID_GEMINI_RESPONSE");
        var old=jdbc.queryForMap("SELECT * FROM teachmi.learning_ai_generation");
        shortResponse();
        assertThat(port.<String>generateWithPreviousSuccess(shortRequest(),previous,node -> node.path("result").asText())).isEqualTo("원가 배분");
        assertThat(jdbc.queryForMap("SELECT * FROM teachmi.learning_ai_generation WHERE generation_key=?",old.get("generation_key"))).isEqualTo(old);
        assertThat(calls()).isEqualTo(2);
    }

    @Test void shortPolicyRetainsTheExistingAttemptLimit() {
        doReturn("invalid envelope").when(transport).generate(anyString());
        error(() -> generate(shortRequest()),"INVALID_GEMINI_RESPONSE");
        error(() -> generate(shortRequest()),"INVALID_GEMINI_RESPONSE");
        error(() -> generate(shortRequest()),"AI_ATTEMPT_LIMIT");
        assertThat(calls()).isEqualTo(2);
        verify(transport,times(2)).generate(anyString());
    }

    @Test void authorizedRawTextAndValidatedResultAreDurableAcrossPortInstances() {
        var request=request("assessment/problems");
        assertThat(generate(request)).isEqualTo("원가 배분");
        String persisted=scoped(settings,ledger).generate(request,node -> node.path("result").asText());
        assertThat(persisted).isEqualTo("원가 배분");
        assertThat(calls()).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT content_json FROM teachmi.learning_ai_generation",String.class))
                .contains(scope.sourceChunkIds().getFirst(),GeminiFixtures.QUOTE);
        verify(transport).generate(anyString());
    }
    @Test void jsonObjectOrderingAndDefensiveCopiesDoNotCreateAnotherGeneration() {
        var input=json.readTree("{\"b\":2,\"a\":1}");
        var schema=request("assessment/problems").schema();
        var once=new Request(scope,"assessment/problems","v1","원문 사용",input,schema);
        ((tools.jackson.databind.node.ObjectNode)input).put("a",999);
        ((tools.jackson.databind.node.ObjectNode)once.input()).put("a",777);
        var twice=new Request(scope,"assessment/problems","v1","원문 사용",json.readTree("{\"a\":1,\"b\":2}"),schema);
        assertThat(generate(once)).isEqualTo(generate(twice));
        assertThat(calls()).isEqualTo(1);
        assertThat(once.toString()).doesNotContain("원문 사용","999",scope.memberId());
    }
    @Test void operationsVersionsAndChangedSourcesNeverReuseTheWrongResult() {
        generate(request("assessment/problems"));
        generate(request("assessment/grade"));
        var request=request("assessment/problems");
        generate(new Request(scope,request.operation(),"v2",request.instruction(),request.input(),request.schema()));
        jdbc.update("UPDATE teachmi.source_chunk SET text=text||' 추가 원문.' WHERE id=?::uuid",scope.sourceChunkIds().getFirst());
        generate(request("assessment/problems"));
        assertThat(calls()).isEqualTo(4);
    }
    @Test void aCacheHitRechecksAsCurrentOwnership() {
        var request=request("assessment/problems");
        generate(request);
        var other=UUID.randomUUID();
        jdbc.update("INSERT INTO teachmi.app_user(id,email,password_hash,created_at) VALUES (?,?,'unused-test-hash',now())",other,other+"@example.test");
        jdbc.update("UPDATE teachmi.material SET user_id=? WHERE id=?::uuid",other,scope.materialId());
        error(() -> generate(request),"NOT_FOUND");
        assertThat(calls()).isEqualTo(1);
    }
    @ParameterizedTest @ValueSource(strings={"minute","day","member"})
    void bAndTheCommonPortConsumeTheSameBudgets(String budget) {
        legacy.summarize(bContext);
        var settings=limits(budget);
        try(var store=new GeminiLedger(database,settings)) {
            error(() -> scoped(settings,store).generate(request("assessment/problems"),node -> node),"AI_USAGE_LIMIT");
        }
        assertThat(calls()).isEqualTo(1);
    }
    @Test void aCommonCallAlsoExhaustsTheBudgetForTheBGenerator() {
        var settings=limits("minute");
        try(var store=new GeminiLedger(database,settings)) {
            var service=new GeminiGenerationService(settings,context.getBeanProvider(LearningSourceReader.class),transport,store,json,
                    context.getBeanProvider(LearningSessionService.class));
            new GeminiPortAdapter(service).generate(request("assessment/problems"),node -> node);
            var generator=new GeminiContentGenerator(service,settings,json);
            error(() -> generator.summarize(bContext),"AI_USAGE_LIMIT");
        }
        assertThat(calls()).isEqualTo(1);
    }
    @Test void simultaneousIdenticalRequestsMakeOnePaidCall() throws Exception {
        var started=new CountDownLatch(1);var release=new CountDownLatch(1);
        doAnswer(call -> { started.countDown();assertThat(release.await(10,TimeUnit.SECONDS)).isTrue();return envelope(payload("원가 배분")); })
                .when(transport).generate(anyString());
        var request=request("assessment/problems");
        try(var executor=Executors.newSingleThreadExecutor()) {
            var first=executor.submit(() -> generate(request));
            try {
                assertThat(started.await(10,TimeUnit.SECONDS)).isTrue();
                error(() -> generate(request),"AI_GENERATION_IN_PROGRESS");
            } finally { release.countDown(); }
            assertThat(first.get(15,TimeUnit.SECONDS)).isEqualTo("원가 배분");
        }
        assertThat(generate(request)).isEqualTo("원가 배분");
        assertThat(calls()).isEqualTo(1);
    }
    @ParameterizedTest @ValueSource(strings={"not-json","bad-payload-json","null-payload","blocked","truncated","unknown-id","bad-quote","missing-evidence",
            "duplicate-id","wrong-type","extra","missing-field"})
    void badResponsesAreNeverSavedAsSuccessAndExplicitRetryIsBounded(String defect) {
        var value=(tools.jackson.databind.node.ObjectNode)payload("원가 배분");
        switch(defect) {
            case "unknown-id" -> value.set("sourceChunkIds",json.valueToTree(List.of("unowned")));
            case "bad-quote" -> value.set("evidence",json.valueToTree(List.of(Map.of("sourceChunkId",scope.sourceChunkIds().getFirst(),"quote","원문에 없는 인용문입니다."))));
            case "missing-evidence" -> value.remove("evidence");
            case "duplicate-id" -> value.set("sourceChunkIds",json.valueToTree(List.of(scope.sourceChunkIds().getFirst(),scope.sourceChunkIds().getFirst())));
            case "wrong-type" -> value.put("result",true);
            case "extra" -> value.put("unexpected","value");
            case "missing-field" -> value.remove("result");
        }
        String response=switch(defect) {
            case "not-json" -> "not-json";
            case "bad-payload-json" -> GeminiFixtures.envelope(json,"{broken private content","STOP");
            case "null-payload" -> GeminiFixtures.envelope(json,"null","STOP");
            case "blocked" -> "{\"promptFeedback\":{\"blockReason\":\"SAFETY\"}}";
            case "truncated" -> GeminiFixtures.envelope(json,json.writeValueAsString(value),"MAX_TOKENS");
            default -> envelope(value);
        };
        doReturn(response).when(transport).generate(anyString());
        var request=request("assessment/problems");
        error(() -> generate(request),"INVALID_GEMINI_RESPONSE");
        assertThat(calls()).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT state FROM teachmi.learning_ai_generation",String.class)).isEqualTo("failed");
        String diagnostic=switch(defect) {
            case "not-json" -> "ENVELOPE_JSON_INVALID";
            case "bad-payload-json" -> "CONTENT_JSON_INVALID";
            case "null-payload" -> "JSON_SCHEMA_INVALID";
            case "blocked" -> "PROMPT_BLOCKED";
            case "truncated" -> "RESPONSE_MAX_TOKENS";
            case "bad-quote" -> "EVIDENCE_QUOTE_NOT_EXACT";
            case "missing-evidence" -> "EVIDENCE_MISSING";
            case "duplicate-id" -> "CITATION_ID_DUPLICATE";
            case "wrong-type","extra","missing-field" -> "JSON_SCHEMA_INVALID";
            default -> "CITATION_ID_INVALID";
        };
        assertThat(jdbc.queryForObject("SELECT last_error_code FROM teachmi.learning_ai_generation",String.class))
                .isEqualTo(diagnostic);
        doReturn(envelope(payload("원가 배분"))).when(transport).generate(anyString());
        assertThat(generate(request)).isEqualTo("원가 배분");
        assertThat(calls()).isEqualTo(2);
    }
    @Test void typedDomainDiagnosticsReachRecoveryAndTheLedgerWithoutPrivateMessages() {
        var exception=catchThrowableOfType(() -> port.generate(request("assessment/problems"),node -> {
            throw new com.teachmi.backend.ai.GeminiValidationException(
                    com.teachmi.backend.ai.GeminiValidationException.Diagnostic.EVIDENCE_QUOTE_TOO_LONG,"private domain details");
        }),LearningException.class);
        assertThat(exception.code()).isEqualTo("INVALID_GEMINI_RESPONSE");
        assertThat(exception.recovery().diagnosticCode()).isEqualTo("EVIDENCE_QUOTE_TOO_LONG");
        assertThat(exception.getMessage()).doesNotContain("private domain details");
        assertThat(jdbc.queryForObject("SELECT last_error_code FROM teachmi.learning_ai_generation",String.class)).isEqualTo("EVIDENCE_QUOTE_TOO_LONG");
    }

    @Test void timeoutsAreUncertainAndNeverAutomaticallySentAgain() {
        doThrow(new GeminiCallFailure(true,503,"AI_GENERATION_UNCERTAIN",0)).when(transport).generate(anyString());
        var request=request("assessment/problems");
        error(() -> generate(request),"AI_GENERATION_UNCERTAIN");
        error(() -> generate(request),"AI_GENERATION_UNCERTAIN");
        assertThat(calls()).isEqualTo(1);
        verify(transport).generate(anyString());
        assertThat(jdbc.queryForObject("SELECT state FROM teachmi.learning_ai_generation",String.class)).isEqualTo("uncertain");
    }

    @Test void successReceiptWriteFailureLeavesRunningAndDoesNotSendAnotherRequest() {
        var broken=mock(GeminiLedger.class);
        doAnswer(call -> ledger.claim(call.getArgument(0),call.getArgument(1,String.class),
                call.getArgument(2),call.getArgument(3))).when(broken).claim(anyString(),anyString(),any(),anyInt());
        doThrow(new IllegalStateException("private persistence failure")).when(broken).succeed(anyString(),anyString());
        var request=request("assessment/problems");
        error(() -> scoped(settings,broken).generate(request,node -> node.path("result").asText()),"AI_GENERATION_UNCERTAIN");
        assertThat(jdbc.queryForObject("SELECT state FROM teachmi.learning_ai_generation",String.class)).isEqualTo("running");
        error(() -> generate(request),"AI_GENERATION_IN_PROGRESS");
        assertThat(calls()).isEqualTo(1);
        verify(transport).generate(anyString());
    }

    @Test void failureReceiptWriteFailureLeavesRunningAndDoesNotSendAnotherRequest() {
        var broken=mock(GeminiLedger.class);
        doAnswer(call -> ledger.claim(call.getArgument(0),call.getArgument(1,String.class),
                call.getArgument(2),call.getArgument(3))).when(broken).claim(anyString(),anyString(),any(),anyInt());
        doThrow(new IllegalStateException("private persistence failure")).when(broken)
                .fail(anyString(),anyBoolean(),anyInt(),anyString());
        doThrow(new GeminiCallFailure(true,503,"AI_GENERATION_UNCERTAIN",0,"UPSTREAM_HTTP_503"))
                .when(transport).generate(anyString());
        var request=request("assessment/problems");
        error(() -> scoped(settings,broken).generate(request,node -> node.path("result").asText()),"AI_GENERATION_UNCERTAIN");
        assertThat(jdbc.queryForObject("SELECT state FROM teachmi.learning_ai_generation",String.class)).isEqualTo("running");
        error(() -> generate(request),"AI_GENERATION_IN_PROGRESS");
        assertThat(calls()).isEqualTo(1);
        verify(transport).generate(anyString());
    }
    @Test void failureStatusReadFailureStopsWithAnExistingApiStageAndSafeDiagnostic() throws Exception {
        var broken=mock(GeminiLedger.class);
        doAnswer(call -> ledger.claim(call.getArgument(0),call.getArgument(1,String.class),
                call.getArgument(2),call.getArgument(3))).when(broken).claim(anyString(),anyString(),any(),anyInt());
        doAnswer(call -> {ledger.fail(call.getArgument(0),call.getArgument(1),call.getArgument(2),call.getArgument(3));return null;})
                .when(broken).fail(anyString(),anyBoolean(),anyInt(),anyString());
        doThrow(new IllegalStateException("private status read failure")).when(broken).failedAtLimit(anyString());
        doReturn("invalid response").when(transport).generate(anyString());
        var failure=catchThrowableOfType(() -> scoped(settings,broken).generate(request("assessment/problems"),node -> node),LearningException.class);
        assertThat(failure.code()).isEqualTo("AI_GENERATION_UNCERTAIN");
        assertThat(failure.recovery().action()).isEqualTo("review_required");
        assertThat(failure.recovery().retryAllowed()).isFalse();
        assertThat(failure.recovery().diagnosticCode()).isEqualTo("LEDGER_FAILURE_STATUS_READ_FAILED");
        var schema=json.readTree(java.nio.file.Files.readString(java.nio.file.Path.of("api/openapi.json")));
        var stages=schema.path("components").path("schemas").path("GenerationRecovery").path("properties").path("stage").path("enum");
        assertThat(json.convertValue(stages,java.util.List.class)).contains(failure.recovery().stage());
        assertThat(jdbc.queryForObject("SELECT state FROM teachmi.learning_ai_generation",String.class)).isEqualTo("failed");
        assertThat(calls()).isEqualTo(1);
        verify(transport).generate(anyString());
    }

    @Test void retryAfterAndAttemptLimitsStillApplyToThePublicPort() {
        var request=request("assessment/problems");
        doThrow(new GeminiCallFailure(false,429,"GEMINI_RATE_LIMITED",60)).when(transport).generate(anyString());
        error(() -> generate(request),"GEMINI_RATE_LIMITED");
        error(() -> generate(request),"AI_RETRY_LATER");
        assertThat(calls()).isEqualTo(1);
        jdbc.update("UPDATE teachmi.learning_ai_generation SET retry_after=now()-interval '1 second'");
        doReturn("invalid").when(transport).generate(anyString());
        error(() -> generate(request),"INVALID_GEMINI_RESPONSE");
        error(() -> generate(request),"AI_ATTEMPT_LIMIT");
        assertThat(calls()).isEqualTo(2);
    }

    @Test void uncertaintyCannotBeBypassedByVersionModelOrOutputLimitAndOldRowsStayIdentical() {
        doThrow(new GeminiCallFailure(true,503,"AI_GENERATION_UNCERTAIN",0,"TRANSPORT_REQUEST_TIMEOUT")).when(transport).generate(anyString());
        var base=request("assessment/problems");
        error(() -> generate(base),"AI_GENERATION_UNCERTAIN");
        var before=jdbc.queryForList("SELECT * FROM teachmi.learning_ai_generation ORDER BY generation_key");
        error(() -> generate(new Request(scope,base.operation(),"v2",base.instruction(),base.input(),base.schema())),"AI_GENERATION_UNCERTAIN");
        var changedModel=new GeminiSettings(settings.apiKey(),"another-model",settings.connectTimeout(),settings.requestTimeout(),100,100,100,2,16000,2048);
        var changedOutput=new GeminiSettings(settings.apiKey(),settings.model(),settings.connectTimeout(),settings.requestTimeout(),100,100,100,2,16000,6144);
        error(() -> scoped(changedModel,ledger).generate(base,node -> node),"AI_GENERATION_UNCERTAIN");
        error(() -> scoped(changedOutput,ledger).generate(base,node -> node),"AI_GENERATION_UNCERTAIN");
        assertThat(jdbc.queryForList("SELECT * FROM teachmi.learning_ai_generation ORDER BY generation_key")).isEqualTo(before);
        assertThat(calls()).isEqualTo(1);verify(transport).generate(anyString());
    }

    @Test void anExistingSuccessCanStillBeRestoredWhileAnotherOperationIsUncertain() {
        var success=request("assessment/problems");generate(success);
        doThrow(new GeminiCallFailure(true,503,"AI_GENERATION_UNCERTAIN",0)).when(transport).generate(anyString());
        error(() -> generate(request("assessment/grade")),"AI_GENERATION_UNCERTAIN");
        assertThat(generate(success)).isEqualTo("원가 배분");assertThat(calls()).isEqualTo(2);
    }

    @Test void expiredRunningReceiptBlocksANewHashWithoutBeingReclaimed() {
        ledger.claim("old-version",scope.memberId(),scope.sessionId(),100);
        jdbc.update("UPDATE teachmi.learning_ai_generation SET lease_until=now()-interval '1 second' WHERE generation_key='old-version'");
        var before=jdbc.queryForList("SELECT * FROM teachmi.learning_ai_generation");
        var failure=catchThrowableOfType(() -> generate(request("assessment/problems")),LearningException.class);
        assertThat(failure.code()).isEqualTo("AI_GENERATION_UNCERTAIN");
        assertThat(failure.recovery().diagnosticCode()).isEqualTo("GENERATION_LEASE_EXPIRED");
        assertThat(jdbc.queryForList("SELECT * FROM teachmi.learning_ai_generation")).isEqualTo(before);
        assertThat(calls()).isEqualTo(1);verifyNoInteractions(transport);
    }

    @Test void legacyTimeoutDiagnosticIsMappedWithoutChangingOrExposingTheHistoricalRow() {
        ledger.claim("old",scope.memberId(),scope.sessionId(),100);
        ledger.fail("old",true,0,"TRANSPORT_HttpTimeoutException");
        var before=jdbc.queryForList("SELECT * FROM teachmi.learning_ai_generation");
        var failure=catchThrowableOfType(() -> generate(request("assessment/problems")),LearningException.class);
        assertThat(failure.recovery().diagnosticCode()).isEqualTo("TRANSPORT_REQUEST_TIMEOUT");
        assertThat(failure.recovery().retryAllowed()).isFalse();
        assertThat(jdbc.queryForList("SELECT * FROM teachmi.learning_ai_generation")).isEqualTo(before);
        verifyNoInteractions(transport);
    }
    @Test void domainValidationRunsBeforeCachingAndOnCacheHits() {
        var request=request("assessment/grade");
        error(() -> port.generate(request,node -> {throw new IllegalArgumentException("private domain failure");}),"INVALID_GEMINI_RESPONSE");
        assertThat(generate(request)).isEqualTo("원가 배분");
        error(() -> port.generate(request,node -> {throw new IllegalArgumentException("changed contract");}),"INVALID_GEMINI_RESPONSE");
        assertThat(calls()).isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT state FROM teachmi.learning_ai_generation",String.class)).isEqualTo("succeeded");
    }
    @Test void aLearningCommandsCallbackCannotPerformPaidIoOrKeepAReceipt() {
        var request=request("assessment/problems");
        error(() -> commands.execute(scope.memberId(),scope.sessionId(),"must-rollback","c/test","",String.class,
                locked -> generate(request)),"AI_TRANSACTION_NOT_ALLOWED");
        assertThat(calls()).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM teachmi.learning_session_request WHERE member_id=? AND request_key='must-rollback'",Long.class,scope.memberId())).isZero();
        verifyNoInteractions(transport);
        assertThat(sessions.get(scope.memberId(),scope.sessionId()).stage()).isEqualTo("mode");
    }
    @Test void anOrdinaryOrReadOnlyTransactionCannotCallTheNewPortEither() {
        for(boolean readOnly:List.of(false,true)) {
            var transaction=new TransactionTemplate(transactions);transaction.setReadOnly(readOnly);
            error(() -> transaction.execute(status -> generate(request("assessment/problems"))),"AI_TRANSACTION_NOT_ALLOWED");
        }
        assertThat(calls()).isZero();
        verifyNoInteractions(transport);
    }
    @Test void unsupportedSchemaAndOversizedInputFailBeforeBudgetReservation() {
        var base=request("assessment/problems");
        var unsupported=(tools.jackson.databind.node.ObjectNode)base.schema();
        unsupported.put("$ref","#/private");
        error(() -> generate(new Request(scope,base.operation(),"v1",base.instruction(),base.input(),unsupported)),"INVALID_AI_REQUEST");
        var input=json.createObjectNode().put("text","a".repeat(48001));
        error(() -> generate(new Request(scope,base.operation(),"v1",base.instruction(),input,base.schema())),"INVALID_AI_REQUEST");
        assertThat(calls()).isZero();
        verifyNoInteractions(transport);
    }
@Test void currentSessionOwnershipAndItsSelectionAreVerifiedBeforeCalls() {
        var base=request("assessment/problems");
        var wrongOwner=new Scope(UUID.randomUUID().toString(),scope.sessionId(),scope.materialId(),scope.conceptId(),scope.sourceChunkIds());
        error(() -> generate(new Request(wrongOwner,base.operation(),"v1",base.instruction(),base.input(),base.schema())),"NOT_FOUND");
        var wrongMaterial=new Scope(scope.memberId(),scope.sessionId(),UUID.randomUUID().toString(),scope.conceptId(),scope.sourceChunkIds());
        error(() -> generate(new Request(wrongMaterial,base.operation(),"v1",base.instruction(),base.input(),base.schema())),"NOT_FOUND");
        var wrongSources=new Scope(scope.memberId(),scope.sessionId(),scope.materialId(),scope.conceptId(),List.of(UUID.randomUUID().toString()));
        error(() -> generate(new Request(wrongSources,base.operation(),"v1",base.instruction(),base.input(),base.schema())),"LEARNING_SOURCE_INCOMPLETE");
        assertThat(calls()).isZero();
        verifyNoInteractions(transport);
    }

    @Test void sourceSizeLimitStillAppliesBeforeReservationOnThePublicPort() {
        jdbc.update("UPDATE teachmi.source_chunk SET text=? WHERE id=?::uuid","가".repeat(settings.maxSourceChars()+1),scope.sourceChunkIds().getFirst());
        error(() -> generate(request("assessment/problems")),"LEARNING_SOURCE_TOO_LARGE");
        assertThat(calls()).isZero();
        verifyNoInteractions(transport);
    }

    @Test void previouslySavedBGenerationKeysAndPayloadsRemainUsableWithoutAnotherCall() throws Exception {
        var chunks=List.of(new LearningSourceReader.Chunk(scope.sourceChunkIds().getFirst(),GeminiFixtures.SOURCE));
        String oldRequest=org.springframework.test.util.ReflectionTestUtils.invokeMethod(legacy,"request",bContext,"summary",chunks,null,null);
        var key=java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(
                ("teachmi-content-v1\n"+settings.model()+"\n"+oldRequest).getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        ledger.claim(key,bContext,oldRequest.getBytes(java.nio.charset.StandardCharsets.UTF_8).length);
        ledger.succeed(key,json.writeValueAsString(legacyPayload()));
        assertThat(legacy.summarize(bContext).text()).contains("사용 기간");
        assertThat(calls()).isEqualTo(1);
        verifyNoInteractions(transport);
    }
    @Test void nestedArraysNullableFieldsEnumsAndNumericBoundsAreChecked() {
        var schema=json.readTree("""
                {"type":"object","properties":{"result":{"type":"array","minItems":1,"maxItems":2,
                "items":{"type":"object","properties":{"order":{"type":"integer","minimum":1,"maximum":2},
                "status":{"type":"string","enum":["ready","pending"]},"note":{"type":["string","null"]}},
                "required":["order","status","note"],"additionalProperties":false}}},"required":["result"],"additionalProperties":false}
                """);
        var valid=payload(json.readTree("[{\"order\":1,\"status\":\"ready\",\"note\":null}]"));
        doReturn(envelope(valid)).when(transport).generate(anyString());
        var base=request("assessment/problems");
        var request=new Request(scope,base.operation(),"v1",base.instruction(),base.input(),schema);
        int count=port.generate(request,node -> node.path("result").size());
        assertThat(count).isEqualTo(1);
        var bad=payload(List.of(Map.of("order",3,"status","ready","note","설명")));
        doReturn(envelope(bad)).when(transport).generate(anyString());
        var changed=new Request(scope,base.operation(),"v2",base.instruction(),base.input(),schema);
        error(() -> port.generate(changed,node -> node),"INVALID_GEMINI_RESPONSE");
    }
}
