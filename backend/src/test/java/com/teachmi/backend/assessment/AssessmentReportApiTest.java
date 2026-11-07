package com.teachmi.backend.assessment;

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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

@SpringBootTest(properties = "teachmi.assessment.demo-enabled=true")
class AssessmentReportApiTest {
    /** 시나리오 D(재설명 후 회복)에 쓰는 재설명 첫 질문. 실제 Gemini는 호출하지 않는다. */
    @TestConfiguration(proxyBeanMethods = false)
    static class StubGeneration {
        // 로컬 .env에서 Gemini를 켜 둔 경우에도 이 테스트용 생성기를 쓰도록 우선순위를 준다.
        @Bean
        @Primary
        LearningReteachGenerator reportTestReteachOpening() {
            return context -> new LearningContentGenerator.Question("배분하는 이유를 다시 설명해 줄래?",
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
        session = readySession(owner.id());
        var result = mvc.perform(post("/api/v1/sessions/" + session + "/problems").cookie(owner.cookie())
                .header("X-CSRF-Token", owner.csrf())).andReturn().getResponse();
        assertThat(result.getStatus()).isEqualTo(200);
        var set = body(result).path("problems");
        first = UUID.fromString(set.get(0).path("problemId").asText());
        second = UUID.fromString(set.get(1).path("problemId").asText());
    }

    @Test
    void theReportWaitsUntilEveryProblemIsFinished() throws Exception {
        code(getReport(owner), 409, "REPORT_NOT_READY");
        submit(first, "a1", "200만원");
        code(publish(owner, "p1"), 409, "ASSESSMENT_NOT_READY");
        submit(second, "a2", "100만원");
        code(publish(owner, "p2"), 409, "ASSESSMENT_NOT_READY");
        assertThat(stage()).isEqualTo("problem");
    }

    @Test
    void scenarioA_bothFirstTryCorrectMovesTheSessionToReport() throws Exception {
        submit(first, "a1", "200만원");
        submit(second, "a2", "180만원");

        var report = publish(owner, "p1");
        assertThat(report.getStatus()).isEqualTo(200);
        var data = body(report);
        assertThat(data.path("status").asText()).isEqualTo("ready");
        assertThat(data.path("application").path("independentCorrect").asInt()).isEqualTo(2);
        assertThat(data.path("application").path("total").asInt()).isEqualTo(2);
        assertThat(data.path("explanation").path("initialAnswer").path("text").asText()).isEqualTo("처음 설명입니다.");
        assertThat(data.path("explanation").path("finalExplanation").path("text").asText()).isEqualTo("마지막 설명입니다.");
        assertThat(data.path("explanation").path("result").asText()).isEqualTo("independent");
        assertThat(data.path("nextSteps").size()).isZero();
        assertThat(stage()).isEqualTo("report");

        var again = publish(owner, "p2");
        assertThat(body(again).path("reportId").asText()).as("두 번 만들어지지 않는다")
                .isEqualTo(data.path("reportId").asText());
        assertThat(body(getReport(owner)).path("reportId").asText()).isEqualTo(data.path("reportId").asText());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM teachmi.assessment_report WHERE session_id=?",
                Long.class, session)).isEqualTo(1);
    }

    @Test
    void scenarioD_recoveryAfterReteachIsShownButNotCountedAsIndependent() throws Exception {
        var attemptId = body(submit(first, "a1", "100만원")).path("attemptId").asText();
        var reteach = mvc.perform(post(path() + "/problems/" + first + "/reteach").cookie(owner.cookie())
                        .header("X-CSRF-Token", owner.csrf()).header("Idempotency-Key", "r1").contentType("application/json")
                        .content(json.writeValueAsString(Map.of("attemptId", attemptId, "expectedVersion", version()))))
                .andReturn().getResponse();
        assertThat(reteach.getStatus()).isEqualTo(200);
        finishReteach(body(reteach).path("activeReteach").path("reteachId").asText());
        submit(first, "a2", "200만원");
        submit(second, "a3", "180만원");

        var data = body(publish(owner, "p1"));
        var problems = data.path("application").path("problems");
        assertThat(data.path("application").path("independentCorrect").asInt()).isEqualTo(1);
        assertThat(problems.get(0).path("outcome").asText()).isEqualTo("CORRECT_AFTER_RETEACH");
        assertThat(problems.get(0).path("attempts").size()).isEqualTo(2);
        assertThat(problems.get(1).path("outcome").asText()).isEqualTo("FIRST_TRY_CORRECT");
        assertThat(data.path("nextSteps").get(0).path("problemId").asText()).isEqualTo(first.toString());
        assertThat(data.path("explanation").path("reteachCount").asInt()).isEqualTo(1);
    }

    @Test
    void aWrongAnswerFinishedWithTheSolutionCountsAsWrong() throws Exception {
        submit(first, "a1", "100만원");
        assertThat(mvc.perform(post(path() + "/problems/" + first + "/solution").cookie(owner.cookie())
                .header("X-CSRF-Token", owner.csrf())).andReturn().getResponse().getStatus()).isEqualTo(200);
        submit(second, "a2", "180만원");

        var problems = body(publish(owner, "p1")).path("application").path("problems");
        assertThat(problems.get(0).path("outcome").asText()).isEqualTo("WRONG");
        assertThat(problems.get(0).path("solutionViewed").asBoolean()).isTrue();
    }

