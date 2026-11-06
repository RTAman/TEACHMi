package com.teachmi.backend.learning;

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
import com.atlassian.oai.validator.whitelist.ValidationErrorsWhitelist;
import io.swagger.v3.parser.OpenAPIV3Parser;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

import static com.atlassian.oai.validator.whitelist.rule.WhitelistRules.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;

@Tag("api-contract")
@SpringBootTest(classes = LearningTestApplication.class)
@Import({LearningSessionApiTest.Sources.class, LearningModeTransitionTest.Generation.class,
        LearningTurnsApiTest.Dialogue.class, LearningReteachApiTest.Providers.class})
class LearningOpenApiContractTest {
    private static final Path SPEC = Path.of("api/openapi.json");
    private static OpenApiInteractionValidator validator;
    @Autowired WebApplicationContext context;
    @Autowired ObjectMapper json;
    @Autowired JdbcTemplate jdbc;
    @Autowired LearningSessionApiTest.FixtureConceptSource concepts;
    @Autowired LearningModeTransitionTest.FixtureGenerator preparation;
    @MockitoSpyBean LearningTurnsApiTest.FixtureDialogue dialogue;
    @Autowired LearningReteachApiTest.Opening opening;
    @Autowired LearningReteachApiTest.Problems problems;
    @Autowired LearningTurnTransactions turnTransactions;
    @Autowired LearningReteachTransactions reteachTransactions;
    MockMvc mvc;

    @BeforeAll static void loadContract() {
        validator = OpenApiInteractionValidator.createForSpecificationUrl(SPEC.toUri().toString())
                .withWhitelist(ValidationErrorsWhitelist.create().withRule("Spring controls the optional 400 body",
                        allOf(responseStatusIs(400), messageHasKey("validation.response.body.missing"))))
                .build();
    }

    @BeforeEach void setup() {
        jdbc.execute("TRUNCATE teachmi.learning_reteach, teachmi.learning_turn, teachmi.learning_support_event, "
                + "teachmi.learning_session_request, teachmi.learning_message, teachmi.learning_session");
        concepts.unavailable = false;
        preparation.fail = false; preparation.unknownEvidence = false;
        dialogue.sufficient = true; dialogue.fail = false; dialogue.invalidHelp = false; dialogue.wrongEvidence = false;
        dialogue.entered = null; dialogue.release = null;
        opening.fail = false; opening.wrongEvidence = false; opening.entered = null; opening.release = null;
        problems.member = "member-1"; problems.problem = "p01"; problems.attempt = "a01";
        problems.judgement = "wrong"; problems.focus = "기간별 비용을 배분하는 이유";
        mvc = MockMvcBuilders.webAppContextSetup(context).build();
    }

    @Test void specificationParsesAndMatchesEveryPublicBRouteAndResponseRecord() throws Exception {
        var parsed = new OpenAPIV3Parser().readLocation(SPEC.toUri().toString(), null, null);
        assertThat(parsed.getMessages()).isEmpty();
        assertThat(parsed.getOpenAPI()).isNotNull();
        Set<String> documented = new HashSet<>();
        parsed.getOpenAPI().getPaths().forEach((path, item) -> item.readOperationsMap().forEach((method, operation) -> {
            assertThat(operation.getOperationId()).isNotBlank();
            documented.add(method.name() + " " + path);
        }));
        Set<String> implemented = new HashSet<>();
        context.getBean("requestMappingHandlerMapping", RequestMappingHandlerMapping.class).getHandlerMethods().forEach((mapping, method) -> {
            if (method.getBeanType() == LearningSessionController.class) {
                mapping.getPatternValues().forEach(path -> mapping.getMethodsCondition().getMethods()
                        .forEach(verb -> implemented.add(verb.name() + " " + path)));
            }
        });
        assertThat(documented).hasSize(8).isEqualTo(implemented);
        var schemas = json.readTree(Files.readString(SPEC)).path("components").path("schemas");
        Map<String, Class<?>> records = Map.ofEntries(
                Map.entry("SessionView", SessionView.class), Map.entry("LearningMessage", LearningMessageView.class),
                Map.entry("SupportEvent", SupportEventView.class), Map.entry("ReteachView", ReteachView.class),
                Map.entry("TurnProgress", TurnProgress.class), Map.entry("ProblemReturn", ProblemReturn.class),
                Map.entry("LearningEvidenceView", LearningEvidenceView.class), Map.entry("SupportCounts", LearningEvidenceView.SupportCounts.class),
                Map.entry("SupportUsage", LearningEvidenceView.SupportUsage.class), Map.entry("ExplanationEvidence", LearningEvidenceView.ExplanationEvidence.class),
                Map.entry("ReteachEvidence", LearningEvidenceView.ReteachEvidence.class), Map.entry("PendingGeneration", LearningEvidenceView.PendingGeneration.class),
                Map.entry("OriginalLearning", LearningEvidenceView.OriginalLearning.class), Map.entry("Summary", LearningContentGenerator.Summary.class),
                Map.entry("ConceptSnapshot", ConceptSource.Snapshot.class), Map.entry("Resume", SessionView.Resume.class),
                Map.entry("ErrorBody", LearningErrorHandler.ErrorBody.class),
                Map.entry("CreateSessionRequest", CreateSessionRequest.class), Map.entry("ChooseModeRequest", ChooseModeRequest.class),
                Map.entry("StartChatRequest", StartChatRequest.class), Map.entry("TurnRequest", TurnRequest.class),
                Map.entry("StartReteachRequest", StartReteachRequest.class));
        records.forEach((name, type) -> assertThat(schemas.path(name).path("properties").propertyNames())
                .as("%s fields", name).containsExactlyInAnyOrderElementsOf(Arrays.stream(type.getRecordComponents())
                        .map(component -> component.getName()).toList()));
    }

