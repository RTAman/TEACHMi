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
        "teachmi.gemini.user-grading-recovery-enabled=true","teachmi.gemini.max-output-tokens=2048",
        "teachmi.gemini.requests-per-minute=1000","teachmi.gemini.requests-per-day=10000","teachmi.gemini.member-requests-per-day=1000",
        "spring.datasource.hikari.maximum-pool-size=2","spring.datasource.hikari.minimum-idle=0"})
class ApprovedGradingRecoveryIntegrationTest {
    @Autowired WebApplicationContext context;
    @Autowired AuthFilter auth;
    @Autowired ObjectMapper json;
    @Autowired JdbcTemplate jdbc;
    @Autowired GeminiSettings settings;
    @Autowired GeminiLedger ledger;
    @Autowired DataSourceProperties database;
    @MockitoBean GeminiTransport transport;
    private static final com.atlassian.oai.validator.OpenApiInteractionValidator CONTRACT=
            com.atlassian.oai.validator.OpenApiInteractionValidator.createForSpecificationUrl(
                    java.nio.file.Path.of("api/grading-recovery.openapi.json").toUri().toString()).build();
    void contract(org.springframework.mock.web.MockHttpServletResponse response,boolean post) {
        var output=com.atlassian.oai.validator.model.SimpleResponse.Builder.status(response.getStatus())
                .withContentType("application/json").withBody(response.getContentAsByteArray()).build();
        var report=CONTRACT.validateResponse("/api/v1/sessions/"+scope.sessionId()+"/grading-recovery"+(post?"/consent":""),
                post?com.atlassian.oai.validator.model.Request.Method.POST:com.atlassian.oai.validator.model.Request.Method.GET,output);
        assertThat(report.hasErrors()).as(report.toString()).isFalse();
    }
    UUID problemId;
    Scope scope;
    Cookie cookie;
    String csrf;
    MockMvc mvc;

    @BeforeEach void setup() throws Exception {
        jdbc.execute("TRUNCATE teachmi.learning_ai_recovery_execution,teachmi.learning_ai_recovery_authorization,teachmi.learning_ai_call,teachmi.learning_ai_generation");
        mvc=MockMvcBuilders.webAppContextSetup(context).addFilters(new LearningRequestTraceFilter(),auth).build();
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
        seedProblem();
    }

