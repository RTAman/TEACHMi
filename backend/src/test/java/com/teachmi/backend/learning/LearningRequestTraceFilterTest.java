package com.teachmi.backend.learning;

import java.util.UUID;
import jakarta.servlet.ServletException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.slf4j.MDC;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import static org.assertj.core.api.Assertions.*;

@ExtendWith(OutputCaptureExtension.class)
class LearningRequestTraceFilterTest {
    @AfterEach void clear() { MDC.clear(); }

    @Test void serverReferenceIsReturnedOnErrorAndCannotBeChosenByClient(CapturedOutput output) throws Exception {
        var request=new MockHttpServletRequest("PUT","/api/v1/sessions/"+UUID.randomUUID()+"/mode");
        request.addHeader(LearningRequestTraceFilter.HEADER,"client-secret-sentinel");
        request.addHeader("Cookie","private-cookie-sentinel");
        request.setQueryString("token=private-query-sentinel");
        request.setContent("private-answer-sentinel".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        var response=new MockHttpServletResponse();
        new LearningRequestTraceFilter().doFilter(request,response,(req,res) -> {
            assertThat(LearningRequestTraceFilter.currentRequestId()).isNotEqualTo("none");
            response.setStatus(503);
        });
        String id=response.getHeader(LearningRequestTraceFilter.HEADER);
        assertThat(UUID.fromString(id).toString()).isEqualTo(id);
        assertThat(output.getAll()).contains("requestId="+id,"httpStatus=503")
                .doesNotContain("client-secret-sentinel","private-cookie-sentinel","private-query-sentinel","private-answer-sentinel");
        assertThat(LearningRequestTraceFilter.currentRequestId()).isEqualTo("none");
    }

    @Test void requestsHaveDifferentReferencesAndNestedContextIsRestoredEvenOnFailure() throws Exception {
        String previous=UUID.randomUUID().toString();
        MDC.put("teachmiRequestId",previous);
        var request=new MockHttpServletRequest("GET","/api/v1/sessions/active");
        var response=new MockHttpServletResponse();
        assertThatThrownBy(() -> new LearningRequestTraceFilter().doFilter(request,response,(req,res) -> {
            throw new ServletException("private-exception-sentinel");
        })).isInstanceOf(ServletException.class);
        assertThat(LearningRequestTraceFilter.currentRequestId()).isEqualTo(previous);
        var second=new MockHttpServletResponse();
        new LearningRequestTraceFilter().doFilter(new MockHttpServletRequest("GET","/api/v1/sessions/active"),second,(req,res) -> {});
        assertThat(second.getHeader(LearningRequestTraceFilter.HEADER)).isNotEqualTo(response.getHeader(LearningRequestTraceFilter.HEADER));
    }

    @Test void unrelatedRoutesAndSimilarPrefixesAreNotTraced() throws Exception {
        for(String uri:new String[]{"/api/v1/auth/me","/api/v1/sessions-other"}) {
            var response=new MockHttpServletResponse();
            new LearningRequestTraceFilter().doFilter(new MockHttpServletRequest("GET",uri),response,(req,res) -> {});
            assertThat(response.getHeader(LearningRequestTraceFilter.HEADER)).isNull();
        }
    }
}
