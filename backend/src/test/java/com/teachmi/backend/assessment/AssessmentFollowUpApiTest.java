package com.teachmi.backend.assessment;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import com.teachmi.backend.auth.AuthFilter;
import com.teachmi.backend.learning.LearningContentGenerator;
import com.teachmi.backend.learning.LearningReteachGenerator;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

@SpringBootTest(properties = "teachmi.assessment.demo-enabled=true")
class AssessmentFollowUpApiTest {
    /** 실제 Gemini 대신 재설명 첫 질문을 고정한다. 비용이 드는 호출은 하지 않는다. */
    @TestConfiguration(proxyBeanMethods = false)
    static class StubGeneration {
        // 로컬 .env에서 Gemini를 켜 둔 경우에도 이 테스트용 생성기를 쓰도록 우선순위를 준다.
        @Bean
        @Primary
        LearningReteachGenerator testReteachOpening() {
            return context -> new LearningContentGenerator.Question("비용을 나누는 이유를 다른 예로 설명해 줄래?",
                    List.of("trig-source"));
        }
    }

    record Login(String id, Cookie cookie, String csrf) {}

    @Autowired WebApplicationContext context;
    @Autowired AuthFilter filter;
    @Autowired JdbcTemplate jdbc;
    @Autowired ObjectMapper json;
    MockMvc mvc;
    Login owner;
    UUID session, first, second;

    @BeforeEach
    void setup() throws Exception {
        mvc = MockMvcBuilders.webAppContextSetup(context).addFilters(filter).build();
        owner = login();
        session = AssessmentFixtures.ready(jdbc, owner.id());
        var result = mvc.perform(post("/api/v1/sessions/" + session + "/problems").cookie(owner.cookie())
                .header("X-CSRF-Token", owner.csrf())).andReturn().getResponse();
        assertThat(result.getStatus()).isEqualTo(200);
        var set = body(result).path("problems");
        first = UUID.fromString(set.get(0).path("problemId").asText());
        second = UUID.fromString(set.get(1).path("problemId").asText());
    }

    @Test
    void aWrongAttemptStartsReteachInBsChatWithoutLeakingTheAnswer() throws Exception {
        var attemptId = wrong(first, "w1");
        var response = reteach(owner, first, attemptId, "r1");

        assertThat(response.getStatus()).isEqualTo(200);
        assertThat(body(response).path("stage").asText()).isEqualTo("chat");
        assertThat(body(response).path("activeReteach").path("problemId").asText()).isEqualTo(first.toString());
        assertThat(text(response)).doesNotContain("200만 원", "(600만 원 - 0원)");
    }

    @Test
    void aCorrectAttemptCannotBeRetaught() throws Exception {
        var correct = submit(first, "c1", "200만원");
        assertThat(correct.getStatus()).isEqualTo(201);
        code(reteach(owner, first, body(correct).path("attemptId").asText(), "r1"), 409, "WRONG_ATTEMPT_REQUIRED");
    }

    @Test
    void theRetryIsASecondAttemptAndTheNextProblemStillWorks() throws Exception {
        var attemptId = wrong(first, "w1");
        var reteachId = startReteach(first, attemptId);
        finishReteach(reteachId);

        var retry = submit(first, "w2", "200만원");
        assertThat(retry.getStatus()).isEqualTo(201);
        assertThat(body(retry).path("attemptNo").asInt()).isEqualTo(2);
        assertThat(body(retry).path("judgement").asText()).isEqualTo("correct");
        assertThat(jdbc.queryForObject("SELECT reteach_id::text FROM teachmi.assessment_attempt"
                + " WHERE problem_id=? AND attempt_no=2", String.class, first)).isEqualTo(reteachId);
        assertThat(jdbc.queryForObject("SELECT judgement FROM teachmi.assessment_attempt"
                + " WHERE problem_id=? AND attempt_no=1", String.class, first)).as("첫 독립 시도는 그대로 남는다").isEqualTo("wrong");
        assertThat(jdbc.queryForObject("SELECT return_reteach_id FROM teachmi.learning_session WHERE id=?",
                UUID.class, session)).as("B의 복귀 표시가 소비된다").isNull();
        assertThat(submit(second, "s1", "180만원").getStatus()).as("재설명으로 올라간 version 때문에 막히지 않는다")
                .isEqualTo(201);
    }

    @Test
    void anotherProblemWaitsUntilTheRetaughtProblemIsRetried() throws Exception {
        var attemptId = wrong(first, "w1");
        finishReteach(startReteach(first, attemptId));
        code(submit(second, "s1", "180만원"), 409, "RETRY_PENDING");
    }

