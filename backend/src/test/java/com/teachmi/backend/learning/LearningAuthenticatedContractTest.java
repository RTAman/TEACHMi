package com.teachmi.backend.learning;

import java.nio.file.Path;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;
import com.atlassian.oai.validator.OpenApiInteractionValidator;
import com.atlassian.oai.validator.model.Request;
import com.atlassian.oai.validator.model.SimpleRequest;
import com.atlassian.oai.validator.model.SimpleResponse;
import com.teachmi.backend.TeachmiApplication;
import com.teachmi.backend.auth.AuthFilter;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;

/** Actual A filter, sessions, ownership and raw sources; only AI is replaced. No paid requests. */
@SpringBootTest(classes = TeachmiApplication.class)
@Import(LearningAuthenticatedContractTest.Generation.class)
class LearningAuthenticatedContractTest {
    static final String LECTURE = "감가상각은 유형자산의 취득원가를 사용하는 기간에 체계적으로 배분하는 과정이다.";
    static final OpenApiInteractionValidator CONTRACT = OpenApiInteractionValidator
            .createForSpecificationUrl(Path.of("api/openapi.json").toUri().toString()).build();
    @TestConfiguration(proxyBeanMethods = false)
    static class Generation {
        @Bean TestAI authenticatedTestAI(LearningSourceReader sources) { return new TestAI(sources); }
    }
    static class TestAI implements LearningContentGenerator, LearningDialogueGenerator {
        final LearningSourceReader sources;
        TestAI(LearningSourceReader sources) { this.sources = sources; }
        List<String> evidence(LearningContentGenerator.Context context) {
            var source = sources.readOwned(context.memberId(), context.materialId(), context.conceptId(), context.concept().sourceChunkIds());
            assertThat(source.chunks().getFirst().text()).isEqualTo(LECTURE);
            return source.chunks().stream().map(LearningSourceReader.Chunk::id).toList();
        }
        @Override public Summary summarize(LearningContentGenerator.Context context) {
            return new Summary("원가의 기간 배분", List.of("사용 기간별 배분 예시"), List.of("감가상각"), "배분하는 이유는?", evidence(context));
        }
        @Override public Question openingQuestion(LearningContentGenerator.Context context) {
            return new Question("자산 원가를 사용 기간에 배분하는 이유를 설명해 줄래?", evidence(context));
        }
        @Override public Reply reply(LearningDialogueGenerator.Context context) {
            return new Reply("선택한 개념의 의미와 이유를 설명해 줄래?", context.kind().equals("answer"), evidence(context.learning()));
        }
    }
    record Member(String id, Cookie cookie, String csrf, String email) {}
    @Autowired WebApplicationContext context;
    @Autowired AuthFilter filter;
    @Autowired JdbcTemplate jdbc;
    @Autowired ObjectMapper json;
    MockMvc mvc;
    Member member;
    JsonNode material;

    @BeforeEach void setup() throws Exception {
        mvc = MockMvcBuilders.webAppContextSetup(context).addFilters(filter).build();
        jdbc.execute("TRUNCATE teachmi.learning_reteach,teachmi.learning_turn,teachmi.learning_support_event,"
                + "teachmi.learning_session_request,teachmi.learning_message,teachmi.learning_session");
        jdbc.execute("TRUNCATE teachmi.app_user CASCADE");
        member = signup("a-b@example.test");
        var result = mvc.perform(auth(post("/api/v1/materials"), member).header("Idempotency-Key", "material")
                .contentType("application/json").content(json.writeValueAsString(Map.of("text", LECTURE,
                        "concept", Map.of("name", "감가상각", "scope", "사용 기간에 배분하는 이유"))))).andReturn();
        assertThat(result.getResponse().getStatus()).isEqualTo(201);
        material = json.readTree(result.getResponse().getContentAsByteArray());
    }

    @Test void actualCookiesRequireCsrfBeforeAnyBWriteAndThe403MatchesBsContract() throws Exception {
        var body = sessionBody();
        var blocked = b(post("/api/v1/sessions").cookie(member.cookie()).header("Idempotency-Key", "create")
                .contentType("application/json").content(json.writeValueAsString(body)), 403, false);
        assertThat(blocked.path("code").asText()).isEqualTo("CSRF_TOKEN_INVALID");
        assertThat(blocked.has("currentSession")).isFalse();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM teachmi.learning_session", Long.class)).isZero();
        b(post("/api/v1/sessions").cookie(member.cookie()).header("X-CSRF-Token", "incorrect")
                .header("Idempotency-Key", "create").contentType("application/json").content(json.writeValueAsString(body)), 403, true);
        var created = write(post("/api/v1/sessions"), "create", body, 201);
        var other = signup("other@example.test");
        b(get("/api/v1/sessions/" + created.path("sessionId").asText()).cookie(other.cookie()), 404, true);
        b(get("/api/v1/sessions/" + created.path("sessionId").asText()), 401, false);
    }