    @Test void firstLearningAndEveryPublicSuccessResponseMatchTheContract() throws Exception {
        exchange(get("/api/v1/sessions/active").principal(() -> "member-1"), 204, true);
        var created = create();
        String id = created.path("sessionId").asText();
        read(id);
        exchange(get("/api/v1/sessions/active").principal(() -> "member-1"), 200, true);
        exchange(get(path(id) + "/evidence").principal(() -> "member-1"), 200, true);
        write(put(path(id) + "/mode"), "mode", Map.of("mode", "first", "expectedVersion", version(id)), 200, true);
        write(post(path(id) + "/chat/start"), "start", Map.of("expectedVersion", version(id)), 200, true);
        for (String kind : List.of("hint", "example", "dont_know")) turn(id, kind, kind, null);
        for (int i = 0; i < 4; i++) turn(id, "answer-" + i, "answer", "선택한 개념의 의미와 이유를 설명합니다.");
        var evidence = exchange(get(path(id) + "/evidence").principal(() -> "member-1"), 200, true);
        assertThat(evidence.path("originalLearning").path("state").asText()).isEqualTo("completed");
        assertThat(evidence.path("originalLearning").path("evidence").path("support").path("delivered").path("total").asInt()).isEqualTo(3);
        var old = read(id);
        // C's report API is not implemented. These stored statuses only cover B's existing GET contract.
        jdbc.update("UPDATE teachmi.learning_session SET stage='report',status='completed' WHERE id=?", UUID.fromString(id));
        read(id);
        exchange(get("/api/v1/sessions/active").principal(() -> "member-1"), 204, true);
        jdbc.update("UPDATE teachmi.learning_session SET stage=?,status='abandoned' WHERE id=?", old.path("stage").asText(), UUID.fromString(id));
        read(id);
    }

    @Test void reviewReteachAndTheSameProblemReturnHaveTheirOwnTypedEvidence() throws Exception {
        String id = create().path("sessionId").asText();
        write(put(path(id) + "/mode"), "mode", Map.of("mode", "review", "expectedVersion", version(id)), 200, true);
        for (int i = 0; i < 4; i++) turn(id, "normal" + i, "answer", "최초 설명");
        problems.session = UUID.fromString(id);
        write(post(path(id) + "/reteach"), "reteach", Map.of("problemId", "p01", "attemptId", "a01", "expectedVersion", version(id)), 200, true);
        turn(id, "help", "hint", null);
        exchange(get(path(id) + "/evidence").principal(() -> "member-1"), 200, true);
        for (int i = 0; i < 4; i++) turn(id, "reteach" + i, "answer", "재설명의 의미와 이유");
        var returned = read(id);
        assertThat(returned.path("problemReturn").path("problemId").asText()).isEqualTo("p01");
        var evidence = exchange(get(path(id) + "/evidence").principal(() -> "member-1"), 200, true);
        assertThat(evidence.path("reteachHistory").get(0).path("state").asText()).isEqualTo("returned");
    }

