package com.teachmi.backend.material;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import com.teachmi.backend.auth.AuthFilter;
import com.teachmi.backend.learning.ConceptSource;
import com.teachmi.backend.learning.LearningException;
import com.teachmi.backend.learning.LearningSourceReader;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

@SpringBootTest
class MaterialApiTest {
    static final String DEPRECIATION = """
            감가상각은 유형자산의 취득원가를 내용연수 동안 체계적으로 배분하는 과정이다.

            예를 들어 300만 원짜리 컴퓨터를 3년 동안 쓴다면, 구입한 해에 전부 비용으로 처리하지 않고
            매년 100만 원씩 비용으로 나눈다.
            """;

    @Autowired WebApplicationContext context;
    @Autowired AuthFilter filter;
    @Autowired JdbcTemplate jdbc;
    @Autowired ObjectMapper json;
    @Autowired ConceptSource concepts;
    @Autowired LearningSourceReader sources;
    MockMvc mvc;

    record Member(String id, Cookie cookie, String csrf) {}

    @BeforeEach
    void setup() {
        mvc = MockMvcBuilders.webAppContextSetup(context).addFilters(filter).build();
        jdbc.execute("TRUNCATE teachmi.learning_reteach, teachmi.learning_turn, teachmi.learning_support_event, "
                + "teachmi.learning_session_request, teachmi.learning_message, teachmi.learning_session");
        jdbc.execute("TRUNCATE teachmi.app_user CASCADE");
    }

    @Test
    void directInputCreatesADoneMaterialWithItsConceptAndReplaysTheSameKey() throws Exception {
        var member = member("one@example.com");
        var created = createText(member, "key-1", DEPRECIATION, Map.of("name", "감가상각의 개념",
                "scope", "취득원가를 내용연수에 걸쳐 배분하는 이유"));
        assertThat(created.getStatus()).isEqualTo(201);
        var body = body(created);
        String materialId = body.path("materialId").asText();
        assertThat(created.getHeader("Location")).isEqualTo("/api/v1/materials/" + materialId);
        assertThat(body.path("sourceType").asText()).isEqualTo("text");
        assertThat(body.path("status").asText()).isEqualTo("done");
        assertThat(body.path("errorCode").isNull()).isTrue();
        assertThat(body.path("title").asText()).startsWith("감가상각은");
        assertThat(body.path("sources")).hasSize(1);
        assertThat(body.path("sources").get(0).path("location").asText()).isEqualTo("직접 입력 1");
        var concept = body.path("concepts").get(0);
        assertThat(concept.path("name").asText()).isEqualTo("감가상각의 개념");
        assertThat(concept.path("custom").asBoolean()).isTrue();
        assertThat(concept.path("sourceChunkIds").get(0).asText())
                .isEqualTo(body.path("sources").get(0).path("chunkId").asText());

        var replay = createText(member, "key-1", DEPRECIATION, Map.of("name", "감가상각의 개념",
                "scope", "취득원가를 내용연수에 걸쳐 배분하는 이유"));
        assertThat(replay.getStatus()).isEqualTo(201);
        assertThat(body(replay).path("materialId").asText()).isEqualTo(materialId);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM teachmi.material", Integer.class)).isEqualTo(1);

        var reused = createText(member, "key-1", "다른 내용", null);
        assertThat(reused.getStatus()).isEqualTo(409);
        assertThat(body(reused).path("code").asText()).isEqualTo("IDEMPOTENCY_KEY_REUSED");

        var noKey = mvc.perform(withMember(post("/api/v1/materials"), member)
                .contentType(MediaType.APPLICATION_JSON).content("{\"text\":\"내용\"}")).andReturn().getResponse();
        assertThat(noKey.getStatus()).isEqualTo(422);
        assertThat(createText(member, "key-2", "   ", null).getStatus()).isEqualTo(422);
    }

