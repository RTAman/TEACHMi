package com.teachmi.backend.assessment;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.List;
import java.util.concurrent.Executors;
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
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

@SpringBootTest(properties = "teachmi.assessment.demo-enabled=true")
class AssessmentRelearnApiTest {
    record Login(String id, Cookie cookie, String csrf) {}

    @Autowired WebApplicationContext context;
    @Autowired AuthFilter filter;
    @Autowired JdbcTemplate jdbc;
    @Autowired ObjectMapper json;
    MockMvc mvc;
    Login owner;
    String materialId, conceptId;
    UUID session, first, second;

    @BeforeEach
    void setup() throws Exception {
        mvc = MockMvcBuilders.webAppContextSetup(context).addFilters(filter).build();
        owner = login();
        // B가 새 학습을 만들 때 A의 실제 자료·개념 소유권을 확인하므로 A API로 자료를 등록한다.
        var material = mvc.perform(post("/api/v1/materials").cookie(owner.cookie()).header("X-CSRF-Token", owner.csrf())
                        .header("Idempotency-Key", "material").contentType("application/json")
                        .content(json.writeValueAsString(Map.of("text", "감가상각은 유형자산의 원가를 사용 기간에 배분하는 과정이다.",
                                "concept", Map.of("name", "감가상각", "scope", "원가를 사용 기간에 배분하는 이유")))))
                .andReturn().getResponse();
        assertThat(material.getStatus()).isEqualTo(201);
        materialId = body(material).path("materialId").asText();
        conceptId = body(material).path("concepts").get(0).path("conceptId").asText();
        session = readySession();
        var problems = body(write(path() + "/problems", null, null)).path("problems");
        first = UUID.fromString(problems.get(0).path("problemId").asText());
        second = UUID.fromString(problems.get(1).path("problemId").asText());
    }

    @Test
    void aConfirmedReportStartsANewLinkedLearningFromItsNextStep() throws Exception {
        finishWithOneWrongAnswer();
        var focus = body(write(path() + "/report/confirm", "c1", null)).path("nextSteps").get(0).path("focus").asText();

        var response = relearn("r1", Map.of("problemId", first.toString()));
        assertThat(response.getStatus()).isEqualTo(201);
        var data = body(response);
        var created = data.path("session").path("sessionId").asText();
        assertThat(created).isNotEqualTo(session.toString());
        assertThat(data.path("session").path("stage").asText()).isEqualTo("mode");
        assertThat(data.path("session").path("conceptId").asText()).isEqualTo(conceptId);
        assertThat(data.path("originSessionId").asText()).isEqualTo(session.toString());
        assertThat(data.path("focus").asText()).isEqualTo(focus);
        assertThat(jdbc.queryForObject("SELECT origin_session_id FROM teachmi.assessment_relearn WHERE session_id=?::uuid",
                UUID.class, created)).isEqualTo(session);
    }