    @Test
    void confirmingTheReportCompletesTheLearning() throws Exception {
        code(confirm(owner, "c0"), 409, "REPORT_NOT_READY");
        submit(first, "a1", "200만원");
        submit(second, "a2", "180만원");
        publish(owner, "p1");

        var confirmed = confirm(owner, "c1");
        assertThat(confirmed.getStatus()).isEqualTo(200);
        assertThat(body(confirmed).path("status").asText()).isEqualTo("confirmed");
        assertThat(jdbc.queryForObject("SELECT status FROM teachmi.learning_session WHERE id=?", String.class, session))
                .isEqualTo("completed");
        assertThat(confirm(owner, "c2").getStatus()).as("다시 눌러도 성공").isEqualTo(200);
        assertThat(body(getReport(owner)).path("status").asText()).as("종료 후에도 다시 연다").isEqualTo("confirmed");
    }

    @Test
    void changedLearningEvidenceBlocksTheReport() throws Exception {
        submit(first, "a1", "200만원");
        submit(second, "a2", "180만원");
        jdbc.update("UPDATE teachmi.learning_session SET version=version+1 WHERE id=?", session);
        code(publish(owner, "p1"), 409, "ASSESSMENT_EVIDENCE_STALE");
        assertThat(stage()).isEqualTo("problem");
    }

    @Test
    void anotherMemberCannotReadPublishOrConfirm() throws Exception {
        submit(first, "a1", "200만원");
        submit(second, "a2", "180만원");
        publish(owner, "p1");
        var other = login();

        assertThat(getReport(other).getStatus()).isEqualTo(404);
        assertThat(publish(other, "p2").getStatus()).isEqualTo(404);
        assertThat(confirm(other, "c1").getStatus()).isEqualTo(404);
    }

    /**
     * 실제 대화처럼 처음 설명(case)과 마지막 설명(wrapup)을 가진 학습을 만든다.
     * B의 근거 조회는 마지막 설명보다 앞선 처음 설명이 없으면 연결 오류로 본다.
     */
    private UUID readySession(String member) {
        var id = UUID.randomUUID();
        var initial = UUID.randomUUID();
        var last = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO teachmi.learning_session
                (id,member_id,material_id,concept_id,concept_snapshot,mode,stage,status,version,
                 initial_answer_id,final_explanation_id,created_at,updated_at)
                VALUES (?,?,'m','c','{"name":"감가상각","scope":"원가 배분","sourceChunkIds":["trig-source"]}',
                'review','problem','active',7,?,?,now(),now())
                """, id, member, initial, last);
        message(id, initial, 1, "case", "처음 설명입니다.", null);
        message(id, last, 2, "wrapup", "마지막 설명입니다.", null);
        return id;
    }

    private void message(UUID sessionId, UUID id, long sequence, String stage, String text, UUID reteachId) {
        jdbc.update("""
                INSERT INTO teachmi.learning_message
                (id,session_id,sequence,role,kind,chat_stage,text,source_chunk_ids,created_at,reteach_id)
                VALUES (?,?,?,'user','answer',?,?,'[]',now(),?)
                """, id, sessionId, sequence, stage, text, reteachId);
    }

    /** B의 재설명 대화가 끝난 뒤의 상태를 만든다. 재설명의 처음·마지막 설명도 남기고 version도 실제처럼 올린다. */
    private void finishReteach(String reteachId) {
        var episode = UUID.fromString(reteachId);
        long next = jdbc.queryForObject("SELECT max(sequence) FROM teachmi.learning_message WHERE session_id=?",
                Long.class, session) + 1;
        var initial = UUID.randomUUID();
        var last = UUID.randomUUID();
        message(session, initial, next, "case", "다시 한 처음 설명입니다.", episode);
        message(session, last, next + 1, "wrapup", "다시 한 마지막 설명입니다.", episode);
        jdbc.update("""
                UPDATE teachmi.learning_reteach
                SET state='returned', completed_at=now(), initial_answer_id=?, final_explanation_id=?
                WHERE id=?""", initial, last, episode);
        jdbc.update("""
                UPDATE teachmi.learning_session
                SET stage='problem', active_reteach_id=NULL, return_reteach_id=?, version=version+1
                WHERE id=?""", episode, session);
    }

    private String stage() {
        return jdbc.queryForObject("SELECT stage FROM teachmi.learning_session WHERE id=?", String.class, session);
    }

    private long version() {
        return jdbc.queryForObject("SELECT version FROM teachmi.learning_session WHERE id=?", Long.class, session);
    }

    private String path() {
        return "/api/v1/sessions/" + session;
    }

    private MockHttpServletResponse submit(UUID problem, String key, String answer) throws Exception {
        var response = mvc.perform(post(path() + "/problems/" + problem + "/attempts").cookie(owner.cookie())
                        .header("X-CSRF-Token", owner.csrf()).header("Idempotency-Key", key).contentType("application/json")
                        .content(json.writeValueAsString(Map.of("answer", answer, "work", "원가를 내용연수로 나눈다."))))
                .andReturn().getResponse();
        assertThat(response.getStatus()).isEqualTo(201);
        return response;
    }

    private MockHttpServletResponse publish(Login member, String key) throws Exception {
        return mvc.perform(post(path() + "/report").cookie(member.cookie()).header("X-CSRF-Token", member.csrf())
                .header("Idempotency-Key", key)).andReturn().getResponse();
    }

    private MockHttpServletResponse confirm(Login member, String key) throws Exception {
        return mvc.perform(post(path() + "/report/confirm").cookie(member.cookie())
                .header("X-CSRF-Token", member.csrf()).header("Idempotency-Key", key)).andReturn().getResponse();
    }

    private MockHttpServletResponse getReport(Login member) throws Exception {
        return mvc.perform(get(path() + "/report").cookie(member.cookie())).andReturn().getResponse();
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
}