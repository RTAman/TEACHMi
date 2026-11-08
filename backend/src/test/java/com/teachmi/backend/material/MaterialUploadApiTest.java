package com.teachmi.backend.material;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import com.teachmi.backend.auth.AuthFilter;
import com.teachmi.backend.learning.ConceptSource;
import com.teachmi.backend.learning.LearningSourceReader;
import com.teachmi.backend.material.MaterialFiles.Slide;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.AbstractMockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

@SpringBootTest
class MaterialUploadApiTest {
    static final List<Slide> LECTURE = List.of(
            new Slide("Accounting Principles Week 7", "Lecture"),
            new Slide("Depreciation", "Depreciation allocates the cost of an asset over its useful life.\nIt matches cost with revenue."),
            new Slide("Depreciation", "Example: a 3,000 dollar computer used for 3 years costs 1,000 dollars per year."),
            new Slide("Useful Life", "The useful life is the period an asset is expected to be used.\nIt is an estimate."),
            new Slide("Q&A", "Questions?"));

    @Autowired WebApplicationContext context;
    @Autowired AuthFilter filter;
    @Autowired JdbcTemplate jdbc;
    @Autowired ObjectMapper json;
    @Autowired MaterialAnalysis analysis;
    @Autowired ConceptSource concepts;
    @Autowired LearningSourceReader sources;
    MockMvc mvc;

    record Member(String id, Cookie cookie, String csrf) {}

    @BeforeEach
    void setup() {
        mvc = MockMvcBuilders.webAppContextSetup(context).addFilters(filter).build();
        jdbc.execute("TRUNCATE teachmi.app_user CASCADE");
    }

    @Test
    void pdfUploadIsAnalyzedIntoPagesAndGroupedConceptsThatBCanLearnFrom() throws Exception {
        var member = member("pdf@example.com");
        var accepted = upload(member, "k1", "week7.pdf", MaterialFiles.pdf(LECTURE));
        assertThat(accepted.getStatus()).isEqualTo(202);
        var body = body(accepted);
        String materialId = body.path("materialId").asText();
        assertThat(accepted.getHeader("Location")).isEqualTo("/api/v1/materials/" + materialId);
        assertThat(body.path("sourceType").asText()).isEqualTo("file");
        assertThat(body.path("fileName").asText()).isEqualTo("week7.pdf");
        assertThat(body.path("title").asText()).isEqualTo("week7");
        assertThat(body.path("status").asText()).isEqualTo("analyzing");

        var done = awaitAnalysis(member, materialId);
        assertThat(done.path("status").asText()).isEqualTo("done");
        assertThat(done.path("errorCode").isNull()).isTrue();
        assertThat(done.path("sources")).hasSize(5);
        assertThat(done.path("sources").get(1).path("location").asText()).isEqualTo("p.2");
        var names = done.path("concepts").valueStream().map(concept -> concept.path("name").asText()).toList();
        assertThat(names).as("표지와 Q&A는 빼고 같은 제목의 연속 페이지는 하나로 묶는다")
                .containsExactly("Depreciation", "Useful Life");
        var depreciation = done.path("concepts").get(0);
        assertThat(depreciation.path("location").asText()).isEqualTo("p.2~3");
        assertThat(depreciation.path("scope").asText()).startsWith("Depreciation allocates the cost");
        assertThat(depreciation.path("custom").asBoolean()).isFalse();

        String conceptId = depreciation.path("conceptId").asText();
        var snapshot = concepts.loadOwnedConcept(member.id(), materialId, conceptId);
        assertThat(snapshot.sourceChunkIds()).hasSize(2);
        var raw = sources.readOwned(member.id(), materialId, conceptId, snapshot.sourceChunkIds());
        assertThat(raw.chunks().getFirst().text()).contains("useful life");

        var session = mvc.perform(withMember(post("/api/v1/sessions"), member).header("Idempotency-Key", "s1")
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(Map.of("materialId", materialId, "conceptId", conceptId))))
                .andReturn().getResponse();
        assertThat(session.getStatus()).as(session.getContentAsString()).isEqualTo(201);