    void seedProblem() {
        UUID finalMessage=UUID.randomUUID();problemId=UUID.randomUUID();
        jdbc.update("INSERT INTO teachmi.learning_message(id,session_id,sequence,role,kind,chat_stage,text,source_chunk_ids,created_at) VALUES (?,?,1,'user','answer','wrapup','synthetic explanation',?,now())",
                finalMessage,scope.sessionId(),json.writeValueAsString(scope.sourceChunkIds()));
        jdbc.update("UPDATE teachmi.learning_session SET mode='first',stage='problem',version=11,final_explanation_id=? WHERE id=?",finalMessage,scope.sessionId());
        jdbc.update("""
                INSERT INTO teachmi.assessment_problem(id,session_id,member_id,sequence,question,changed_conditions,source_chunk_ids,
                expected_answer,solution,grading_criteria,evidence_version,created_at)
                VALUES (?,?,?,1,'합성 문제: 정액법 감가상각비는?','합성 조건',?,'90000','합성 해설','원가 배분 원리',11,now())
                """,problemId,scope.sessionId(),scope.memberId(),json.writeValueAsString(scope.sourceChunkIds()));
    }
    Map<String,String> answer() { return Map.of("answer"," 90000 ","work"," 원가를 기간에 배분합니다. "); }
    org.springframework.mock.web.MockHttpServletResponse submit(UUID authorization) throws Exception {
        return submit(authorization,"original-attempt",answer());
    }
    org.springframework.mock.web.MockHttpServletResponse submit(UUID authorization,String key,Map<String,String> body) throws Exception {
        var req=post("/api/v1/sessions/"+scope.sessionId()+"/problems/"+problemId+"/attempts")
                .cookie(cookie).header("X-CSRF-Token",csrf).header("Idempotency-Key",key)
                .contentType("application/json").content(json.writeValueAsString(body));
        if(authorization!=null) req.header(ApprovedRecoveryContext.HEADER,authorization);
        return mvc.perform(req).andReturn().getResponse();
    }
    Map<String,Object> uncertain() throws Exception {
        doThrow(new GeminiCallFailure(true,503,"AI_GENERATION_UNCERTAIN",0,"TRANSPORT_REQUEST_TIMEOUT")).when(transport).generate(anyString());
        assertThat(submit(null).getStatus()).isEqualTo(503);
        return jdbc.queryForMap("SELECT * FROM teachmi.learning_ai_generation");
    }
    UUID attempt() { return jdbc.queryForObject("SELECT id FROM teachmi.assessment_attempt WHERE problem_id=?",UUID.class,problemId); }
    org.springframework.mock.web.MockHttpServletResponse consentResponse(boolean accept) throws Exception {
        var response=mvc.perform(post("/api/v1/sessions/"+scope.sessionId()+"/grading-recovery/consent")
                .cookie(cookie).header("X-CSRF-Token",csrf).contentType("application/json")
                .content(json.writeValueAsString(Map.of("attemptId",attempt(),"acceptDuplicateCall",accept))))
                .andReturn().getResponse();
        contract(response,true);return response;
    }
    UUID consent() throws Exception {
        var response=consentResponse(true);
        assertThat(response.getStatus()).isEqualTo(200);
        return UUID.fromString(json.readTree(response.getContentAsByteArray()).path("authorizationId").asText());
    }
    JsonNode recovery() throws Exception {
        var response=mvc.perform(get("/api/v1/sessions/"+scope.sessionId()+"/grading-recovery").cookie(cookie)).andReturn().getResponse();
        assertThat(response.getStatus()).isEqualTo(200);
        assertThat(response.getHeader("Cache-Control")).contains("no-store");
        contract(response,false);
        return json.readTree(response.getContentAsByteArray());
    }
    JsonNode gradePayload(String wire,String judgement) {
        var request=json.readTree(wire);
        var data=json.readTree(request.path("contents").get(0).path("parts").get(0).path("text").asText());
        var candidate=data.path("citationCandidates").get(0);
        return json.valueToTree(Map.of("judgement",judgement,"reteachFocus",judgement.equals("wrong")?"원가 배분 원리를 설명해 보세요.":"",
                "reason","합성 채점 기준을 적용했습니다.","sourceChunkIds",List.of(candidate.path("sourceChunkId").asText()),
                "evidence",List.of(Map.of("sourceChunkId",candidate.path("sourceChunkId").asText(),"quote",candidate.path("quote").asText()))));
    }
    void successful(String judgement) {
        doAnswer(call -> GeminiFixtures.envelope(json,json.writeValueAsString(gradePayload(call.getArgument(0,String.class),judgement)),"STOP"))
                .when(transport).generate(anyString());
    }
    long calls() { return jdbc.queryForObject("SELECT count(*) FROM teachmi.learning_ai_call",Long.class); }
    void unchanged(Map<String,Object> original) {
        assertThat(jdbc.queryForMap("SELECT * FROM teachmi.learning_ai_generation WHERE generation_key=?",original.get("generation_key"))).isEqualTo(original);
        assertThat(ApprovedRecoveryContext.raw()).isNull();
    }

    @ParameterizedTest @ValueSource(strings={"correct","wrong","pending_review"})
    void ownerCanExplicitlyRetryOnceAndRestoreTheUnchangedAnswerAndVerdict(String judgement) throws Exception {
        var original=uncertain();var saved=jdbc.queryForMap("SELECT * FROM teachmi.assessment_attempt WHERE id=?",attempt());
        var before=jdbc.queryForList("SELECT * FROM teachmi.learning_ai_call");
        assertThat(recovery().path("state").asText()).isEqualTo("retry_available");
        assertThat(recovery().path("savedSubmission").path("answer").asText()).isEqualTo(answer().get("answer"));
        assertThat(recovery().toString()).doesNotContain("expectedAnswer","solution","gradingCriteria");
        UUID authorization=consent();assertThat(consent()).isEqualTo(authorization);
        assertThat(jdbc.queryForList("SELECT * FROM teachmi.learning_ai_call")).isEqualTo(before);
        assertThat(submit(null).getStatus()).isEqualTo(503); // Consent alone never authorizes the ordinary path.
        successful(judgement);
        var result=submit(authorization);assertThat(result.getStatus()).isEqualTo(201);
        assertThat(json.readTree(result.getContentAsByteArray()).path("judgement").asText()).isEqualTo(judgement);
        assertThat(submit(authorization).getContentAsString()).isEqualTo(result.getContentAsString());
        assertThat(submit(null).getContentAsString()).isEqualTo(result.getContentAsString());
        var after=jdbc.queryForMap("SELECT * FROM teachmi.assessment_attempt WHERE id=?",attempt());
        for(String field:List.of("id","answer","work","request_key","request_hash","attempt_no")) assertThat(after.get(field)).isEqualTo(saved.get(field));
        assertThat(calls()).isEqualTo(2);verify(transport,times(2)).generate(anyString());
        assertThat(recovery().path("state").asText()).isEqualTo("completed");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM teachmi.unresolved_ai_generation WHERE session_id=?",Long.class,scope.sessionId())).isZero();
        unchanged(original);
    }

