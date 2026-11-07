package com.teachmi.backend.home;

import java.util.Map;
import java.util.UUID;
import com.teachmi.backend.auth.AuthFilter;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
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
class HomeApiTest {
    record Login(String id, Cookie cookie, String csrf) {}

    @Autowired WebApplicationContext context;
    @Autowired AuthFilter filter;
    @Autowired JdbcTemplate jdbc;
    @Autowired ObjectMapper json;
    MockMvc mvc;

    @BeforeEach
    void setup() {
        mvc = MockMvcBuilders.webAppContextSetup(context).addFilters(filter).build();
    }

    @Test
    void aNewMembersHomeIsEmpty() throws Exception {
        var data = body(home(login()));
        assertThat(data.path("firstVisit").asBoolean()).isTrue();
        assertThat(data.path("inProgress").isNull()).isTrue();
        assertThat(data.path("recentReports").size()).isZero();
    }

    @Test
    void unauthenticatedHomeIs401() throws Exception {
        var response = mvc.perform(get("/api/v1/home")).andReturn().getResponse();
        assertThat(response.getStatus()).isEqualTo(401);
        assertThat(body(response).path("code").asText()).isEqualTo("UNAUTHENTICATED");
    }

    @Test
    void anActiveLearningResumesAtItsStage() throws Exception {
        var member = login();
        var session = readySession(member.id());

        var progress = body(home(member)).path("inProgress");
        assertThat(progress.path("sessionId").asText()).isEqualTo(session.toString());
        assertThat(progress.path("conceptName").asText()).isEqualTo("감가상각");
        assertThat(progress.path("resumeStage").asText()).isEqualTo("problem");
        assertThat(progress.path("resumePath").asText()).isEqualTo("/api/v1/sessions/" + session + "/problems");
    }

    @Test
    void aConfirmedReportStaysOnHomeAfterReloginAndLeavesInProgress() throws Exception {
        var member = login();
        var session = readySession(member.id());
        var problems = body(write(member, "/api/v1/sessions/" + session + "/problems", null)).path("problems");
        submit(member, session, problems.get(0).path("problemId").asText(), "a1", "200만원");
        submit(member, session, problems.get(1).path("problemId").asText(), "a2", "100만원");
        mvc.perform(post("/api/v1/sessions/" + session + "/problems/" + problems.get(1).path("problemId").asText()
                + "/solution").cookie(member.cookie()).header("X-CSRF-Token", member.csrf()));
        assertThat(write(member, "/api/v1/sessions/" + session + "/report", "p1").getStatus()).isEqualTo(200);

        var ready = body(home(member));
        assertThat(ready.path("inProgress").path("resumeStage").asText()).as("확인 전에는 리포트로 이어간다")
                .isEqualTo("report");
        assertThat(ready.path("recentReports").get(0).path("status").asText()).isEqualTo("ready");

        assertThat(write(member, "/api/v1/sessions/" + session + "/report/confirm", "c1").getStatus()).isEqualTo(200);
        var again = relogin(member);
        var done = body(home(again));
        assertThat(done.path("firstVisit").asBoolean()).isFalse();
        assertThat(done.path("inProgress").isNull()).isTrue();
        var report = done.path("recentReports").get(0);
        assertThat(report.path("status").asText()).isEqualTo("confirmed");
        assertThat(report.path("independentCorrect").asInt()).isEqualTo(1);
        assertThat(report.path("total").asInt()).isEqualTo(2);
        assertThat(report.path("path").asText()).isEqualTo("/api/v1/sessions/" + session + "/report");
    }

    @Test
    void anotherMembersLearningIsNotShown() throws Exception {
        var owner = login();
        readySession(owner.id());
        var data = body(home(login()));
        assertThat(data.path("firstVisit").asBoolean()).isTrue();
        assertThat(data.path("inProgress").isNull()).isTrue();
    }

    /** 처음 설명(case)과 마지막 설명(wrapup)을 가진, 문제 풀이 직전의 학습을 만든다. */
    private UUID readySession(String member) {
        var id = UUID.randomUUID();
        var initial = UUID.randomUUID();
        var last = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO teachmi.learning_session
                (id,member_id,material_id,concept_id,concept_snapshot,mode,stage,status,version,
                 initial_answer_id,final_explanation_id,created_at,updated_at)
                VALUES (?,?,'m','c','{"name":"감가상각","scope":"원가 배분","sourceChunkIds":["k01"]}',
                'review','problem','active',7,?,?,now(),now())
                """, id, member, initial, last);
        message(id, initial, 1, "case", "처음 설명입니다.");
        message(id, last, 2, "wrapup", "마지막 설명입니다.");
        return id;
    }

    private void message(UUID sessionId, UUID id, long sequence, String stage, String text) {
        jdbc.update("""
                INSERT INTO teachmi.learning_message
                (id,session_id,sequence,role,kind,chat_stage,text,source_chunk_ids,created_at)
                VALUES (?,?,?,'user','answer',?,?,'[]',now())
                """, id, sessionId, sequence, stage, text);
    }

    private void submit(Login member, UUID session, String problemId, String key, String answer) throws Exception {
        var response = mvc.perform(post("/api/v1/sessions/" + session + "/problems/" + problemId + "/attempts")
                        .cookie(member.cookie()).header("X-CSRF-Token", member.csrf()).header("Idempotency-Key", key)
                        .contentType("application/json")
                        .content(json.writeValueAsString(Map.of("answer", answer, "work", "원가를 내용연수로 나눈다."))))
                .andReturn().getResponse();
        assertThat(response.getStatus()).isEqualTo(201);
    }

    private MockHttpServletResponse write(Login member, String path, String key) throws Exception {
        var request = post(path).cookie(member.cookie()).header("X-CSRF-Token", member.csrf());
        if (key != null) {
            request.header("Idempotency-Key", key);
        }
        return mvc.perform(request).andReturn().getResponse();
    }

    private MockHttpServletResponse home(Login member) throws Exception {
        return mvc.perform(get("/api/v1/home").cookie(member.cookie())).andReturn().getResponse();
    }

    private Login login() throws Exception {
        var email = UUID.randomUUID() + "@example.test";
        var credentials = json.writeValueAsString(Map.of("email", email, "password", "correct-horse"));
        assertThat(mvc.perform(post("/api/v1/auth/signup").contentType("application/json").content(credentials))
                .andReturn().getResponse().getStatus()).isEqualTo(201);
        return signIn(credentials);
    }

    /** 로그아웃 후 같은 계정으로 다시 로그인한다. 학습 기록은 계정에 남아야 한다. */
    private Login relogin(Login member) throws Exception {
        var email = jdbc.queryForObject("SELECT email FROM teachmi.app_user WHERE id=?::uuid", String.class, member.id());
        assertThat(mvc.perform(post("/api/v1/auth/logout").cookie(member.cookie()).header("X-CSRF-Token", member.csrf()))
                .andReturn().getResponse().getStatus()).isEqualTo(204);
        return signIn(json.writeValueAsString(Map.of("email", email, "password", "correct-horse")));
    }

    private Login signIn(String credentials) throws Exception {
        var response = mvc.perform(post("/api/v1/auth/login").contentType("application/json").content(credentials))
                .andReturn().getResponse();
        assertThat(response.getStatus()).isEqualTo(200);
        var data = body(response);
        return new Login(data.path("id").asText(), response.getCookie("TEACHMI_SESSION"), data.path("csrfToken").asText());
    }

    private JsonNode body(MockHttpServletResponse response) {
        return json.readTree(response.getContentAsByteArray());
    }
}