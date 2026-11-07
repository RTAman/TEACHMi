package com.teachmi.backend.assessment;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Executors;
import com.teachmi.backend.auth.ApiException;
import com.teachmi.backend.auth.AuthFilter;
import com.teachmi.backend.learning.LearningAssessmentAccess;
import com.teachmi.backend.learning.LearningException;
import com.teachmi.backend.learning.LearningSessionService;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.context.WebApplicationContext;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;

@SpringBootTest(properties="teachmi.assessment.demo-enabled=true")
class AssessmentAttemptApiTest {
    @Autowired WebApplicationContext context;
    @Autowired AuthFilter filter;
    @Autowired JdbcTemplate jdbc;
    @Autowired ObjectMapper json;
    @Autowired AssessmentAttemptService service;
    @Autowired LearningSessionService sessions;
    @Autowired LearningAssessmentAccess access;
    @Autowired AssessmentProblemRepository problems;
    @Autowired AssessmentAttemptRepository attempts;
    @Autowired TransactionTemplate transaction;
    @Autowired ReteachReturns returns;
    MockMvc mvc;
    Cookie cookie;
    String csrf,member;
    UUID session,first,second;
    record Login(String id,Cookie cookie,String csrf) {}

    @BeforeEach void setup() throws Exception {
        mvc=MockMvcBuilders.webAppContextSetup(context).addFilters(filter).build();
        var login=login();
        cookie=login.cookie(); csrf=login.csrf(); member=login.id();
        session=AssessmentFixtures.ready(jdbc,member);
        var result=mvc.perform(post("/api/v1/sessions/"+session+"/problems").cookie(cookie)
                .header("X-CSRF-Token",csrf)).andReturn().getResponse();
        assertThat(result.getStatus()).isEqualTo(200);
        var set=body(result).path("problems");
        first=UUID.fromString(set.get(0).path("problemId").asText());
        second=UUID.fromString(set.get(1).path("problemId").asText());
    }
    Login login() throws Exception {
        var credentials=json.writeValueAsString(Map.of("email",UUID.randomUUID()+"@example.test","password","correct-horse"));
        assertThat(mvc.perform(post("/api/v1/auth/signup").contentType("application/json").content(credentials))
                .andReturn().getResponse().getStatus()).isEqualTo(201);
        var response=mvc.perform(post("/api/v1/auth/login").contentType("application/json").content(credentials))
                .andReturn().getResponse();
        assertThat(response.getStatus()).isEqualTo(200);
        var b=body(response);
        return new Login(b.path("id").asText(),response.getCookie("TEACHMI_SESSION"),b.path("csrfToken").asText());
    }
    String path(UUID problem) { return "/api/v1/sessions/"+session+"/problems/"+problem+"/attempts"; }
    String payload(String answer,String work) { return json.writeValueAsString(Map.of("answer",answer,"work",work)); }
    MockHttpServletResponse submit(UUID problem,String key,String answer,String work) throws Exception {
        return mvc.perform(post(path(problem)).cookie(cookie).header("X-CSRF-Token",csrf).header("Idempotency-Key",key)
                .contentType("application/json").content(payload(answer,work))).andReturn().getResponse();
    }
    JsonNode body(MockHttpServletResponse response) { return json.readTree(response.getContentAsByteArray()); }
    long count() { return jdbc.queryForObject("SELECT count(*) FROM teachmi.assessment_attempt WHERE session_id=?",Long.class,session); }
    void code(MockHttpServletResponse response,int status,String code) {
        assertThat(response.getStatus()).isEqualTo(status);
        assertThat(body(response).path("code").asText()).isEqualTo(code);
    }
    AssessmentAttemptService using(AnswerGrader grader) {
        var beans=new StaticListableBeanFactory();
        if (grader != null) beans.addBean("grader",grader);
        return new AssessmentAttemptService(sessions,access,problems,attempts,beans.getBeanProvider(AnswerGrader.class),json,returns);
    }
    AttemptViews.AttemptView direct(AssessmentAttemptService target,String key) {
        return transaction.execute(status -> target.submit(member,session,first,key,new AttemptViews.SubmitRequest("200만 원","600 ÷ 3")));
    }