    @Test void rejectsMissingConsentForgedConsentChangedBodyAndMissingCsrfWithoutDispatch() throws Exception {
        var original=uncertain();
        assertThat(consentResponse(false).getStatus()).isEqualTo(409);
        assertThat(submit(UUID.randomUUID()).getStatus()).isEqualTo(409);
        UUID auth=consent();
        assertThat(submit(auth,"original-attempt",Map.of("answer","changed","work","changed")).getStatus()).isEqualTo(409);
        assertThat(mvc.perform(post("/api/v1/sessions/"+scope.sessionId()+"/grading-recovery/consent").cookie(cookie)
                .contentType("application/json").content(json.writeValueAsString(Map.of("attemptId",attempt(),"acceptDuplicateCall",true))))
                .andReturn().getResponse().getStatus()).isEqualTo(403);
        assertThat(calls()).isEqualTo(1);verify(transport).generate(anyString());unchanged(original);
    }

    @Test void expiredConsentAndChangedPromptOrScopeCannotDispatch() throws Exception {
        var original=uncertain();UUID authorization=consent();
        jdbc.update("UPDATE teachmi.learning_ai_recovery_authorization SET expires_at=now()-interval '1 second' WHERE id=?",authorization);
        assertThat(submit(authorization).getStatus()).isEqualTo(409);
        UUID renewed=consent();assertThat(renewed).isNotEqualTo(authorization);
        jdbc.update("UPDATE teachmi.assessment_problem SET question='changed synthetic question' WHERE id=?",problemId);
        assertThat(submit(renewed).getStatus()).isEqualTo(409);assertThat(calls()).isEqualTo(1);unchanged(original);
    }

    @ParameterizedTest @ValueSource(strings={"minute","day","member"})
    void disabledRecoveryAndExistingQuotasAndLogicalAttemptLimitsArePreserved(String bucket) throws Exception {
        var original=uncertain();UUID authorization=consent();String key=(String)original.get("generation_key");
        try(var disabled=new GeminiLedger(database,settings)) {
            assertThatThrownBy(() -> disabled.claimApproved(authorization,key,scope.memberId(),scope.sessionId(),attempt(),10,2048))
                    .isInstanceOfSatisfying(LearningException.class,e -> assertThat(e.code()).isEqualTo("AI_RECOVERY_NOT_AUTHORIZED"));
        }
        var limited=new GeminiSettings(settings.apiKey(),settings.model(),settings.connectTimeout(),settings.requestTimeout(),
                bucket.equals("minute")?1:settings.requestsPerMinute(),bucket.equals("day")?1:settings.requestsPerDay(),
                bucket.equals("member")?1:settings.memberRequestsPerDay(),settings.maxAttempts(),settings.maxSourceChars(),settings.maxOutputTokens());
        try(var budget=new GeminiLedger(database,limited,true)) {
            assertThatThrownBy(() -> budget.claimApproved(authorization,key,scope.memberId(),scope.sessionId(),attempt(),10,2048))
                    .isInstanceOfSatisfying(LearningException.class,e -> assertThat(e.code()).isEqualTo("AI_USAGE_LIMIT"));
        }
        jdbc.update("UPDATE teachmi.learning_ai_generation SET attempts=2 WHERE generation_key=?",key);
        assertThat(submit(authorization).getStatus()).isEqualTo(429);
        assertThat(calls()).isEqualTo(1);verify(transport).generate(anyString());
    }

    @ParameterizedTest @ValueSource(booleans={true,false})
    void recoveryTimeoutOrInvalidResponseConsumesTheOnlyExtraCall(boolean timeout) throws Exception {
        var original=uncertain();UUID authorization=consent();
        if(!timeout) doReturn("{bad-json").when(transport).generate(anyString());
        var response=submit(authorization);assertThat(response.getStatus()).isEqualTo(timeout?503:502);
        assertThat(json.readTree(response.getContentAsByteArray()).path("recovery").path("retryAllowed").asBoolean()).isFalse();
        assertThat(submit(authorization).getStatus()).isEqualTo(409);
        assertThat(consentResponse(true).getStatus()).isEqualTo(409);
        assertThat(recovery().path("state").asText()).isEqualTo("exhausted");
        assertThat(jdbc.queryForObject("SELECT judgement FROM teachmi.assessment_attempt WHERE id=?",String.class,attempt())).isNull();
        assertThat(calls()).isEqualTo(2);verify(transport,times(2)).generate(anyString());unchanged(original);
    }

