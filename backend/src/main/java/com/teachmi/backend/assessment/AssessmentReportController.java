package com.teachmi.backend.assessment;

import java.util.UUID;
import com.teachmi.backend.assessment.ReportViews.ReportView;
import com.teachmi.backend.auth.AuthenticatedMember;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/sessions/{sessionId}/report")
class AssessmentReportController {
    private final AssessmentReportService reports;

    AssessmentReportController(AssessmentReportService reports) {
        this.reports = reports;
    }

    /** 마지막 문항의 finish_problems 다음에 호출한다. 학습 세션이 report 단계로 바뀐다. */
    @PostMapping
    ResponseEntity<ReportView> publish(HttpServletRequest http, @PathVariable String sessionId,
                                       @RequestHeader(value = "Idempotency-Key", required = false) String key) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(reports.publish(member(http), id(sessionId), key));
    }

    @GetMapping
    ResponseEntity<ReportView> get(HttpServletRequest http, @PathVariable String sessionId) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(reports.get(member(http), id(sessionId)));
    }

    /** 리포트 화면에서 학생이 확인을 누르면 호출한다. 학습이 completed로 끝난다. */
    @PostMapping("/confirm")
    ResponseEntity<ReportView> confirm(HttpServletRequest http, @PathVariable String sessionId,
                                       @RequestHeader(value = "Idempotency-Key", required = false) String key) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(reports.confirm(member(http), id(sessionId), key));
    }

    private static String member(HttpServletRequest http) {
        return AuthenticatedMember.require(http).userId().toString();
    }

    /** 형식이 틀린 ID는 없는 학습과 같은 404로 처리한다. */
    private static UUID id(String value) {
        try {
            return UUID.fromString(value);
        } catch (IllegalArgumentException invalid) {
            throw AssessmentProblemService.notFound();
        }
    }
}