    @Test void correctAnswerIsStoredAndOffersTheNextProblem() throws Exception {
        var response=submit(first,"first","2,000,000원","600만 원을 3년으로 나눕니다.");
        assertThat(response.getStatus()).isEqualTo(201);
        assertThat(response.getHeader("Cache-Control")).contains("no-store");
        var b=body(response);
        assertThat(b.path("judgement").asText()).isEqualTo("correct");
        assertThat(b.path("attemptNo").asInt()).isEqualTo(1);
        assertThat(b.path("nextProblemId").asText()).isEqualTo(second.toString());
        assertThat(b.path("availableActions").get(0).asText()).isEqualTo("next_problem");
        var saved=attempts.findByMemberIdAndRequestKey(member,"first").orElseThrow();
        assertThat(saved.answer).isEqualTo("2,000,000원");
        assertThat(saved.work).isEqualTo("600만 원을 3년으로 나눕니다.");
        assertThat(saved.gradedAt).isNotNull();
        assertThat(problems.findById(first).orElseThrow().confirmedAt).isNotNull();
    }
    @Test void firstWrongAnswerHidesSecretsAndIsNotConfirmed() throws Exception {
        var response=submit(first,"wrong","100만 원","600을 6으로 나눴습니다.");
        assertThat(response.getStatus()).isEqualTo(201);
        var b=body(response);
        assertThat(b.path("judgement").asText()).isEqualTo("wrong");
        assertThat(b.path("availableActions").toString()).isEqualTo("[\"reteach\",\"view_solution\"]");
        assertThat(b.path("nextProblemId").isNull()).isTrue();
        assertThat(response.getContentAsString()).doesNotContain("expectedAnswer","gradingCriteria","200만 원","600만 원 -");
        assertThat(problems.findById(first).orElseThrow().confirmedAt).isNull();
        assertThat(attempts.findByMemberIdAndRequestKey(member,"wrong").orElseThrow().reteachFocus).isNotBlank();
    }
    @Test void lastProblemFinishesAndEarlierReceiptsRemainStableAfterAbandonment() throws Exception {
        var original=body(submit(first,"first","200만원","600 ÷ 3"));
        var last=submit(second,"second","180만원","잔존가치를 뺀 원가를 배분합니다.");
        assertThat(last.getStatus()).isEqualTo(201);
        assertThat(body(last).path("availableActions").get(0).asText()).isEqualTo("finish_problems");
        assertThat(body(last).path("nextProblemId").isNull()).isTrue();
        jdbc.update("UPDATE teachmi.learning_session SET status='abandoned',version=version+1 WHERE id=?",session);
        var replay=submit(first,"first","200만원","600 ÷ 3");
        assertThat(replay.getStatus()).isEqualTo(201);
        assertThat(body(replay)).isEqualTo(original);
        assertThat(count()).isEqualTo(2);
    }
    @Test void sameKeyWithChangedAnswerWorkOrTargetIs409() throws Exception {
        assertThat(submit(first,"one","200만원","원래 풀이").getStatus()).isEqualTo(201);
        code(submit(first,"one","180만원","원래 풀이"),409,"IDEMPOTENCY_KEY_REUSED");
        code(submit(first,"one","200만원","다른 풀이"),409,"IDEMPOTENCY_KEY_REUSED");
        code(submit(second,"one","200만원","원래 풀이"),409,"IDEMPOTENCY_KEY_REUSED");
        assertThat(count()).isEqualTo(1);
    }
    @Test void aNewKeyCannotOverwriteTheFirstIndependentAnswer() throws Exception {
        var original=body(submit(first,"one","100만원","첫 풀이"));
        code(submit(first,"two","200만원","수정 풀이"),409,"FIRST_ATTEMPT_ALREADY_SUBMITTED");
        assertThat(count()).isEqualTo(1);
        assertThat(body(submit(first,"one","100만원","첫 풀이"))).isEqualTo(original);
    }
    @Test void theSecondProblemCannotBeSubmittedBeforeTheFirstIsConfirmed() throws Exception {
        code(submit(second,"two","180만원","잔존가치 차감"),409,"PREVIOUS_PROBLEM_REQUIRED");
        assertThat(count()).isZero();
    }
    @Test void anotherMemberCannotSubmitToTheOwnersProblem() throws Exception {
        var other=login();
        var response=mvc.perform(post(path(first)).cookie(other.cookie()).header("X-CSRF-Token",other.csrf())
                        .header("Idempotency-Key","foreign").contentType("application/json").content(payload("200만원","풀이")))
                .andReturn().getResponse();
        assertThat(response.getStatus()).isEqualTo(404);
        assertThat(count()).isZero();
    }
    @Test void authAndCsrfAreRequiredBeforeWrites() throws Exception {
        var request=payload("200만원","풀이");
        assertThat(mvc.perform(post(path(first)).header("Idempotency-Key","unauth").contentType("application/json")
                .content(request)).andReturn().getResponse().getStatus()).isEqualTo(401);
        assertThat(mvc.perform(post(path(first)).cookie(cookie).header("Idempotency-Key","no-csrf")
                .contentType("application/json").content(request)).andReturn().getResponse().getStatus()).isEqualTo(403);
        assertThat(mvc.perform(post(path(first)).cookie(cookie).header("X-CSRF-Token","invalid")
                        .header("Idempotency-Key","bad-csrf").contentType("application/json").content(request))
                .andReturn().getResponse().getStatus()).isEqualTo(403);
        assertThat(count()).isZero();
    }
    @Test void invalidInputsAndKeysAreRejectedWithoutSaving() throws Exception {
        for (var content:List.of("{}", "{\"answer\":null,\"work\":\"풀이\"}",payload(" ","풀이"),
                payload("200만원",""),payload("a".repeat(2001),"풀이"),payload("200만원","a".repeat(4001)))) {
            var response=mvc.perform(post(path(first)).cookie(cookie).header("X-CSRF-Token",csrf)
                            .header("Idempotency-Key","invalid").contentType("application/json").content(content))
                    .andReturn().getResponse();
            code(response,422,"INVALID_REQUEST");
        }
        for (var key:List.of(""," ","a".repeat(129))) code(submit(first,key,"200만원","풀이"),422,"INVALID_IDEMPOTENCY_KEY");
        var missing=mvc.perform(post(path(first)).cookie(cookie).header("X-CSRF-Token",csrf)
                .contentType("application/json").content(payload("200만원","풀이"))).andReturn().getResponse();
        code(missing,422,"INVALID_IDEMPOTENCY_KEY");
        var malformed=mvc.perform(post(path(first)).cookie(cookie).header("X-CSRF-Token",csrf)
                .header("Idempotency-Key","json").contentType("application/json").content("{")).andReturn().getResponse();
        code(malformed,400,"MALFORMED_JSON");
        assertThat(count()).isZero();
    }
    @Test void wrongProblemOrSessionIdsAre404() throws Exception {
        assertThat(submit(UUID.randomUUID(),"unknown","200만원","풀이").getStatus()).isEqualTo(404);
        var path="/api/v1/sessions/not-a-uuid/problems/"+first+"/attempts";
        assertThat(mvc.perform(post(path).cookie(cookie).header("X-CSRF-Token",csrf).header("Idempotency-Key","badid")
                .contentType("application/json").content(payload("200만원","풀이"))).andReturn().getResponse().getStatus()).isEqualTo(404);
    }
    @Test void simultaneousSameKeyRequestsReturnOneStoredResult() throws Exception {
        try (var executor=Executors.newFixedThreadPool(2)) {
            var a=executor.submit(() -> submit(first,"same","200만원","풀이"));
            var b=executor.submit(() -> submit(first,"same","200만원","풀이"));
            var ra=a.get(20,java.util.concurrent.TimeUnit.SECONDS); var rb=b.get(20,java.util.concurrent.TimeUnit.SECONDS);
            assertThat(ra.getStatus()).isEqualTo(201); assertThat(rb.getStatus()).isEqualTo(201);
            assertThat(body(ra)).isEqualTo(body(rb));
        }
        assertThat(count()).isEqualTo(1);
    }
    @Test void simultaneousDifferentKeysCannotCreateTwoFirstAttempts() throws Exception {
        try (var executor=Executors.newFixedThreadPool(2)) {
            var a=executor.submit(() -> submit(first,"a","200만원","풀이"));
            var b=executor.submit(() -> submit(first,"b","200만원","풀이"));
            assertThat(List.of(a.get(20,java.util.concurrent.TimeUnit.SECONDS).getStatus(),
                    b.get(20,java.util.concurrent.TimeUnit.SECONDS).getStatus())).containsExactlyInAnyOrder(201,409);
        }
        assertThat(count()).isEqualTo(1);
    }
    @Test void abandonedOrStaleLearningRejectsNewAnswers() throws Exception {
        jdbc.update("UPDATE teachmi.learning_session SET version=version+1 WHERE id=?",session);
        code(submit(first,"stale","200만원","풀이"),409,"ASSESSMENT_EVIDENCE_STALE");
        jdbc.update("UPDATE teachmi.learning_session SET status='abandoned' WHERE id=?",session);
        code(submit(first,"abandoned","200만원","풀이"),409,"SESSION_STATE_CONFLICT");
        assertThat(count()).isZero();
    }
    @Test void aMissingRealGraderIsExplicitlyPending() {
        assertThatThrownBy(() -> direct(using(null),"pending")).isInstanceOfSatisfying(ApiException.class,
                ex -> { assertThat(ex.status()).isEqualTo(503); assertThat(ex.code()).isEqualTo("ASSESSMENT_GRADING_PENDING"); });
        assertThat(count()).isZero();
    }
    @Test void failedOrInvalidGradingRollsBackAndTheSameKeyCanRetry() {
        assertThatThrownBy(() -> direct(using(input -> { throw new IllegalStateException("failure"); }),"retry"))
                .isInstanceOfSatisfying(ApiException.class,ex -> assertThat(ex.status()).isEqualTo(502));
        assertThat(count()).isZero();
        assertThatThrownBy(() -> direct(using(input -> new AnswerGrader.Grade("invented",null)),"retry"))
                .isInstanceOf(ApiException.class);
        assertThat(count()).isZero();
        var result=direct(using(new FixedAnswerGrader()),"retry");
        assertThat(result.judgement()).isEqualTo("correct");
        assertThat(count()).isEqualTo(1);
    }
    @Test void pendingReviewIsSavedWithoutBecomingWrongOrConfirmingTheProblem() {
        var result=direct(using(input -> new AnswerGrader.Grade("pending_review",null)),"review");
        assertThat(result.judgement()).isEqualTo("pending_review");
        assertThat(result.availableActions()).containsExactly("next_problem");
        assertThat(problems.findById(first).orElseThrow().confirmedAt).isNull();
        var saved=attempts.findByMemberIdAndRequestKey(member,"review").orElseThrow();
        assertThat(IndependentApplication.of(List.of(List.of(new AttemptFact(saved.attemptNo,saved.judgement,null,false)))).pendingReview()).isEqualTo(1);
    }
    @Test void theLockedSessionIsRefreshedEvenIfAlreadyReadByTheCaller() {
        assertThatThrownBy(() -> transaction.execute(status -> {
            sessions.get(member,session);
            jdbc.update("UPDATE teachmi.learning_session SET status='abandoned' WHERE id=?",session);
            return access.lockReady(member,session,null);
        })).isInstanceOfSatisfying(LearningException.class,
                ex -> assertThat(ex.code()).isEqualTo("SESSION_STATE_CONFLICT"));
    }
}
