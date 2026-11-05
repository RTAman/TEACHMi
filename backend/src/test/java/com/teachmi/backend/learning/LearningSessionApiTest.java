package com.teachmi.backend.learning;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

@SpringBootTest(classes = LearningTestApplication.class)
@Import(LearningSessionApiTest.Sources.class)
class LearningSessionApiTest {
    @Autowired WebApplicationContext context;
    @Autowired JdbcTemplate jdbc;
    @Autowired ObjectMapper json;
    @Autowired FixtureConceptSource source;
    MockMvc mvc;

    @TestConfiguration(proxyBeanMethods = false)
    static class Sources {
        @Bean
        FixtureConceptSource fixtureConceptSource() {
            return new FixtureConceptSource();
        }
    }

    static class FixtureConceptSource implements ConceptSource {
        boolean unavailable;

        @Override
        public Snapshot loadOwnedConcept(String member, String material, String concept) {
            if (unavailable) {
                throw new LearningException(503, "CONCEPT_SOURCE_UNAVAILABLE", "자료 조회 중단");
            }
            if (!member.equals("member-1") || !material.equals("m01")
                    || !List.of("c01", "c02").contains(concept)) {
                throw LearningException.notFound();
            }
            return new Snapshot(concept.equals("c01") ? "감가상각" : "내용연수",
                    "정액법의 적용 범위", List.of("k01"));
        }
    }

    @BeforeEach
    void setup() {
        mvc = MockMvcBuilders.webAppContextSetup(context).build();
        jdbc.execute("TRUNCATE teachmi.learning_session_request, teachmi.learning_message, teachmi.learning_session");
        source.unavailable = false;
    }

    @Test
    void createsAndRetrievesAnOwnedSnapshot() throws Exception {
        var created = send("start", "c01");
        assertThat(created.getResponse().getStatus()).isEqualTo(201);
        var body = body(created);
        String id = body.path("sessionId").asText();
        assertThat(created.getResponse().getHeader("Location")).isEqualTo("/api/v1/sessions/" + id);
        assertThat(body.path("stage").asText()).isEqualTo("mode");
        assertThat(body.path("mode").isNull()).isTrue();
        assertThat(body.path("version").asLong()).isZero();
        assertThat(body.path("concept").path("name").asText()).isEqualTo("감가상각");
        assertThat(body.path("concept").path("sourceChunkIds").get(0).asText()).isEqualTo("k01");
        var loaded = mvc.perform(get("/api/v1/sessions/" + id).principal(() -> "member-1")).andReturn();
        assertThat(loaded.getResponse().getStatus()).isEqualTo(200);
        assertThat(body(loaded)).isEqualTo(body);
    }

    @Test
    void restoresPersistedProgressAndSnapshotWithoutCallingMaterialsAgain() throws Exception {
        String id = body(send("start", "c01")).path("sessionId").asText();
        // Stand in for the next B increment saving progress, in a separate DB transaction.
        jdbc.update("""
                UPDATE teachmi.learning_session SET mode='review', stage='chat',
                version=1, updated_at=now() WHERE id=?
                """, UUID.fromString(id));
        source.unavailable = true;
        var loaded = mvc.perform(get("/api/v1/sessions/" + id).principal(() -> "member-1")).andReturn();
        assertThat(loaded.getResponse().getStatus()).isEqualTo(200);
        assertThat(body(loaded).path("mode").asText()).isEqualTo("review");
        assertThat(body(loaded).path("resume").path("stage").asText()).isEqualTo("chat");
        assertThat(body(loaded).path("version").asLong()).isEqualTo(1);
        assertThat(body(loaded).path("concept").path("name").asText()).isEqualTo("감가상각");
        // A second application instance must restore the saved progress without the test source bean.
        try (var restarted = new SpringApplicationBuilder(LearningTestApplication.class)
                .web(WebApplicationType.NONE).run()) {
            var restored = restarted.getBean(LearningSessionService.class)
                    .get("member-1", UUID.fromString(id));
            assertThat(restored.stage()).isEqualTo("chat");
            assertThat(restored.version()).isEqualTo(1);
            assertThat(restored.concept().name()).isEqualTo("감가상각");
        }
    }

