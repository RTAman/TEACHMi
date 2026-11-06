package com.teachmi.backend.assessment;

import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Instant;
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
class AssessmentProblemApiTest {
    record Member(String id, Cookie cookie, String csrf) {}

    @Autowired WebApplicationContext context;
    @Autowired AuthFilter filter;
    @Autowired JdbcTemplate jdbc;
    @Autowired ObjectMapper json;
    MockMvc mvc;
    Member owner;
    Member other;

    @BeforeEach
    void setup() throws Exception {
        mvc = MockMvcBuilders.webAppContextSetup(context).addFilters(filter).build();
        jdbc.execute("TRUNCATE teachmi.assessment_attempt, teachmi.assessment_problem");
        jdbc.execute("TRUNCATE teachmi.app_user CASCADE");
        owner = signup("owner@example.test");
        other = signup("other@example.test");
    }

    @Test
    void unauthenticatedRequestsGet401() throws Exception {
        var response = mvc.perform(post(path(UUID.randomUUID()))).andReturn().getResponse();
        assertThat(response.getStatus()).isEqualTo(401);
        assertThat(body(response).path("code").asText()).isEqualTo("UNAUTHENTICATED");
    }

    @Test
    void problemsWaitUntilTheExplanationIsFinished() throws Exception {
        var session = session(owner, "chat", null);
        var response = start(owner, session);
        assertThat(response.getStatus()).isEqualTo(409);
        assertThat(body(response).path("code").asText()).isEqualTo("PROBLEMS_NOT_READY");
    }

    @Test
    void startFixesTwoProblemsAndARepeatedCallKeepsThem() throws Exception {
        var session = session(owner, "problem", UUID.randomUUID());
        var first = start(owner, session);
        var second = start(owner, session);

        assertThat(first.getStatus()).isEqualTo(200);
        assertThat(body(first).path("total").asInt()).isEqualTo(2);
        assertThat(body(second).path("problems").toString()).isEqualTo(body(first).path("problems").toString());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM teachmi.assessment_problem", Integer.class))
                .isEqualTo(2);
        assertThat(jdbc.queryForList("SELECT source_chunk_ids FROM teachmi.assessment_problem", String.class))
                .containsOnly("[]");
    }

    @Test
    void responsesNeverIncludeTheAnswerOrSolution() throws Exception {
        var session = session(owner, "problem", UUID.randomUUID());
        var started = start(owner, session);
        var problemId = body(started).path("problems").get(0).path("problemId").asText();
        var read = read(owner, session, problemId);

        assertThat(read.getStatus()).isEqualTo(200);
        assertThat(body(read).path("sequence").asInt()).isEqualTo(1);
        assertThat(body(read).path("total").asInt()).isEqualTo(2);
        for (var response : new MockHttpServletResponse[] {started, read}) {
            assertThat(text(response)).doesNotContain("200만 원", "180만 원", "expectedAnswer", "solution",
                    "gradingCriteria");
        }
    }

    @Test
    void anotherMemberCannotStartOrReadProblems() throws Exception {
        var session = session(owner, "problem", UUID.randomUUID());
        var problemId = body(start(owner, session)).path("problems").get(0).path("problemId").asText();

        assertThat(start(other, session).getStatus()).isEqualTo(404);
        assertThat(read(other, session, problemId).getStatus()).isEqualTo(404);
    }

    @Test
    void malformedIdsAreNotFound() throws Exception {
        var session = session(owner, "problem", UUID.randomUUID());
        assertThat(read(owner, session, "not-a-uuid").getStatus()).isEqualTo(404);
    }

    private String path(UUID session) {
        return "/api/v1/sessions/" + session + "/problems";
    }

    private MockHttpServletResponse start(Member member, UUID session) throws Exception {
        return mvc.perform(post(path(session)).cookie(member.cookie()).header("X-CSRF-Token", member.csrf()))
                .andReturn().getResponse();
    }

    private MockHttpServletResponse read(Member member, UUID session, String problemId) throws Exception {
        return mvc.perform(get(path(session) + "/" + problemId).cookie(member.cookie())).andReturn().getResponse();
    }

    private UUID session(Member member, String stage, UUID finalExplanationId) {
        var id = UUID.randomUUID();
        var now = Timestamp.from(Instant.now());
        jdbc.update("""
                INSERT INTO teachmi.learning_session
                (id, member_id, material_id, concept_id, concept_snapshot, mode, stage, status,
                 final_explanation_id, created_at, updated_at)
                VALUES (?, ?, 'm01', 'c01', ?, 'review', ?, 'active', ?, ?, ?)""",
                id, member.id(), "{\"name\":\"감가상각\",\"scope\":\"배분 이유\",\"sourceChunkIds\":[\"k01\"]}",
                stage, finalExplanationId, now, now);
        if (finalExplanationId != null) jdbc.update("""
                INSERT INTO teachmi.learning_message
                (id,session_id,sequence,role,kind,chat_stage,text,source_chunk_ids,created_at)
                VALUES (?, ?, 1, 'user', 'answer', 'wrapup', '원가를 사용 기간에 배분합니다.', '[]', now())
                """, finalExplanationId, id);
        return id;
    }

    private Member signup(String email) throws Exception {
        var credentials = json.writeValueAsString(Map.of("email", email, "password", "correct-horse"));
        mvc.perform(post("/api/v1/auth/signup").contentType("application/json").content(credentials));
        var login = mvc.perform(post("/api/v1/auth/login").contentType("application/json").content(credentials))
                .andReturn().getResponse();
        assertThat(login.getStatus()).isEqualTo(200);
        var data = body(login);
        return new Member(data.path("id").asText(), login.getCookie("TEACHMI_SESSION"), data.path("csrfToken").asText());
    }

    private JsonNode body(MockHttpServletResponse response) throws Exception {
        return json.readTree(response.getContentAsByteArray());
    }

    private String text(MockHttpServletResponse response) throws Exception {
        return response.getContentAsString(StandardCharsets.UTF_8);
    }
}