    @Test void realAMaterialsAndSourcesSupportBLearningAndReloginWithoutLosingTheSession() throws Exception {
        String id = write(post("/api/v1/sessions"), "create", sessionBody(), 201).path("sessionId").asText();
        var state = write(put(path(id) + "/mode"), "mode", Map.of("mode", "first", "expectedVersion", 0), 200);
        state = write(post(path(id) + "/chat/start"), "start", Map.of("expectedVersion", state.path("version").asLong()), 200);
        state = write(post(path(id) + "/turns"), "help", Map.of("kind", "hint", "expectedVersion", state.path("version").asLong()), 200);
        for (int i = 0; i < 4; i++) state = write(post(path(id) + "/turns"), "answer-" + i,
                Map.of("kind", "answer", "text", "원가를 사용 기간에 배분합니다.", "expectedVersion", state.path("version").asLong()), 200);
        assertThat(state.path("stage").asText()).isEqualTo("problem");
        var evidence = b(get(path(id) + "/evidence").cookie(member.cookie()), 200, true);
        assertThat(evidence.path("originalLearning").path("evidence").path("support").path("delivered").path("hint").asInt()).isEqualTo(1);
        b(auth(post("/api/v1/auth/logout"), member), 204, true, false);
        b(get("/api/v1/sessions/active").cookie(member.cookie()), 401, false);
        member = login(member.email());
        var restored = b(get("/api/v1/sessions/active").cookie(member.cookie()), 200, true);
        assertThat(restored.path("sessionId").asText()).isEqualTo(id);
        assertThat(restored.path("messages")).isEqualTo(state.path("messages"));
        // C still has no production problem source; A's integration must not fake its completion.
        var pending = write(post(path(id) + "/reteach"), "reteach", Map.of("problemId", "p01", "attemptId", "a01",
                "expectedVersion", state.path("version").asLong()), 503);
        assertThat(pending.path("code").asText()).isEqualTo("LEARNING_PROBLEM_INTEGRATION_PENDING");
    }

    @Test void expiredACookiesCannotReadBSessionState() throws Exception {
        write(post("/api/v1/sessions"), "create", sessionBody(), 201);
        jdbc.update("UPDATE teachmi.auth_session SET expires_at=now()-interval '1 second' WHERE user_id=?", UUID.fromString(member.id()));
        b(get("/api/v1/sessions/active").cookie(member.cookie()), 401, false);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM teachmi.learning_session", Long.class)).isEqualTo(1);
    }

    private Map<String, Object> sessionBody() {
        return Map.of("materialId", material.path("materialId").asText(), "conceptId", material.path("concepts").get(0).path("conceptId").asText());
    }
    private String path(String id) { return "/api/v1/sessions/" + id; }
    private MockHttpServletRequestBuilder auth(MockHttpServletRequestBuilder request, Member current) {
        return request.cookie(current.cookie()).header("X-CSRF-Token", current.csrf());
    }
    private JsonNode write(MockHttpServletRequestBuilder request, String key, Object body, int status) throws Exception {
        return b(auth(request, member).header("Idempotency-Key", key).contentType("application/json")
                .content(json.writeValueAsString(body)), status, true);
    }
    private JsonNode b(MockHttpServletRequestBuilder request, int status, boolean validRequest) throws Exception {
        return b(request, status, validRequest, true);
    }
    private JsonNode b(MockHttpServletRequestBuilder request, int status, boolean validRequest, boolean validate) throws Exception {
        var result = mvc.perform(request).andReturn();
        var output = result.getResponse(); var input = result.getRequest();
        assertThat(output.getStatus()).isEqualTo(status);
        if (validate) {
            var response = SimpleResponse.Builder.status(status);
            output.getHeaderNames().forEach(name -> response.withHeader(name, List.copyOf(output.getHeaders(name))));
            if (output.getContentAsByteArray().length > 0) response.withBody(output.getContentAsByteArray());
            var responseReport = CONTRACT.validateResponse(input.getRequestURI(), Request.Method.valueOf(input.getMethod()), response.build());
            assertThat(responseReport.hasErrors()).as(responseReport.getMessages().toString()).isFalse();
            if (validRequest || status == 403) {
                var sample = new SimpleRequest.Builder(Request.Method.valueOf(input.getMethod()), input.getRequestURI());
                input.getHeaderNames().asIterator().forEachRemaining(name -> sample.withHeader(name, Collections.list(input.getHeaders(name))));
                if (input.getCookies() != null) sample.withHeader("Cookie", Arrays.stream(input.getCookies())
                        .map(cookie -> cookie.getName() + "=" + cookie.getValue()).collect(Collectors.joining("; ")));
                if (input.getContentAsByteArray() != null && input.getContentAsByteArray().length > 0) sample.withBody(input.getContentAsByteArray());
                var requestReport = CONTRACT.validateRequest(sample.build());
                assertThat(requestReport.hasErrors()).as(requestReport.getMessages().toString()).isEqualTo(!validRequest);
            }
        }
        return output.getContentAsByteArray().length == 0 ? json.nullNode() : json.readTree(output.getContentAsByteArray());
    }
    private Member signup(String email) throws Exception {
        var result = mvc.perform(post("/api/v1/auth/signup").contentType("application/json")
                .content(json.writeValueAsString(Map.of("email", email, "password", "correct-horse")))).andReturn();
        assertThat(result.getResponse().getStatus()).isEqualTo(201);
        return login(email);
    }
    private Member login(String email) throws Exception {
        var result = mvc.perform(post("/api/v1/auth/login").contentType("application/json")
                .content(json.writeValueAsString(Map.of("email", email, "password", "correct-horse")))).andReturn().getResponse();
        assertThat(result.getStatus()).isEqualTo(200);
        var data = json.readTree(result.getContentAsByteArray());
        return new Member(data.path("id").asText(), result.getCookie("TEACHMI_SESSION"), data.path("csrfToken").asText(), email);
    }
}