        var other = member("other@example.com");
        assertThat(mvc.perform(withMember(get("/api/v1/materials/" + materialId), other)).andReturn().getResponse()
                .getStatus()).isEqualTo(404);
    }

    @Test
    void koreanPptxAndPptSlidesUseTheirSlideTitles() throws Exception {
        var member = member("slides@example.com");
        var slides = List.of(new Slide("감가상각의 개념", "취득원가를 내용연수 동안 비용으로 나누어 인식한다."),
                new Slide("정액법", "매년 같은 금액을 감가상각비로 인식하는 방법이다."));

        String pptxId = body(upload(member, "x1", "회계원리.pptx", MaterialFiles.pptx(slides))).path("materialId").asText();
        var pptx = awaitAnalysis(member, pptxId);
        assertThat(pptx.path("status").asText()).isEqualTo("done");
        assertThat(pptx.path("title").asText()).isEqualTo("회계원리");
        assertThat(pptx.path("concepts").get(0).path("name").asText()).isEqualTo("감가상각의 개념");
        assertThat(pptx.path("concepts").get(0).path("location").asText()).isEqualTo("슬라이드 1");
        assertThat(pptx.path("concepts").get(1).path("scope").asText()).isEqualTo("매년 같은 금액을 감가상각비로 인식하는 방법이다.");

        String pptId = body(upload(member, "x2", "old.ppt", MaterialFiles.ppt(slides))).path("materialId").asText();
        var ppt = awaitAnalysis(member, pptId);
        assertThat(ppt.path("status").asText()).isEqualTo("done");
        assertThat(ppt.path("concepts").valueStream().map(concept -> concept.path("name").asText()).toList())
                .containsExactly("감가상각의 개념", "정액법");
    }

    @Test
    void unreadableFilesFailWithReasonsAndCanBeRecoveredByDirectInputOrRetry() throws Exception {
        var member = member("fail@example.com");
        var blank = new java.util.ArrayList<Slide>();
        blank.add(null);
        String blankId = body(upload(member, "b1", "scan.pdf", MaterialFiles.pdf(blank))).path("materialId").asText();
        var noText = awaitAnalysis(member, blankId);
        assertThat(noText.path("status").asText()).isEqualTo("failed");
        assertThat(noText.path("errorCode").asText()).isEqualTo("no_text");

        String brokenId = body(upload(member, "b2", "broken.pdf", "%PDF-1.7 not really a pdf".getBytes()))
                .path("materialId").asText();
        var broken = awaitAnalysis(member, brokenId);
        assertThat(broken.path("errorCode").asText()).isEqualTo("format");

        // 실패한 자료는 같은 화면에서 직접 입력으로 넘어가 S03까지 진행할 수 있다.
        var direct = mvc.perform(withMember(post("/api/v1/materials"), member).header("Idempotency-Key", "d1")
                .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(Map.of(
                        "text", "감가상각은 취득원가를 배분하는 과정이다.",
                        "concept", Map.of("name", "감가상각", "scope", "배분 이유"))))).andReturn().getResponse();
        assertThat(direct.getStatus()).isEqualTo(201);

        var retried = mvc.perform(withMember(post("/api/v1/materials/" + brokenId + "/retry"), member)
                .header("Idempotency-Key", "r1")).andReturn().getResponse();
        assertThat(retried.getStatus()).isEqualTo(202);
        assertThat(body(retried).path("analysisVersion").asInt()).isEqualTo(2);
        var again = awaitAnalysis(member, brokenId);
        assertThat(again.path("status").asText()).isEqualTo("failed");
        assertThat(again.path("analysisVersion").asInt()).isEqualTo(2);

        var notFailed = mvc.perform(withMember(post("/api/v1/materials/" + direct(direct) + "/retry"), member)
                .header("Idempotency-Key", "r2")).andReturn().getResponse();
        assertThat(notFailed.getStatus()).isEqualTo(409);
        assertThat(body(notFailed).path("code").asText()).isEqualTo("MATERIAL_NOT_FAILED");
    }

    @Test
    void aLateResultFromAnOlderAnalysisIsDiscarded() throws Exception {
        var member = member("late@example.com");
        String id = body(upload(member, "l1", "lecture.pdf", MaterialFiles.pdf(LECTURE))).path("materialId").asText();
        awaitAnalysis(member, id);
        // 재시도로 버전 2가 분석 중인 상황을 만든 뒤, 버전 1 작업이 늦게 끝난 경우를 재현한다.
        jdbc.update("UPDATE teachmi.material SET status = 'analyzing', analysis_version = 2 WHERE id = ?", UUID.fromString(id));
        analysis.analyze(UUID.fromString(id), 1);
        var stillAnalyzing = body(mvc.perform(withMember(get("/api/v1/materials/" + id), member)).andReturn().getResponse());
        assertThat(stillAnalyzing.path("status").asText()).isEqualTo("analyzing");
        assertThat(stillAnalyzing.path("concepts")).isEmpty();

        analysis.analyze(UUID.fromString(id), 2);
        var done = body(mvc.perform(withMember(get("/api/v1/materials/" + id), member)).andReturn().getResponse());
        assertThat(done.path("status").asText()).isEqualTo("done");
        assertThat(done.path("concepts")).hasSize(2);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM teachmi.concept WHERE material_id = ? AND analysis_version = 2",
                Integer.class, UUID.fromString(id))).isEqualTo(2);
    }

    @Test
    void uploadsAreValidatedImmediatelyAndDeduplicatedByKey() throws Exception {
        var member = member("check@example.com");
        assertThat(upload(member, "v1", "notes.txt", "hello".getBytes()).getStatus()).isEqualTo(415);
        assertThat(upload(member, "v2", "fake.pdf", "PK not a pdf".getBytes()).getStatus()).isEqualTo(415);
        assertThat(upload(member, "v3", "empty.pdf", new byte[0]).getStatus()).isEqualTo(422);
        var tooLarge = new byte[21 * 1024 * 1024];
        System.arraycopy("%PDF".getBytes(), 0, tooLarge, 0, 4);
        var large = upload(member, "v4", "huge.pdf", tooLarge);
        assertThat(large.getStatus()).isEqualTo(413);
        assertThat(body(large).path("code").asText()).isEqualTo("FILE_TOO_LARGE");

        byte[] pdf = MaterialFiles.pdf(LECTURE);
        String first = body(upload(member, "same", "a.pdf", pdf)).path("materialId").asText();
        String replay = body(upload(member, "same", "a.pdf", pdf)).path("materialId").asText();
        assertThat(replay).isEqualTo(first);
        assertThat(upload(member, "same", "b.pdf", pdf).getStatus()).isEqualTo(409);

        var noCsrf = mvc.perform(multipart("/api/v1/materials").file(new MockMultipartFile("file", "a.pdf",
                "application/pdf", pdf)).cookie(member.cookie()).header("Idempotency-Key", "c1")).andReturn().getResponse();
        assertThat(noCsrf.getStatus()).isEqualTo(403);
        var anonymous = mvc.perform(multipart("/api/v1/materials").file(new MockMultipartFile("file", "a.pdf",
                "application/pdf", pdf)).header("Idempotency-Key", "c2")).andReturn().getResponse();
        assertThat(anonymous.getStatus()).isEqualTo(401);
        awaitAnalysis(member, first);
    }

    @Test
    void analysisInterruptedByARestartResumes() throws Exception {
        var member = member("resume@example.com");
        String id = body(upload(member, "r1", "lecture.pdf", MaterialFiles.pdf(LECTURE))).path("materialId").asText();
        awaitAnalysis(member, id);
        jdbc.update("DELETE FROM teachmi.concept WHERE material_id = ?", UUID.fromString(id));
        jdbc.update("DELETE FROM teachmi.source_chunk WHERE material_id = ?", UUID.fromString(id));
        jdbc.update("UPDATE teachmi.material SET status = 'analyzing' WHERE id = ?", UUID.fromString(id));
        analysis.resumePending();
        var resumed = awaitAnalysis(member, id);
        assertThat(resumed.path("status").asText()).isEqualTo("done");
        assertThat(resumed.path("concepts")).hasSize(2);
    }

    @Test
    void conceptCandidatesSkipRunningHeadersAndGenericSlides() {
        var pages = List.of(
                new ConceptCandidates.Source(1, null, "Accounting 101\nCover"),
                new ConceptCandidates.Source(2, null, "Accounting 101\nAgenda\n1. Depreciation"),
                new ConceptCandidates.Source(3, null, "Accounting 101\n1. Depreciation\nCost is spread over the useful life."),
                new ConceptCandidates.Source(4, null, "Accounting 101\n2. Straight-line method\nThe same amount every year."),
                new ConceptCandidates.Source(5, null, "Accounting 101\nThank you"));
        var candidates = ConceptCandidates.from(pages);
        assertThat(candidates).extracting(ConceptCandidates.Candidate::name)
                .containsExactly("Depreciation", "Straight-line method");
        assertThat(candidates.getFirst().scope()).isEqualTo("Cost is spread over the useful life.");
        assertThat(candidates.getFirst().sourceIds()).containsExactly(3);

        var numbered = ConceptCandidates.from(List.of(
                new ConceptCandidates.Source(1, null, "(3) 서비스 접근 방식\n• AI의 역할을 질문하는 학생으로 전환함.")));
        assertThat(numbered.getFirst().name()).isEqualTo("서비스 접근 방식");
        assertThat(numbered.getFirst().scope()).isEqualTo("AI의 역할을 질문하는 학생으로 전환함.");
    }

    // ---------------------------------------------------------------- helpers

    private JsonNode awaitAnalysis(Member member, String materialId) throws Exception {
        for (int i = 0; i < 100; i++) {
            var response = body(mvc.perform(withMember(get("/api/v1/materials/" + materialId), member)).andReturn()
                    .getResponse());
            if (!"analyzing".equals(response.path("status").asText())) {
                return response;
            }
            Thread.sleep(100);
        }
        throw new AssertionError("Analysis did not finish");
    }

    private String direct(MockHttpServletResponse response) throws Exception {
        return body(response).path("materialId").asText();
    }

    private MockHttpServletResponse upload(Member member, String key, String name, byte[] content) throws Exception {
        return mvc.perform(withMember(multipart("/api/v1/materials")
                .file(new MockMultipartFile("file", name, "application/octet-stream", content)), member)
                .header("Idempotency-Key", key)).andReturn().getResponse();
    }

    private Member member(String email) throws Exception {
        String credentials = json.writeValueAsString(Map.of("email", email, "password", "correct-horse"));
        mvc.perform(post("/api/v1/auth/signup").contentType(MediaType.APPLICATION_JSON).content(credentials));
        var login = mvc.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON)
                .content(credentials)).andReturn().getResponse();
        var body = body(login);
        return new Member(body.path("id").asText(), login.getCookie("TEACHMI_SESSION"), body.path("csrfToken").asText());
    }

    private <B extends AbstractMockHttpServletRequestBuilder<B>> B withMember(B request, Member member) {
        return request.cookie(member.cookie()).header(AuthFilter.CSRF_HEADER, member.csrf());
    }

    private JsonNode body(MockHttpServletResponse response) throws Exception {
        return json.readTree(response.getContentAsString());
    }
}