    @Test void anotherMemberCannotReadSavedAnswersOrCreateOrUseConsent() throws Exception {
        var original=uncertain();UUID authorization=consent();
        var credentials=Map.of("email",UUID.randomUUID()+"@example.test","password","correct-horse");
        mvc.perform(post("/api/v1/auth/signup").contentType("application/json").content(json.writeValueAsString(credentials)));
        var login=mvc.perform(post("/api/v1/auth/login").contentType("application/json").content(json.writeValueAsString(credentials))).andReturn().getResponse();
        cookie=login.getCookie("TEACHMI_SESSION");csrf=json.readTree(login.getContentAsByteArray()).path("csrfToken").asText();
        assertThat(mvc.perform(get("/api/v1/sessions/"+scope.sessionId()+"/grading-recovery").cookie(cookie)).andReturn().getResponse().getStatus()).isEqualTo(404);
        assertThat(consentResponse(true).getStatus()).isEqualTo(404);
        assertThat(submit(authorization).getStatus()).isEqualTo(404);
        assertThat(calls()).isEqualTo(1);unchanged(original);
    }

    @Test void concurrentClicksReserveAndDispatchOnlyOnce() throws Exception {
        var original=uncertain();UUID authorization=consent();
        var entered=new CountDownLatch(1);var release=new CountDownLatch(1);
        doAnswer(call -> { entered.countDown();assertThat(release.await(8,TimeUnit.SECONDS)).isTrue();
            return GeminiFixtures.envelope(json,json.writeValueAsString(gradePayload(call.getArgument(0,String.class),"correct")),"STOP");
        }).when(transport).generate(anyString());
        try(var executor=Executors.newVirtualThreadPerTaskExecutor()) {
            var first=executor.submit(() -> submit(authorization));
            assertThat(entered.await(8,TimeUnit.SECONDS)).isTrue();
            try { assertThat(submit(authorization).getStatus()).isEqualTo(409); }
            finally { release.countDown(); }
            assertThat(first.get(10,TimeUnit.SECONDS).getStatus()).isEqualTo(201);
        }
        assertThat(calls()).isEqualTo(2);verify(transport,times(2)).generate(anyString());unchanged(original);
    }

    @Test void cachedRecoveryDoesNotUnblockUntilCCommitsAndRestoresWithoutAnotherCall() throws Exception {
        var original=uncertain();UUID authorization=consent();
        var captured=org.mockito.ArgumentCaptor.forClass(String.class);verify(transport).generate(captured.capture());
        var receipt=ledger.claimApproved(authorization,(String)original.get("generation_key"),scope.memberId(),scope.sessionId(),attempt(),10,2048);
        ledger.succeed(receipt.key(),json.writeValueAsString(gradePayload(captured.getValue(),"correct")));
        try(var reopened=new GeminiLedger(database,settings,false)) {
            assertThat(reopened.recoveredSuccess((String)original.get("generation_key"),scope.memberId(),scope.sessionId(),attempt()))
                    .isEqualTo(json.writeValueAsString(gradePayload(captured.getValue(),"correct")));
        }
        assertThat(recovery().path("state").asText()).isEqualTo("result_available");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM teachmi.unresolved_ai_generation WHERE session_id=?",Long.class,scope.sessionId())).isEqualTo(1);
        assertThat(submit(null).getStatus()).isEqualTo(201); // Exact owned request restores cache, with no provider dispatch.
        assertThat(submit(null).getStatus()).isEqualTo(201);
        assertThat(calls()).isEqualTo(2);verify(transport).generate(anyString());unchanged(original);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM teachmi.unresolved_ai_generation WHERE session_id=?",Long.class,scope.sessionId())).isZero();
    }

    @Test void ordinarySessionReadIsNoStoreAndReservesNothingWithoutAPendingAnswer() throws Exception {
        assertThat(recovery().path("state").asText()).isEqualTo("none");
        assertThat(calls()).isZero();
        verifyNoInteractions(transport);
    }
}
