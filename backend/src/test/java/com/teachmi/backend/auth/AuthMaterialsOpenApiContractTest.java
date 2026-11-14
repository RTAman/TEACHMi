package com.teachmi.backend.auth;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import com.atlassian.oai.validator.OpenApiInteractionValidator;
import com.atlassian.oai.validator.model.Request;
import com.atlassian.oai.validator.model.SimpleRequest;
import com.atlassian.oai.validator.model.SimpleResponse;
import com.atlassian.oai.validator.report.ValidationReport;
import io.swagger.v3.parser.OpenAPIV3Parser;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.request.AbstractMockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/** api/auth-materials.openapi.json이 A의 실제 경로·요청·응답과 일치하는지 검사한다. */
@Tag("api-contract")
@SpringBootTest
class AuthMaterialsOpenApiContractTest {
    private static final Path SPEC = Path.of("api/auth-materials.openapi.json");
    private static OpenApiInteractionValidator validator;

    @Autowired WebApplicationContext context;
    @Autowired AuthFilter filter;
    @Autowired JdbcTemplate jdbc;
    @Autowired ObjectMapper json;
    MockMvc mvc;

    record Member(Cookie cookie, String csrf) {}

    @BeforeAll
    static void loadContract() {
        validator = OpenApiInteractionValidator.createForSpecificationUrl(SPEC.toUri().toString()).build();
    }

    @BeforeEach
    void setup() {
        mvc = MockMvcBuilders.webAppContextSetup(context).addFilters(filter).build();
        jdbc.execute("TRUNCATE teachmi.app_user CASCADE");
    }

    @Test
    void specificationParsesAndMatchesEveryARouteAndRecord() throws Exception {
        var parsed = new OpenAPIV3Parser().readLocation(SPEC.toUri().toString(), null, null);
        assertThat(parsed.getMessages()).isEmpty();
        Set<String> documented = new HashSet<>();
        parsed.getOpenAPI().getPaths().forEach((path, item) -> item.readOperationsMap().forEach((method, operation) -> {
            assertThat(operation.getOperationId()).isNotBlank();
            documented.add(method.name() + " " + path);
        }));
        Set<String> implemented = new HashSet<>();
        var controllers = Set.of(AuthController.class, Class.forName("com.teachmi.backend.material.MaterialController"));
        context.getBean("requestMappingHandlerMapping", RequestMappingHandlerMapping.class).getHandlerMethods()
                .forEach((mapping, method) -> {
                    if (controllers.contains(method.getBeanType())) {
                        mapping.getPatternValues().forEach(path -> mapping.getMethodsCondition().getMethods()
                                .forEach(verb -> implemented.add(verb.name() + " " + path)));
                    }
                });
        assertThat(documented).hasSize(10).isEqualTo(implemented);

        var schemas = json.readTree(Files.readString(SPEC)).path("components").path("schemas");
        String views = "com.teachmi.backend.material.MaterialViews$";
        Map<String, Class<?>> records = Map.ofEntries(
                Map.entry("Credentials", AuthController.Credentials.class),
                Map.entry("MemberView", AuthController.MemberView.class),
                Map.entry("LoginView", AuthController.LoginView.class),
                Map.entry("MeView", AuthController.MeView.class),
                Map.entry("ErrorBody", ApiErrorHandler.ErrorBody.class),
                Map.entry("ConceptInput", Class.forName(views + "ConceptInput")),
                Map.entry("CreateTextMaterialRequest", Class.forName(views + "CreateTextMaterialRequest")),
                Map.entry("AddConceptRequest", Class.forName(views + "AddConceptRequest")),
                Map.entry("UpdateConceptRequest", Class.forName(views + "UpdateConceptRequest")),
                Map.entry("SourceView", Class.forName(views + "SourceView")),
                Map.entry("ConceptView", Class.forName(views + "ConceptView")),
                Map.entry("MaterialView", Class.forName(views + "MaterialView")));
        records.forEach((name, type) -> assertThat(schemas.path(name).path("properties").propertyNames())
                .as("%s fields", name)
                .containsExactlyInAnyOrderElementsOf(Arrays.stream(type.getRecordComponents())
                        .map(component -> component.getName()).toList()));
    }