    @Test
    void theSameKeyReturnsTheSameNewLearning() throws Exception {
        finishWithOneWrongAnswer();
        write(path() + "/report/confirm", "c1", null);
        var request = Map.of("problemId", first.toString());

        var once = body(relearn("r1", request)).path("session").path("sessionId").asText();
        var twice = body(relearn("r1", request)).path("session").path("sessionId").asText();
        assertThat(twice).isEqualTo(once);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM teachmi.assessment_relearn WHERE origin_session_id=?",
                Long.class, session)).isEqualTo(1);
    }

    @Test
    void anotherActiveLearningNeedsAnExplicitReplacement() throws Exception {
        finishWithOneWrongAnswer();
        write(path() + "/report/confirm", "c1", null);
        var created = body(relearn("r1", Map.of())).path("session");

        code(relearn("r2", Map.of()), 409, "ACTIVE_SESSION_EXISTS");
        var replace = new HashMap<String, Object>();
        replace.put("replaceSessionId", created.path("sessionId").asText());
        replace.put("expectedVersion", created.path("version").asLong());
        assertThat(relearn("r3", replace).getStatus()).isEqualTo(201);
    }

    @Test
    void theReportMustBeConfirmedFirst() throws Exception {
        code(relearn("r0", Map.of()), 409, "REPORT_NOT_READY");
        finishWithOneWrongAnswer();
        code(relearn("r1", Map.of()), 409, "REPORT_NOT_CONFIRMED");
    }

    @Test
    void onlyAReportedNextStepCanBeChosen() throws Exception {
        finishWithOneWrongAnswer();
        write(path() + "/report/confirm", "c1", null);
        code(relearn("r1", Map.of("problemId", second.toString())), 422, "INVALID_REQUEST");
        code(relearn("r2", Map.of("problemId", UUID.randomUUID().toString())), 422, "INVALID_REQUEST");
    }

    @Test
    void anotherMemberCannotRelearn() throws Exception {
        finishWithOneWrongAnswer();
        write(path() + "/report/confirm", "c1", null);
        var other = login();
        var response = mvc.perform(post(path() + "/relearn").cookie(other.cookie()).header("X-CSRF-Token", other.csrf())
                .header("Idempotency-Key", "r1").contentType("application/json").content("{}")).andReturn().getResponse();
        assertThat(response.getStatus()).isEqualTo(404);
    }

    /** 1번은 틀리고 해설을 보고, 2번은 맞힌 뒤 리포트를 만든다. 1번이 보완 항목이 된다. */
    private void finishWithOneWrongAnswer() throws Exception {
        submit(first, "a1", "100만원");
        assertThat(write(path() + "/problems/" + first + "/solution", null, null).getStatus()).isEqualTo(200);
        submit(second, "a2", "180만원");
        assertThat(write(path() + "/report", "p1", null).getStatus()).isEqualTo(200);
    }

    /** 처음 설명과 마지막 설명이 있는, 문제 풀이 직전의 학습. 자료·개념은 A에 실제로 등록한 값이다. */
    private UUID readySession() {
        var id = UUID.randomUUID();
        var initial = UUID.randomUUID();
        var last = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO teachmi.learning_session
                (id,member_id,material_id,concept_id,concept_snapshot,mode,stage,status,version,
                 initial_answer_id,final_explanation_id,created_at,updated_at)
                VALUES (?,?,?,?,'{"name":"감가상각","scope":"원가 배분","sourceChunkIds":[]}',
                'review','problem','active',7,?,?,now(),now())
                """, id, owner.id(), materialId, conceptId, initial, last);
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

private void confirmOrigin() throws Exception {
        finishWithOneWrongAnswer();
        assertThat(write(path()+"/report/confirm","c1",null).getStatus()).isEqualTo(200);
    }
    private MockHttpServletResponse ordinary(String key,Map<String,?> replacement) throws Exception {
        var body=new HashMap<String,Object>();
        body.put("materialId",materialId);body.put("conceptId",conceptId);body.putAll(replacement);
        return write("/api/v1/sessions",key,body);
    }
    private long linkCount() {
        return jdbc.queryForObject("SELECT count(*) FROM teachmi.assessment_relearn WHERE origin_session_id=?",Long.class,session);
    }

    @Test void anOrdinaryCreateKeyCannotTurnItsExistingSessionIntoARelearning() throws Exception {
        confirmOrigin();
        var ordinary=ordinary("ordinary",Map.of());
        assertThat(ordinary.getStatus()).isEqualTo(201);
        var ordinaryId=body(ordinary).path("sessionId").asText();
        code(relearn("ordinary",Map.of()),409,"IDEMPOTENCY_KEY_REUSED");
        assertThat(linkCount()).isZero();
        assertThat(jdbc.queryForObject("SELECT status FROM teachmi.learning_session WHERE id=?::uuid",String.class,ordinaryId)).isEqualTo("active");
    }

    @Test void aRelearningKeyCannotBecomeAnOrdinaryCreationOrReportCommand() throws Exception {
        confirmOrigin();
        assertThat(relearn("relearn",Map.of()).getStatus()).isEqualTo(201);
        code(ordinary("relearn",Map.of()),409,"IDEMPOTENCY_KEY_REUSED");
        code(write(path()+"/report","relearn",null),409,"IDEMPOTENCY_KEY_REUSED");
        assertThat(linkCount()).isEqualTo(1);
    }

    @Test void reportKeysCannotBeReusedForRelearning() throws Exception {
        confirmOrigin();
        code(relearn("p1",Map.of()),409,"IDEMPOTENCY_KEY_REUSED");
        code(relearn("c1",Map.of()),409,"IDEMPOTENCY_KEY_REUSED");
        assertThat(linkCount()).isZero();
    }

    @Test void changedFocusOnTheSameKeyIsRejectedWithoutAnotherSession() throws Exception {
        confirmOrigin();
        assertThat(relearn("same",Map.of("problemId",first.toString())).getStatus()).isEqualTo(201);
        code(relearn("same",Map.of()),409,"IDEMPOTENCY_KEY_REUSED");
        assertThat(linkCount()).isEqualTo(1);
    }

    @Test void concurrentSameKeyRequestsReturnOneIdenticalSessionAndLink() throws Exception {
        confirmOrigin();
        try(var executor=Executors.newFixedThreadPool(2)) {
            var a=executor.submit(() -> relearn("same",Map.of("problemId",first.toString())));
            var b=executor.submit(() -> relearn("same",Map.of("problemId",first.toString())));
            var ra=a.get(20,java.util.concurrent.TimeUnit.SECONDS);var rb=b.get(20,java.util.concurrent.TimeUnit.SECONDS);
            assertThat(ra.getStatus()).isEqualTo(201);assertThat(rb.getStatus()).isEqualTo(201);
            assertThat(body(ra)).isEqualTo(body(rb));
        }
        assertThat(linkCount()).isEqualTo(1);
    }

    @Test void concurrentDifferentKeysStillNeedExplicitReplacement() throws Exception {
        confirmOrigin();
        try(var executor=Executors.newFixedThreadPool(2)) {
            var a=executor.submit(() -> relearn("a",Map.of()));
            var b=executor.submit(() -> relearn("b",Map.of()));
            var ra=a.get(20,java.util.concurrent.TimeUnit.SECONDS);var rb=b.get(20,java.util.concurrent.TimeUnit.SECONDS);
            assertThat(List.of(ra.getStatus(),rb.getStatus())).containsExactlyInAnyOrder(201,409);
            var conflict=ra.getStatus()==409?ra:rb;
            code(conflict,409,"ACTIVE_SESSION_EXISTS");
        }
        assertThat(linkCount()).isEqualTo(1);
    }

    @Test void linkFailureRollsBackCreationReplacementAndReceiptAndTheSameRequestCanRetry() throws Exception {
        confirmOrigin();
        var current=body(ordinary("before",Map.of()));
        var oldId=current.path("sessionId").asText();
        var replace=Map.of("replaceSessionId",oldId,"expectedVersion",current.path("version").asLong());
        // Test-only constraint forces failure precisely at C persistence, after B created/replaced a session.
        jdbc.execute("ALTER TABLE teachmi.assessment_relearn ADD CONSTRAINT test_atomic_relearn CHECK(member_id <> '"+owner.id()+"')");
        try {
            assertThatThrownBy(() -> relearn("rollback",replace)).hasRootCauseInstanceOf(java.sql.SQLException.class);
            assertThat(linkCount()).isZero();
            assertThat(jdbc.queryForObject("SELECT count(*) FROM teachmi.learning_session WHERE member_id=?",Long.class,owner.id())).isEqualTo(2);
            assertThat(jdbc.queryForObject("SELECT status FROM teachmi.learning_session WHERE id=?::uuid",String.class,oldId)).isEqualTo("active");
            assertThat(jdbc.queryForObject("SELECT version FROM teachmi.learning_session WHERE id=?::uuid",Long.class,oldId))
                    .isEqualTo(current.path("version").asLong());
            assertThat(jdbc.queryForObject("SELECT count(*) FROM teachmi.learning_session_request WHERE member_id=? AND request_key='rollback'",Long.class,owner.id())).isZero();
        } finally { jdbc.execute("ALTER TABLE teachmi.assessment_relearn DROP CONSTRAINT test_atomic_relearn"); }
        assertThat(relearn("rollback",replace).getStatus()).isEqualTo(201);
        assertThat(linkCount()).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT status FROM teachmi.learning_session WHERE id=?::uuid",String.class,oldId)).isEqualTo("abandoned");
    }

    @Test void replayAfterReplacementPreservesTheOriginalResponseAndDoesNotCreateAgain() throws Exception {
        confirmOrigin();
        var original=body(relearn("relearn",Map.of("problemId",first.toString())));
        var child=original.path("session");
        assertThat(ordinary("replace",Map.of("replaceSessionId",child.path("sessionId").asText(),
                "expectedVersion",child.path("version").asLong())).getStatus()).isEqualTo(201);
        var replay=relearn("relearn",Map.of("problemId",first.toString()));
        assertThat(replay.getStatus()).isEqualTo(201);
        assertThat(body(replay)).isEqualTo(original);
        assertThat(linkCount()).isEqualTo(1);
    }

    @Test void missingOrBlankKeysNeverCreateARelearning() throws Exception {
        confirmOrigin();
        code(relearn(null,Map.of()),422,"INVALID_IDEMPOTENCY_KEY");
        code(relearn(" ",Map.of()),422,"INVALID_IDEMPOTENCY_KEY");
        assertThat(linkCount()).isZero();
    }
    private String path() {
        return "/api/v1/sessions/" + session;
    }

    private void submit(UUID problem, String key, String answer) throws Exception {
        var response = write(path() + "/problems/" + problem + "/attempts", key,
                Map.of("answer", answer, "work", "원가를 내용연수로 나눈다."));
        assertThat(response.getStatus()).isEqualTo(201);
    }

    private MockHttpServletResponse relearn(String key, Map<String, ?> body) throws Exception {
        return write(path() + "/relearn", key, body);
    }

    private MockHttpServletResponse write(String target, String key, Map<String, ?> body) throws Exception {
        var request = post(target).cookie(owner.cookie()).header("X-CSRF-Token", owner.csrf());
        if (key != null) {
            request.header("Idempotency-Key", key);
        }
        if (body != null) {
            request.contentType("application/json").content(json.writeValueAsString(body));
        }
        return mvc.perform(request).andReturn().getResponse();
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
