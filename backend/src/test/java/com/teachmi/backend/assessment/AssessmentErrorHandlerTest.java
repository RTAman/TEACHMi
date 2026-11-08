package com.teachmi.backend.assessment;

import com.teachmi.backend.auth.ApiException;
import com.teachmi.backend.learning.GenerationRecovery;
import com.teachmi.backend.learning.LearningException;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.json.JsonMapper;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

class AssessmentErrorHandlerTest {
    @RestController static class Controller {
        @GetMapping("/errors/uncertain") void uncertain() {throw new LearningException(503,"AI_GENERATION_UNCERTAIN","확인이 필요합니다.");}
        @GetMapping("/errors/limit") void limit() {throw new LearningException(429,"AI_ATTEMPT_LIMIT","한도에 도달했습니다.");}
        @GetMapping("/errors/quote") void quote() {throw new LearningException(502,"INVALID_GEMINI_RESPONSE","출처 검증 실패",null,
                new GenerationRecovery("RESPONSE_VALIDATION","EVIDENCE_QUOTE_TOO_LONG",true,"retry_same_request"));}
        @GetMapping("/errors/notfound") void notFound() {throw ApiException.notFound();}
    }
    final org.springframework.test.web.servlet.MockMvc mvc=MockMvcBuilders.standaloneSetup(new Controller())
            .setControllerAdvice(new AssessmentErrorHandler()).build();
    @Test void uncertainIncludesStaticRecoveryRatherThanOnlyCodeAndMessage() throws Exception {
        var response=mvc.perform(get("/errors/uncertain")).andReturn().getResponse();
        assertThat(response.getStatus()).isEqualTo(503);
        var body=JsonMapper.builder().build().readTree(response.getContentAsByteArray());
        assertThat(body.path("code").asText()).isEqualTo("AI_GENERATION_UNCERTAIN");
        assertThat(body.path("recovery").path("action").asText()).isEqualTo("review_required");
        assertThat(body.path("recovery").path("retryAllowed").asBoolean()).isFalse();
    }
    @Test void attemptLimitEndsLoadingAndDisallowsRetry() throws Exception {
        var response=mvc.perform(get("/errors/limit")).andReturn().getResponse();
        assertThat(response.getStatus()).isEqualTo(429);
        var body=JsonMapper.builder().build().readTree(response.getContentAsByteArray());
        assertThat(body.path("recovery").path("action").asText()).isEqualTo("limit_reached");
        assertThat(body.path("recovery").path("retryAllowed").asBoolean()).isFalse();
    }
    @Test void validationDiagnosticAndExplicitRetryArePreserved() throws Exception {
        var response=mvc.perform(get("/errors/quote")).andReturn().getResponse();
        assertThat(response.getStatus()).isEqualTo(502);
        var body=JsonMapper.builder().build().readTree(response.getContentAsByteArray());
        assertThat(body.path("recovery").path("diagnosticCode").asText()).isEqualTo("EVIDENCE_QUOTE_TOO_LONG");
        assertThat(body.path("recovery").path("retryAllowed").asBoolean()).isTrue();
    }
    @Test void nonGenerationErrorsKeepTheExistingJsonShape() throws Exception {
        var response=mvc.perform(get("/errors/notfound")).andReturn().getResponse();
        assertThat(response.getStatus()).isEqualTo(404);
        var body=JsonMapper.builder().build().readTree(response.getContentAsByteArray());
        assertThat(body.size()).isEqualTo(2);
        assertThat(body.has("recovery")).isFalse();
    }
}