    @Test void generatedFailedAndExpiredTurnAndReteachStatesMatchTheSameSchemas() throws Exception {
        String id = create().path("sessionId").asText();
        write(put(path(id) + "/mode"), "mode", Map.of("mode", "review", "expectedVersion", version(id)), 200, true);
        var request = new TurnRequest("answer", "복구할 설명", version(id));
        var claim = turnTransactions.begin("member-1", UUID.fromString(id), "pending", request);
        read(id);
        exchange(get(path(id) + "/evidence").principal(() -> "member-1"), 200, true);
        turnTransactions.fail(claim, "TEST_FAILURE");
        read(id);
        jdbc.update("UPDATE teachmi.learning_turn SET state='generating',lease_until=now()-interval '1 second'");
        read(id);
        write(post(path(id) + "/turns"), "pending", request, 200, true);
        for (int i = 0; i < 3; i++) turn(id, "rest" + i, "answer", "설명을 마칩니다.");
        problems.session = UUID.fromString(id);
        var openingClaim = reteachTransactions.begin("member-1", UUID.fromString(id), "pending-reteach", new StartReteachRequest("p01", "a01", version(id)));
        read(id);
        exchange(get(path(id) + "/evidence").principal(() -> "member-1"), 200, true);
        reteachTransactions.fail(openingClaim, "TEST_FAILURE");
        read(id);
        exchange(get(path(id) + "/evidence").principal(() -> "member-1"), 200, true);
        jdbc.update("UPDATE teachmi.learning_reteach SET state='generating',lease_until=now()-interval '1 second'");
        read(id);
        exchange(get(path(id) + "/evidence").principal(() -> "member-1"), 200, true);
    }

    @Test void domainErrorsKeepTheirStatusCodeAndNullableOrRestorableCurrentSession() throws Exception {
        String id = create().path("sessionId").asText();
        exchange(get(path(id)), 401, true);
        exchange(get(path(id)).principal(() -> "member-2"), 404, true);
        write(put(path(id) + "/mode"), "stale", Map.of("mode", "first", "expectedVersion", 4), 409, true);
        preparation.fail = true;
        write(put(path(id) + "/mode"), "generation-failed", Map.of("mode", "first", "expectedVersion", 0), 502, true);
        preparation.fail = false;
        write(put(path(id) + "/mode"), "review", Map.of("mode", "review", "expectedVersion", 0), 200, true);
        dialogue.fail = true;
        write(post(path(id) + "/turns"), "failed-turn", Map.of("kind", "answer", "text", "원래 입력", "expectedVersion", version(id)), 503, true);
        exchange(get("/api/v1/sessions/not-a-uuid").principal(() -> "member-1"), 400, false);
        var badJson = post(path(id) + "/turns").principal(() -> "member-1").header("Idempotency-Key", "json")
                .contentType("application/json").content("{");
        exchange(badJson, 400, false);
    }

    @Test void crossFieldInvalidInputsAndMissingKeysAreInvalidInBothSpecAndServer() throws Exception {
        write(post("/api/v1/sessions"), "bad-create", Map.of("materialId", "m01", "conceptId", "c01", "expectedVersion", 0), 422, false);
        String id = create().path("sessionId").asText();
        exchange(put(path(id) + "/mode").principal(() -> "member-1").contentType("application/json")
                .content("{\"mode\":\"review\",\"expectedVersion\":0}"), 422, false);
        write(put(path(id) + "/mode"), "mode", Map.of("mode", "review", "expectedVersion", 0), 200, true);
        for (Object body : List.of(Map.of("kind", "answer", "text", " ", "expectedVersion", version(id)),
                Map.of("kind", "hint", "text", "help text is forbidden", "expectedVersion", version(id)),
                Map.of("kind", "other", "expectedVersion", version(id)))) {
            write(post(path(id) + "/turns"), "invalid", body, 422, false);
        }
    }

    @Test void explicitNullCreationAndConfirmedReplacementReplayAreValidContracts() throws Exception {
        var body = json.createObjectNode().put("materialId", "m01").put("conceptId", "c01")
                .putNull("replaceSessionId").putNull("expectedVersion");
        var first = write(post("/api/v1/sessions"), "null-create", body, 201, true);
        String previous = first.path("sessionId").asText();
        var replacementBody = Map.of("materialId", "m01", "conceptId", "c02", "replaceSessionId", previous,
                "expectedVersion", first.path("version").asLong());
        var replacement = write(post("/api/v1/sessions"), "replace", replacementBody, 201, true);
        assertThat(write(post("/api/v1/sessions"), "replace", replacementBody, 201, true)).isEqualTo(replacement);
        assertThat(read(previous).path("status").asText()).isEqualTo("abandoned");
    }

    @Test void validatorRejectsMissingFieldsWrongTypesUnknownEnumsAndBodiesOn204() throws Exception {
        var real = (ObjectNode) create(); String path = path(real.path("sessionId").asText());
        var missing = real.deepCopy(); missing.remove("sessionId");
        assertRejected(path, missing);
        var wrongType = real.deepCopy(); wrongType.put("version", "zero"); assertRejected(path, wrongType);
        var wrongEnum = real.deepCopy(); wrongEnum.put("stage", "unknown"); assertRejected(path, wrongEnum);
        var unknownField = real.deepCopy(); unknownField.put("undocumented", true); assertRejected(path, unknownField);
        assertThat(validator.validateResponse("/api/v1/sessions/active", Request.Method.GET,
                SimpleResponse.Builder.status(204).withHeader("Cache-Control", "no-store")
                        .withContentType("application/json").withBody("{}").build()).hasErrors()).isTrue();
    }