    @Test
    void theSolutionOpensOnlyAfterGradingAndClosesTheProblem() throws Exception {
        code(solution(owner, first), 409, "SOLUTION_NOT_AVAILABLE");
        var attemptId = wrong(first, "w1");

        var opened = solution(owner, first);
        assertThat(opened.getStatus()).isEqualTo(200);
        assertThat(body(opened).path("answer").asText()).isEqualTo("200만 원");
        assertThat(body(opened).path("nextProblemId").asText()).isEqualTo(second.toString());
        assertThat(solution(owner, first).getStatus()).as("다시 열어도 같은 해설").isEqualTo(200);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM teachmi.assessment_problem"
                + " WHERE id=? AND solution_viewed_at IS NOT NULL AND confirmed_at IS NOT NULL", Long.class, first))
                .isEqualTo(1);

        code(submit(first, "w2", "200만원"), 409, "FIRST_ATTEMPT_ALREADY_SUBMITTED");
        code(reteach(owner, first, attemptId, "r1"), 409, "SOLUTION_ALREADY_VIEWED");
        assertThat(submit(second, "s1", "180만원").getStatus()).as("해설로 확정되면 다음 문항으로 간다").isEqualTo(201);
    }

    @Test
    void viewingTheSolutionAfterReturningConsumesTheReturn() throws Exception {
        var attemptId = wrong(first, "w1");
        finishReteach(startReteach(first, attemptId));

        assertThat(solution(owner, first).getStatus()).isEqualTo(200);
        assertThat(jdbc.queryForObject("SELECT return_reteach_id FROM teachmi.learning_session WHERE id=?",
                UUID.class, session)).isNull();
    }

    @Test
    void changedLearningEvidenceBlocksANewReteach() throws Exception {
        var attemptId = wrong(first, "w1");
        jdbc.update("UPDATE teachmi.learning_session SET version=version+1 WHERE id=?", session);
        code(reteach(owner, first, attemptId, "r1"), 409, "ASSESSMENT_EVIDENCE_STALE");
    }

    @Test
    void anotherMemberCannotReteachOrSeeTheSolution() throws Exception {
        var attemptId = wrong(first, "w1");
        var other = login();
        assertThat(reteach(other, first, attemptId, "r1").getStatus()).isEqualTo(404);
        assertThat(solution(other, first).getStatus()).isEqualTo(404);
    }

    /** B의 재설명 대화를 끝까지 진행하는 대신, 대화가 끝난 뒤 B가 남기는 상태를 만든다. version도 실제처럼 올린다. */
    private void finishReteach(String reteachId) {
        jdbc.update("UPDATE teachmi.learning_reteach SET state='returned', completed_at=now() WHERE id=?::uuid",
                reteachId);
        jdbc.update("""
                UPDATE teachmi.learning_session
                SET stage='problem', active_reteach_id=NULL, return_reteach_id=?::uuid, version=version+1
                WHERE id=?""", reteachId, session);
    }

    private String startReteach(UUID problem, String attemptId) throws Exception {
        var response = reteach(owner, problem, attemptId, "r-" + UUID.randomUUID());
        assertThat(response.getStatus()).isEqualTo(200);
        return body(response).path("activeReteach").path("reteachId").asText();
    }

    private String wrong(UUID problem, String key) throws Exception {
        var response = submit(problem, key, "100만원");
        assertThat(response.getStatus()).isEqualTo(201);
        assertThat(body(response).path("judgement").asText()).isEqualTo("wrong");
        return body(response).path("attemptId").asText();
    }

    private long version() {
        return jdbc.queryForObject("SELECT version FROM teachmi.learning_session WHERE id=?", Long.class, session);
    }

    private String path(UUID problem) {
        return "/api/v1/sessions/" + session + "/problems/" + problem;
    }

    private MockHttpServletResponse submit(UUID problem, String key, String answer) throws Exception {
        return mvc.perform(post(path(problem) + "/attempts").cookie(owner.cookie()).header("X-CSRF-Token", owner.csrf())
                        .header("Idempotency-Key", key).contentType("application/json")
                        .content(json.writeValueAsString(Map.of("answer", answer, "work", "원가를 내용연수로 나눈다."))))
                .andReturn().getResponse();
    }

    private MockHttpServletResponse reteach(Login member, UUID problem, String attemptId, String key) throws Exception {
        return mvc.perform(post(path(problem) + "/reteach").cookie(member.cookie()).header("X-CSRF-Token", member.csrf())
                        .header("Idempotency-Key", key).contentType("application/json")
                        .content(json.writeValueAsString(Map.of("attemptId", attemptId, "expectedVersion", version()))))
                .andReturn().getResponse();
    }

    private MockHttpServletResponse solution(Login member, UUID problem) throws Exception {
        return mvc.perform(post(path(problem) + "/solution").cookie(member.cookie())
                .header("X-CSRF-Token", member.csrf())).andReturn().getResponse();
    }

    private Login login() throws Exception {
        var credentials = json.writeValueAsString(Map.of("email", UUID.randomUUID() + "@example.test",
                "password", "correct-horse"));
        assertThat(mvc.perform(post("/api/v1/auth/signup").contentType("application/json").content(credentials))
                .andReturn().getResponse().getStatus()).isEqualTo(201);
        var response = mvc.perform(post("/api/v1/auth/login").contentType("application/json").content(credentials))
                .andReturn().getResponse();
        assertThat(response.getStatus()).isEqualTo(200);
        var data = body(response);
        return new Login(data.path("id").asText(), response.getCookie("TEACHMI_SESSION"), data.path("csrfToken").asText());
    }

    private void code(MockHttpServletResponse response, int status, String code) {
        assertThat(response.getStatus()).isEqualTo(status);
        assertThat(body(response).path("code").asText()).isEqualTo(code);
    }

    private JsonNode body(MockHttpServletResponse response) {
        return json.readTree(response.getContentAsByteArray());
    }

    private String text(MockHttpServletResponse response) throws Exception {
        return response.getContentAsString(StandardCharsets.UTF_8);
    }
}