    @Test
    void anotherMemberCannotReadOrChangeTheMaterialAndAnonymousCallsAreRejected() throws Exception {
        var owner = member("owner@example.com");
        var other = member("other@example.com");
        var body = body(createText(owner, "k", DEPRECIATION, Map.of("name", "감가상각", "scope", "정액법")));
        String materialId = body.path("materialId").asText();
        String conceptId = body.path("concepts").get(0).path("conceptId").asText();

        assertThat(mvc.perform(withMember(get("/api/v1/materials/" + materialId), owner)).andReturn()
                .getResponse().getStatus()).isEqualTo(200);
        assertThat(mvc.perform(withMember(get("/api/v1/materials/" + materialId), other)).andReturn()
                .getResponse().getStatus()).isEqualTo(404);
        assertThat(mvc.perform(withMember(patch("/api/v1/materials/" + materialId + "/concepts/" + conceptId), other)
                .contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"가로채기\"}")).andReturn()
                .getResponse().getStatus()).isEqualTo(404);
        assertThat(mvc.perform(withMember(post("/api/v1/materials/" + materialId + "/concepts"), other)
                .header("Idempotency-Key", "x").contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"a\",\"scope\":\"b\"}")).andReturn().getResponse().getStatus()).isEqualTo(404);
        assertThat(mvc.perform(get("/api/v1/materials/" + materialId)).andReturn().getResponse().getStatus())
                .isEqualTo(401);
        assertThat(mvc.perform(withMember(get("/api/v1/materials/not-a-uuid"), owner)).andReturn()
                .getResponse().getStatus()).isEqualTo(404);

        var noCsrf = mvc.perform(post("/api/v1/materials").cookie(owner.cookie()).header("Idempotency-Key", "k2")
                .contentType(MediaType.APPLICATION_JSON).content("{\"text\":\"내용\"}")).andReturn().getResponse();
        assertThat(noCsrf.getStatus()).isEqualTo(403);

        assertThatThrownBy(() -> concepts.loadOwnedConcept(other.id(), materialId, conceptId))
                .isInstanceOf(LearningException.class).extracting("code").isEqualTo("NOT_FOUND");
        assertThatThrownBy(() -> sources.readOwned(other.id(), materialId, conceptId, List.of()))
                .isInstanceOf(LearningException.class);
    }

    @Test
    void conceptsCanBeAddedWithEvidenceAndEdited() throws Exception {
        var member = member("edit@example.com");
        var material = body(createText(member, "k", DEPRECIATION, null));
        String materialId = material.path("materialId").asText();
        assertThat(material.path("concepts")).isEmpty();

        var whole = addConcept(member, materialId, "c1", Map.of("name", "감가상각", "scope", "배분 이유"));
        assertThat(whole.getStatus()).isEqualTo(201);
        assertThat(body(whole).path("sourceChunkIds")).hasSize(1);

        var evidence = addConcept(member, materialId, "c2", Map.of("name", "내용연수", "scope", "자산을 쓰는 기간",
                "evidenceText", "내용연수는 자산을 사용할 것으로 예상하는 기간이다."));
        assertThat(evidence.getStatus()).isEqualTo(201);
        String evidenceConcept = body(evidence).path("conceptId").asText();
        assertThat(body(evidence).path("location").asText()).isEqualTo("직접 입력 2");
        assertThat(addConcept(member, materialId, "c2", Map.of("name", "내용연수", "scope", "자산을 쓰는 기간",
                "evidenceText", "내용연수는 자산을 사용할 것으로 예상하는 기간이다.")).getStatus()).isEqualTo(201);

        var badSource = addConcept(member, materialId, "c3", Map.of("name", "x", "scope", "y",
                "sourceChunkIds", List.of(UUID.randomUUID().toString())));
        assertThat(badSource.getStatus()).isEqualTo(422);
        assertThat(addConcept(member, materialId, "c4", Map.of("name", " ", "scope", "y")).getStatus())
                .isEqualTo(422);

        var reloaded = body(mvc.perform(withMember(get("/api/v1/materials/" + materialId), member)).andReturn()
                .getResponse());
        assertThat(reloaded.path("concepts")).hasSize(2);
        assertThat(reloaded.path("concepts").get(0).path("conceptId").asText())
                .as("새로 추가한 개념이 목록 맨 위").isEqualTo(evidenceConcept);
        assertThat(reloaded.path("sources")).hasSize(2);

        var updated = mvc.perform(withMember(patch("/api/v1/materials/" + materialId + "/concepts/" + evidenceConcept),
                member).contentType(MediaType.APPLICATION_JSON).content("{\"scope\":\"자산의 예상 사용 기간\"}"))
                .andReturn().getResponse();
        assertThat(updated.getStatus()).isEqualTo(200);
        assertThat(body(updated).path("name").asText()).isEqualTo("내용연수");
        assertThat(body(updated).path("scope").asText()).isEqualTo("자산의 예상 사용 기간");
        assertThat(mvc.perform(withMember(patch("/api/v1/materials/" + materialId + "/concepts/" + evidenceConcept),
                member).contentType(MediaType.APPLICATION_JSON).content("{}")).andReturn().getResponse().getStatus())
                .isEqualTo(422);
    }

    @Test
    void learningAdaptersReturnTheOwnedSnapshotAndRawTextAndBCanStartASession() throws Exception {
        var member = member("learn@example.com");
        var body = body(createText(member, "k", DEPRECIATION, Map.of("name", "감가상각의 개념",
                "scope", "취득원가를 내용연수에 걸쳐 배분하는 이유")));
        String materialId = body.path("materialId").asText();
        String conceptId = body.path("concepts").get(0).path("conceptId").asText();
        String chunkId = body.path("sources").get(0).path("chunkId").asText();

        var snapshot = concepts.loadOwnedConcept(member.id(), materialId, conceptId);
        assertThat(snapshot.name()).isEqualTo("감가상각의 개념");
        assertThat(snapshot.sourceChunkIds()).containsExactly(chunkId);

        var source = sources.readOwned(member.id(), materialId, conceptId, List.of(chunkId));
        assertThat(source.chunks()).hasSize(1);
        assertThat(source.chunks().getFirst().text()).contains("취득원가를 내용연수 동안");
        assertThatThrownBy(() -> sources.readOwned(member.id(), materialId, conceptId,
                List.of(UUID.randomUUID().toString()))).isInstanceOf(LearningException.class);
        assertThatThrownBy(() -> concepts.loadOwnedConcept(member.id(), "m01", "c01"))
                .isInstanceOf(LearningException.class);

        // B의 실제 학습 세션 API가 A의 로그인 쿠키·개념으로 학습을 만든다.
        String request = json.writeValueAsString(Map.of("materialId", materialId, "conceptId", conceptId));
        var session = mvc.perform(withMember(post("/api/v1/sessions"), member).header("Idempotency-Key", "s1")
                .contentType(MediaType.APPLICATION_JSON).content(request)).andReturn().getResponse();
        assertThat(session.getStatus()).isEqualTo(201);
        assertThat(body(session).path("concept").path("name").asText()).isEqualTo("감가상각의 개념");
        assertThat(body(session).path("concept").path("sourceChunkIds").get(0).asText()).isEqualTo(chunkId);
        assertThat(jdbc.queryForObject("SELECT member_id FROM teachmi.learning_session", String.class))
                .isEqualTo(member.id());

        var other = member("thief@example.com");
        var stolen = mvc.perform(withMember(post("/api/v1/sessions"), other).header("Idempotency-Key", "s1")
                .contentType(MediaType.APPLICATION_JSON).content(request)).andReturn().getResponse();
        assertThat(stolen.getStatus()).isEqualTo(404);
        assertThat(mvc.perform(post("/api/v1/sessions").header("Idempotency-Key", "s2")
                .contentType(MediaType.APPLICATION_JSON).content(request)).andReturn().getResponse().getStatus())
                .isEqualTo(401);
    }

    @Test
    void longTextIsSplitIntoBoundedChunks() {
        String paragraph = "가".repeat(1500);
        var parts = MaterialService.split(paragraph + "\n\n" + paragraph + "\n\n" + "나".repeat(4500));
        assertThat(parts).allSatisfy(part -> assertThat(part.length()).isLessThanOrEqualTo(MaterialService.CHUNK_TARGET_CHARS));
        assertThat(String.join("", parts).replace("\n", "")).hasSize(1500 * 2 + 4500);
    }

    @Test
    void supplementaryCharactersSurviveChunkAndDefaultTitleBoundariesInTheDatabase() throws Exception {
        var member = member("unicode@example.com");
        for (String text : List.of("a".repeat(1999) + "😀" + "tail",
                "a".repeat(39) + "😀" + "b".repeat(1958) + "😀" + "tail")) {
            var response = createText(member, UUID.randomUUID().toString(), text,
                    Map.of("name", "문자 보존", "scope", "실제 원문"));
            assertThat(response.getStatus()).isEqualTo(201);
            var created = body(response);
            var materialId = UUID.fromString(created.path("materialId").asText());
            var stored = jdbc.queryForObject(
                    "SELECT string_agg(text,'' ORDER BY seq) FROM teachmi.source_chunk WHERE material_id=?",
                    String.class, materialId);
            assertThat(stored).isEqualTo(text);
            assertThat(created.path("title").asText()).isEqualTo(text.substring(0, text.indexOf("😀") == 39 ? 39 : 40));
            var chunks = MaterialService.split(text);
            assertThat(chunks).allSatisfy(part -> assertThat(part.length()).isLessThanOrEqualTo(2000));
            var conceptId = created.path("concepts").get(0).path("conceptId").asText();
            var ids = created.path("concepts").get(0).path("sourceChunkIds");
            var source = sources.readOwned(member.id(), materialId.toString(), conceptId,
                    java.util.stream.StreamSupport.stream(ids.spliterator(), false).map(JsonNode::asText).toList());
            assertThat(String.join("", source.chunks().stream().map(LearningSourceReader.Chunk::text).toList())).isEqualTo(text);
        }
    }

    // ---------------------------------------------------------------- helpers

    Member member(String email) throws Exception {
        String credentials = json.writeValueAsString(Map.of("email", email, "password", "correct-horse"));
        mvc.perform(post("/api/v1/auth/signup").contentType(MediaType.APPLICATION_JSON).content(credentials));
        var login = mvc.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON)
                .content(credentials)).andReturn().getResponse();
        var body = body(login);
        return new Member(body.path("id").asText(), login.getCookie("TEACHMI_SESSION"), body.path("csrfToken").asText());
    }

    MockHttpServletRequestBuilder withMember(MockHttpServletRequestBuilder request, Member member) {
        return request.cookie(member.cookie()).header(AuthFilter.CSRF_HEADER, member.csrf());
    }

    MockHttpServletResponse createText(Member member, String key, String text, Map<String, String> concept)
            throws Exception {
        var payload = new java.util.HashMap<String, Object>();
        payload.put("text", text);
        if (concept != null) {
            payload.put("concept", concept);
        }
        return mvc.perform(withMember(post("/api/v1/materials"), member).header("Idempotency-Key", key)
                .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(payload)))
                .andReturn().getResponse();
    }

    MockHttpServletResponse addConcept(Member member, String materialId, String key, Map<String, Object> payload)
            throws Exception {
        return mvc.perform(withMember(post("/api/v1/materials/" + materialId + "/concepts"), member)
                .header("Idempotency-Key", key).contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(payload))).andReturn().getResponse();
    }

    JsonNode body(MockHttpServletResponse response) throws Exception {
        return json.readTree(response.getContentAsString());
    }
}