    @Test void usageLimitsReturnTheDocumentedRestorable429Response() throws Exception {
        String id = create().path("sessionId").asText();
        write(put(path(id) + "/mode"), "mode", Map.of("mode", "review", "expectedVersion", 0), 200, true);
        doThrow(new LearningException(429, "AI_USAGE_LIMIT", "테스트 사용량 제한")).when(dialogue).reply(any());
        var error = write(post(path(id) + "/turns"), "limited", Map.of("kind", "hint", "expectedVersion", version(id)), 429, true);
        assertThat(error.path("code").asText()).isEqualTo("AI_USAGE_LIMIT");
        assertThat(error.path("currentSession").path("pendingTurn").path("state").asText()).isEqualTo("failed");
    }

    @Test void brokenEvidenceReturnsTheDocumented500Envelope() throws Exception {
        String id = create().path("sessionId").asText();
        write(put(path(id) + "/mode"), "mode", Map.of("mode", "review", "expectedVersion", 0), 200, true);
        UUID assistant = UUID.fromString(read(id).path("messages").get(0).path("messageId").asText());
        jdbc.update("UPDATE teachmi.learning_session SET final_explanation_id=? WHERE id=?", assistant, UUID.fromString(id));
        exchange(get(path(id) + "/evidence").principal(() -> "member-1"), 500, true);
    }

    private JsonNode create() throws Exception {
        return write(post("/api/v1/sessions"), "create", Map.of("materialId", "m01", "conceptId", "c01"), 201, true);
    }
    private String path(String id) { return "/api/v1/sessions/" + id; }
    private long version(String id) throws Exception { return read(id).path("version").asLong(); }
    private JsonNode read(String id) throws Exception { return exchange(get(path(id)).principal(() -> "member-1"), 200, true); }
    private void turn(String id, String key, String kind, String text) throws Exception {
        write(post(path(id) + "/turns"), key, text == null ? Map.of("kind", kind, "expectedVersion", version(id))
                : Map.of("kind", kind, "text", text, "expectedVersion", version(id)), 200, true);
    }
    private JsonNode write(MockHttpServletRequestBuilder request, String key, Object body, int status, boolean valid) throws Exception {
        return exchange(request.principal(() -> "member-1").header("Idempotency-Key", key).header("X-CSRF-Token", "contract-csrf")
                .contentType("application/json").content(json.writeValueAsString(body)), status, valid);
    }
    private JsonNode exchange(MockHttpServletRequestBuilder builder, int status, boolean validRequest) throws Exception {
        // B-only fixture authentication; real A cookies and CSRF are verified in LearningAuthenticatedContractTest.
        builder.header("Cookie", "TEACHMI_SESSION=contract-cookie");
        MvcResult result = mvc.perform(builder).andReturn();
        assertThat(result.getResponse().getStatus()).isEqualTo(status);
        var request = result.getRequest(); var response = result.getResponse();
        var input = new SimpleRequest.Builder(Request.Method.valueOf(request.getMethod()), request.getRequestURI());
        request.getHeaderNames().asIterator().forEachRemaining(name -> input.withHeader(name, java.util.Collections.list(request.getHeaders(name))));
        if (request.getContentAsByteArray() != null && request.getContentAsByteArray().length > 0) input.withBody(request.getContentAsByteArray());
        var output = SimpleResponse.Builder.status(response.getStatus());
        response.getHeaderNames().forEach(name -> output.withHeader(name, List.copyOf(response.getHeaders(name))));
        if (response.getContentAsByteArray().length > 0) output.withBody(response.getContentAsByteArray());
        assertValid(validator.validateResponse(request.getRequestURI(), Request.Method.valueOf(request.getMethod()), output.build()));
        var requestReport = validator.validateRequest(input.build());
        if (validRequest) assertValid(requestReport); else assertThat(requestReport.hasErrors()).as(requestReport.toString()).isTrue();
        return response.getContentAsByteArray().length == 0 ? json.nullNode() : json.readTree(response.getContentAsByteArray());
    }
    private void assertValid(ValidationReport report) { assertThat(report.hasErrors()).as(report.getMessages().toString()).isFalse(); }
    private void assertRejected(String path, JsonNode body) {
        assertThat(validator.validateResponse(path, Request.Method.GET,
                SimpleResponse.Builder.ok().withContentType("application/json").withBody(body.toString()).build()).hasErrors()).isTrue();
    }
}