    @Test
    void requiresAuthenticationAndIgnoresClientIdentityHeaders() throws Exception {
        assertThat(mvc.perform(start("k", "c01").header("X-User-Id", "member-1"))
                .andReturn().getResponse().getStatus()).isEqualTo(401);
        assertThat(mvc.perform(get("/api/v1/sessions/" + UUID.randomUUID()))
                .andReturn().getResponse().getStatus()).isEqualTo(401);
    }

    @Test
    void concealsOtherMembersAndMissingSessions() throws Exception {
        String id = body(send("start", "c01")).path("sessionId").asText();
        var other = mvc.perform(get("/api/v1/sessions/" + id).principal(() -> "member-2")).andReturn();
        var absent = mvc.perform(get("/api/v1/sessions/" + UUID.randomUUID())
                .principal(() -> "member-2")).andReturn();
        assertThat(other.getResponse().getStatus()).isEqualTo(404);
        assertThat(body(other)).isEqualTo(body(absent));
    }

    @Test
    void verifiesTheSourceAndRejectsInvalidRequests() throws Exception {
        assertThat(send("k", "missing").getResponse().getStatus()).isEqualTo(404);
        assertThat(mvc.perform(start("k", "c01").principal(() -> "member-2"))
                .andReturn().getResponse().getStatus()).isEqualTo(404);
        assertThat(mvc.perform(post("/api/v1/sessions").principal(() -> "member-1")
                .contentType("application/json").content("{\"materialId\":\"\",\"conceptId\":\"c01\"}"))
                .andReturn().getResponse().getStatus()).isEqualTo(422);
        assertThat(mvc.perform(post("/api/v1/sessions").principal(() -> "member-1")
                .contentType("application/json").content(payload("c01")))
                .andReturn().getResponse().getStatus()).isEqualTo(422);
        assertThat(send(" ".repeat(129), "c01").getResponse().getStatus()).isEqualTo(422);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM teachmi.learning_session", Long.class)).isZero();
    }

    @Test
    void retryReturnsTheOriginalResponseEvenAfterReplacementOrSourceOutage() throws Exception {
        var original = body(send("key1", "c01"));
        assertThat(replace("key2", original, 0, "c02").getResponse().getStatus()).isEqualTo(201);
        source.unavailable = true;
        assertThat(body(send("key1", "c01"))).isEqualTo(original);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM teachmi.learning_session", Long.class))
                .isEqualTo(2);
        assertThat(send("key1", "c02").getResponse().getStatus()).isEqualTo(409);
    }

    @Test
    void activeSessionRequiresExplicitReplacementAndAnUpToDateVersion() throws Exception {
        var first = body(send("key1", "c01"));
        var conflict = send("key2", "c02");
        assertThat(conflict.getResponse().getStatus()).isEqualTo(409);
        assertThat(body(conflict).path("currentSession").path("sessionId")).isEqualTo(first.path("sessionId"));
        assertThat(replace("key3", first, 1, "c02").getResponse().getStatus()).isEqualTo(409);
        var replaced = replace("key4", first, 0, "c02");
        assertThat(replaced.getResponse().getStatus()).isEqualTo(201);
        var old = mvc.perform(get("/api/v1/sessions/" + first.path("sessionId").asText())
                .principal(() -> "member-1")).andReturn();
        assertThat(body(old).path("status").asText()).isEqualTo("abandoned");
        assertThat(body(old).path("version").asLong()).isEqualTo(1);
        assertThat(body(old).path("resume").isNull()).isTrue();
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM teachmi.learning_session WHERE status='active'", Long.class))
                .isEqualTo(1);
    }