    @Test
    void everySuccessResponseMatchesTheContract() throws Exception {
        exchange(post("/api/v1/auth/signup").contentType(MediaType.APPLICATION_JSON)
                .content(credentials("flow@example.com")), 201, true);
        var member = login("flow@example.com");
        exchange(withMember(get("/api/v1/auth/me"), member, false), 200, true);
        var guest = exchange(post("/api/v1/auth/guest"), 201, true);
        assertThat(guest.path("guest").asBoolean()).isTrue();

        var material = exchange(withMember(post("/api/v1/materials"), member, true).header("Idempotency-Key", "m1")
                .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(Map.of(
                        "text", "감가상각은 취득원가를 내용연수 동안 배분하는 과정이다.",
                        "concept", Map.of("name", "감가상각의 개념", "scope", "배분하는 이유")))), 201, true);
        String materialId = material.path("materialId").asText();
        String conceptId = material.path("concepts").get(0).path("conceptId").asText();
        exchange(withMember(get("/api/v1/materials/" + materialId), member, false), 200, true);
        exchange(withMember(post("/api/v1/materials/" + materialId + "/concepts"), member, true)
                .header("Idempotency-Key", "c1").contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(Map.of("name", "내용연수", "scope", "사용 기간",
                        "evidenceText", "내용연수는 자산을 쓰는 기간이다."))), 201, true);
        exchange(withMember(patch("/api/v1/materials/" + materialId + "/concepts/" + conceptId), member, true)
                .contentType(MediaType.APPLICATION_JSON).content("{\"scope\":\"배분하는 이유와 기간\"}"), 200, true);
        // 파일 업로드 202 → 분석 실패 → 재시도 202
        var uploaded = exchangeMultipart(withMember(multipart("/api/v1/materials")
                .file(new MockMultipartFile("file", "broken.pdf", "application/pdf", "%PDF-1.7 broken".getBytes())),
                member, true).header("Idempotency-Key", "u1"), 202);
        assertThat(uploaded.path("status").asText()).isEqualTo("analyzing");
        String fileId = uploaded.path("materialId").asText();
        assertThat(awaitAnalysis(fileId, member).path("errorCode").asText()).isEqualTo("format");
        exchange(withMember(post("/api/v1/materials/" + fileId + "/retry"), member, true)
                .header("Idempotency-Key", "r1"), 202, true);
        awaitAnalysis(fileId, member);
        exchange(withMember(post("/api/v1/auth/logout"), member, true), 204, true);
    }

    @Test
    void uploadRejectionsMatchTheContract() throws Exception {
        signupAndReturn("upload@example.com");
        var member = login("upload@example.com");
        exchangeMultipart(withMember(multipart("/api/v1/materials")
                .file(new MockMultipartFile("file", "notes.txt", "text/plain", "hello".getBytes())), member, true)
                .header("Idempotency-Key", "t1"), 415);
        exchangeMultipart(withMember(multipart("/api/v1/materials")
                .file(new MockMultipartFile("file", "empty.pdf", "application/pdf", new byte[0])), member, true)
                .header("Idempotency-Key", "t2"), 422);
        var text = exchange(withMember(post("/api/v1/materials"), member, true).header("Idempotency-Key", "t3")
                .contentType(MediaType.APPLICATION_JSON).content("{\"text\":\"원문\"}"), 201, true);
        exchange(withMember(post("/api/v1/materials/" + text.path("materialId").asText() + "/retry"), member, true)
                .header("Idempotency-Key", "t4"), 409, true);
    }

    @Test
    void documentedErrorsMatchTheContract() throws Exception {
        String body = credentials("err@example.com");
        exchange(post("/api/v1/auth/signup").contentType(MediaType.APPLICATION_JSON).content(body), 201, true);
        exchange(post("/api/v1/auth/signup").contentType(MediaType.APPLICATION_JSON).content(body), 409, true);
        exchange(post("/api/v1/auth/signup").contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\":\"bad\",\"password\":\"correct-horse\"}"), 422, true);
        exchange(post("/api/v1/auth/signup").contentType(MediaType.APPLICATION_JSON).content("{"), 400, false);
        exchange(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\":\"err@example.com\",\"password\":\"wrong-password\"}"), 401, true);
        exchange(get("/api/v1/auth/me"), 401, false);

        var member = login("err@example.com");
        var other = login(signupAndReturn("other@example.com"));
        var material = exchange(withMember(post("/api/v1/materials"), member, true).header("Idempotency-Key", "m1")
                .contentType(MediaType.APPLICATION_JSON).content("{\"text\":\"원문\"}"), 201, true);
        String materialId = material.path("materialId").asText();
        exchange(withMember(get("/api/v1/materials/" + materialId), other, false), 404, true);
        exchange(withMember(get("/api/v1/materials/" + UUID.randomUUID()), member, false), 404, true);
        exchange(withMember(post("/api/v1/materials"), member, true).header("Idempotency-Key", "m1")
                .contentType(MediaType.APPLICATION_JSON).content("{\"text\":\"다른 원문\"}"), 409, true);
        exchange(withMember(post("/api/v1/materials"), member, true).header("Idempotency-Key", "m2")
                .contentType(MediaType.APPLICATION_JSON).content("{\"text\":\"   \"}"), 422, true);
        exchange(withMember(post("/api/v1/materials"), member, true)
                .contentType(MediaType.APPLICATION_JSON).content("{\"text\":\"원문\"}"), 422, false);
        exchange(withMember(post("/api/v1/materials"), member, false).header("Idempotency-Key", "m3")
                .contentType(MediaType.APPLICATION_JSON).content("{\"text\":\"원문\"}"), 403, false);
        exchange(withMember(patch("/api/v1/materials/" + materialId + "/concepts/" + UUID.randomUUID()), member, true)
                .contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"없는 개념\"}"), 404, true);
    }

    @Test
    void theValidatorRejectsUndocumentedOrMissingFields() {
        String path = "/api/v1/auth/me";
        String valid = "{\"id\":\"" + UUID.randomUUID() + "\",\"email\":\"a@example.com\",\"guest\":false,\"csrfToken\":\"t\"}";
        assertThat(validator.validateResponse(path, Request.Method.GET, response(200, valid)).hasErrors()).isFalse();
        assertThat(validator.validateResponse(path, Request.Method.GET,
                response(200, valid.replace("}", ",\"extra\":1}"))).hasErrors()).isTrue();
        assertThat(validator.validateResponse(path, Request.Method.GET,
                response(200, "{\"id\":\"" + UUID.randomUUID() + "\",\"email\":\"a@example.com\"}")).hasErrors()).isTrue();
    }

    // ---------------------------------------------------------------- helpers

    private String credentials(String email) throws Exception {
        return json.writeValueAsString(Map.of("email", email, "password", "correct-horse"));
    }

    private String signupAndReturn(String email) throws Exception {
        mvc.perform(post("/api/v1/auth/signup").contentType(MediaType.APPLICATION_JSON).content(credentials(email)));
        return email;
    }

    private Member login(String email) throws Exception {
        var result = mvc.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON)
                .content(credentials(email))).andReturn().getResponse();
        assertThat(result.getStatus()).isEqualTo(200);
        return new Member(result.getCookie("TEACHMI_SESSION"), json.readTree(result.getContentAsString()).path("csrfToken").asText());
    }

    private <B extends AbstractMockHttpServletRequestBuilder<B>> B withMember(B request, Member member, boolean csrf) {
        request.cookie(member.cookie());
        return csrf ? request.header(AuthFilter.CSRF_HEADER, member.csrf()) : request;
    }

    /** multipart 본문은 검증기가 해석하지 못해 응답과 경로·헤더만 대조한다. */
    private JsonNode exchangeMultipart(AbstractMockHttpServletRequestBuilder<?> builder, int status) throws Exception {
        var result = mvc.perform(builder).andReturn();
        var request = result.getRequest();
        var response = result.getResponse();
        assertThat(response.getStatus()).as(response.getContentAsString()).isEqualTo(status);
        var output = SimpleResponse.Builder.status(response.getStatus());
        response.getHeaderNames().forEach(name -> output.withHeader(name, List.copyOf(response.getHeaders(name))));
        if (response.getContentAsByteArray().length > 0) {
            output.withBody(response.getContentAsByteArray());
        }
        assertValid(validator.validateResponse(request.getRequestURI(), Request.Method.POST, output.build()));
        return json.readTree(response.getContentAsByteArray());
    }

    private JsonNode awaitAnalysis(String materialId, Member member) throws Exception {
        for (int i = 0; i < 100; i++) {
            var body = exchange(withMember(get("/api/v1/materials/" + materialId), member, false), 200, true);
            if (!"analyzing".equals(body.path("status").asText())) {
                return body;
            }
            Thread.sleep(100);
        }
        throw new AssertionError("Analysis did not finish");
    }

    private static SimpleResponse response(int status, String body) {
        return SimpleResponse.Builder.status(status).withContentType("application/json").withBody(body).build();
    }

    private JsonNode exchange(AbstractMockHttpServletRequestBuilder<?> builder, int status, boolean validRequest) throws Exception {
        var result = mvc.perform(builder).andReturn();
        var request = result.getRequest();
        var response = result.getResponse();
        assertThat(response.getStatus()).as(response.getContentAsString()).isEqualTo(status);
        var method = Request.Method.valueOf(request.getMethod());
        var input = new SimpleRequest.Builder(method, request.getRequestURI());
        request.getHeaderNames().asIterator().forEachRemaining(name ->
                input.withHeader(name, java.util.Collections.list(request.getHeaders(name))));
        if (request.getCookies() != null && request.getCookies().length > 0) {
            input.withHeader("Cookie", Arrays.stream(request.getCookies())
                    .map(cookie -> cookie.getName() + "=" + cookie.getValue()).toList());
        }
        if (request.getContentAsByteArray() != null && request.getContentAsByteArray().length > 0) {
            input.withBody(request.getContentAsByteArray());
        }
        var output = SimpleResponse.Builder.status(response.getStatus());
        response.getHeaderNames().forEach(name -> output.withHeader(name, List.copyOf(response.getHeaders(name))));
        if (response.getContentAsByteArray().length > 0) {
            output.withBody(response.getContentAsByteArray());
        }
        assertValid(validator.validateResponse(request.getRequestURI(), method, output.build()));
        var requestReport = validator.validateRequest(input.build());
        if (validRequest) {
            assertValid(requestReport);
        } else {
            assertThat(requestReport.hasErrors()).as(requestReport.toString()).isTrue();
        }
        return response.getContentAsByteArray().length == 0 ? json.nullNode() : json.readTree(response.getContentAsByteArray());
    }

    private static void assertValid(ValidationReport report) {
        assertThat(report.hasErrors()).as(report.getMessages().toString()).isFalse();
    }
}
