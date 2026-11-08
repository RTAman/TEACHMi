package com.teachmi.backend.learning.gemini;

import java.util.*;
import com.teachmi.backend.learning.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

@SpringBootTest(classes=LearningTestApplication.class,properties="teachmi.gemini.enabled=false")
@Import(GeminiRecoverySource.class)
class GeminiRecoverySourceTest {
    @Autowired JdbcTemplate jdbc;
    @Autowired LearningSessionService sessions;
    @Autowired WebApplicationContext context;
    @Autowired ObjectMapper json;
    @MockitoBean ConceptSource concepts;
    MockMvc mvc;
    UUID session;
    @BeforeEach void setup() {
        jdbc.execute("TRUNCATE teachmi.learning_ai_call,teachmi.learning_ai_generation");
        jdbc.execute("TRUNCATE teachmi.learning_session CASCADE");
        when(concepts.loadOwnedConcept(anyString(),anyString(),anyString()))
                .thenReturn(new ConceptSource.Snapshot("개념","범위",List.of("source")));
        mvc=MockMvcBuilders.webAppContextSetup(context).build();
        session=sessions.create("member-1","create",new CreateSessionRequest("material","concept",null,null)).sessionId();
    }
    void receipt(String state,String diagnostic,boolean expired,String member,UUID ownerSession) {
        String key=UUID.randomUUID().toString().replace("-","").repeat(2);
        jdbc.update("INSERT INTO teachmi.learning_ai_generation(generation_key,member_id,session_id,state,attempts,lease_until,retry_after,last_error_code,updated_at) "
                +"VALUES (?,?,?,?,1,now()+make_interval(secs=>?),now(),?,now())",
                key,member,ownerSession,state,expired?-1:120,diagnostic);
    }
    JsonNode read(String member,String path) throws Exception {
        var response=mvc.perform(get(path).principal(() -> member)).andReturn().getResponse();
        assertThat(response.getStatus()).isEqualTo(200);
        return json.readTree(response.getContentAsByteArray());
    }
    JsonNode read() throws Exception { return read("member-1","/api/v1/sessions/"+session); }
    List<Map<String,Object>> rows() { return jdbc.queryForList("SELECT * FROM teachmi.learning_ai_generation ORDER BY generation_key"); }

    @Test void uncertainWithoutPendingTurnSurvivesGetAndActiveEvenWithGenerationDisabled() throws Exception {
        receipt("uncertain","TRANSPORT_REQUEST_TIMEOUT",false,"member-1",session);
        var before=rows();
        var current=read();
        assertThat(current.path("pendingTurn").isNull()).isTrue();
        assertThat(current.path("stage").asText()).isEqualTo("mode");
        assertThat(current.path("recovery").path("action").asText()).isEqualTo("review_required");
        assertThat(current.path("recovery").path("diagnosticCode").asText()).isEqualTo("TRANSPORT_REQUEST_TIMEOUT");
        assertThat(current.path("recovery").path("retryAllowed").asBoolean()).isFalse();
        assertThat(current.path("availableActions").get(0).asText()).isEqualTo("wait");
        assertThat(read("member-1","/api/v1/sessions/active")).isEqualTo(current);
        assertThat(rows()).isEqualTo(before);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM teachmi.learning_ai_call",Long.class)).isZero();
    }
    @Test void expiredRunningIsReadOnlyReviewRatherThanAnAutomaticReclaim() throws Exception {
        receipt("running",null,true,"member-1",session);
        var before=rows();
        assertThat(read().path("recovery").path("action").asText()).isEqualTo("review_required");
        assertThat(read().path("recovery").path("diagnosticCode").asText()).isEqualTo("GENERATION_LEASE_EXPIRED");
        assertThat(rows()).isEqualTo(before);
    }
    @Test void leasedRunningOffersBoundedWaitingWithoutRetry() throws Exception {
        receipt("running",null,false,"member-1",session);
        var before=rows();var response=read();
        assertThat(response.path("recovery").path("action").asText()).isEqualTo("wait");
        assertThat(response.path("recovery").path("retryAllowed").asBoolean()).isFalse();
        assertThat(rows()).isEqualTo(before);
    }
    @Test void failedAndSucceededReceiptsDoNotChangeTheExistingNormalJsonShape() throws Exception {
        var before=read();
        receipt("failed","JSON_SCHEMA_INVALID",true,"member-1",session);
        receipt("succeeded",null,true,"member-1",session);
        assertThat(read()).isEqualTo(before);
        assertThat(read().has("recovery")).isFalse();
    }
    @Test void otherMembersAndOtherSessionsCannotContributeOrRevealARecovery() throws Exception {
        var before=read();
        receipt("uncertain","TRANSPORT_REQUEST_TIMEOUT",true,"member-2",session);
        receipt("uncertain","TRANSPORT_REQUEST_TIMEOUT",true,"member-1",UUID.randomUUID());
        assertThat(read()).isEqualTo(before);
        var denied=mvc.perform(get("/api/v1/sessions/"+session).principal(() -> "member-2")).andReturn().getResponse();
        assertThat(denied.getStatus()).isEqualTo(404);
        assertThat(json.readTree(denied.getContentAsByteArray()).has("recovery")).isFalse();
    }
    @Test void historicalAndUntrustedDiagnosticsAreMappedWithoutRewritingStoredRows() throws Exception {
        receipt("uncertain","TRANSPORT_HttpTimeoutException",true,"member-1",session);
        var before=rows();
        assertThat(read().path("recovery").path("diagnosticCode").asText()).isEqualTo("TRANSPORT_REQUEST_TIMEOUT");
        assertThat(rows()).isEqualTo(before);
        jdbc.update("UPDATE teachmi.learning_ai_generation SET last_error_code='private-test-text'");
        before=rows();
        var response=read();
        assertThat(response.path("recovery").path("diagnosticCode").asText()).isEqualTo("AI_RESULT_UNCONFIRMED");
        assertThat(response.toString()).doesNotContain("private-test-text");
        assertThat(rows()).isEqualTo(before);
    }
    @Test void completedHistoryStaysReadableWithoutAddingAnActiveHold() throws Exception {
        jdbc.update("UPDATE teachmi.learning_session SET status='completed',stage='report' WHERE id=?",session);
        receipt("uncertain","TRANSPORT_REQUEST_TIMEOUT",true,"member-1",session);
        var before=rows();
        assertThat(read().has("recovery")).isFalse();
        assertThat(read().path("availableActions")).isEmpty();
        assertThat(rows()).isEqualTo(before);
    }
}