    @Test
    void failedReplacementPreservesTheOldSessionAndAllowsRetry() throws Exception {
        var first = body(send("key1", "c01"));
        source.unavailable = true;
        assertThat(replace("key2", first, 0, "c02").getResponse().getStatus()).isEqualTo(503);
        assertThat(jdbc.queryForObject(
                "SELECT status FROM teachmi.learning_session WHERE id=?", String.class,
                UUID.fromString(first.path("sessionId").asText()))).isEqualTo("active");
        source.unavailable = false;
        assertThat(replace("key2", first, 0, "c02").getResponse().getStatus()).isEqualTo(201);
    }

    @Test
    void rejectsIncompleteReplacementAndMalformedIds() throws Exception {
        var invalid = mvc.perform(post("/api/v1/sessions").principal(() -> "member-1")
                .header("Idempotency-Key", "k").contentType("application/json")
                .content("{\"materialId\":\"m01\",\"conceptId\":\"c01\",\"expectedVersion\":0}"))
                .andReturn();
        assertThat(invalid.getResponse().getStatus()).isEqualTo(422);
        assertThat(mvc.perform(get("/api/v1/sessions/not-a-uuid").principal(() -> "member-1"))
                .andReturn().getResponse().getStatus()).isEqualTo(400);
    }

    @Test
    void concurrentStartsWithDifferentKeysCreateOnlyOneActiveSession() throws Exception {
        var results = together(() -> send("key1", "c01"), () -> send("key2", "c01"));
        assertThat(results.stream().map(r -> r.getResponse().getStatus()).toList())
                .containsExactlyInAnyOrder(201, 409);
    }

    @Test
    void concurrentRetriesReturnTheSameSession() throws Exception {
        var results = together(() -> send("same", "c01"), () -> send("same", "c01"));
        assertThat(results.stream().map(r -> r.getResponse().getStatus()).toList())
                .containsExactly(201, 201);
        assertThat(body(results.get(0))).isEqualTo(body(results.get(1)));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM teachmi.learning_session", Long.class))
                .isEqualTo(1);
    }

    @Test
    void concurrentReplacementsCannotAbandonTheNewSession() throws Exception {
        var original = body(send("start", "c01"));
        var results = together(() -> replace("r1", original, 0, "c02"),
                () -> replace("r2", original, 0, "c02"));
        assertThat(results.stream().map(r -> r.getResponse().getStatus()).toList())
                .containsExactlyInAnyOrder(201, 409);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM teachmi.learning_session", Long.class))
                .isEqualTo(2);
    }

    private List<MvcResult> together(Callable<MvcResult> left, Callable<MvcResult> right) throws Exception {
        var barrier = new CyclicBarrier(2);
        try (var pool = Executors.newFixedThreadPool(2)) {
            var a = pool.submit(() -> { barrier.await(10, TimeUnit.SECONDS); return left.call(); });
            var b = pool.submit(() -> { barrier.await(10, TimeUnit.SECONDS); return right.call(); });
            return List.of(a.get(20, TimeUnit.SECONDS), b.get(20, TimeUnit.SECONDS));
        }
    }

    private MvcResult replace(String key, JsonNode original, long version, String concept) throws Exception {
        return mvc.perform(post("/api/v1/sessions").principal(() -> "member-1")
                .header("Idempotency-Key", key).contentType("application/json")
                .content(json.writeValueAsString(new CreateSessionRequest("m01", concept,
                        UUID.fromString(original.path("sessionId").asText()), version)))).andReturn();
    }

    private MvcResult send(String key, String concept) throws Exception {
        return mvc.perform(start(key, concept).principal(() -> "member-1")).andReturn();
    }

    private MockHttpServletRequestBuilder start(String key, String concept) {
        return post("/api/v1/sessions").header("Idempotency-Key", key)
                .contentType("application/json").content(payload(concept));
    }

    private String payload(String concept) {
        return json.writeValueAsString(new CreateSessionRequest("m01", concept, null, null));
    }

    private JsonNode body(MvcResult result) throws Exception {
        return json.readTree(result.getResponse().getContentAsByteArray());
    }
}
