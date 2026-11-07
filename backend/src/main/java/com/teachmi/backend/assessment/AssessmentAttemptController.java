package com.teachmi.backend.assessment;
import java.util.UUID;
import com.teachmi.backend.auth.AuthenticatedMember;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import static com.teachmi.backend.assessment.AttemptViews.*;

@RestController
@RequestMapping("/api/v1/sessions/{sessionId}/problems/{problemId}/attempts")
class AssessmentAttemptController {
    // 원격 채점기(Gemini) 여부에 따라 채점 단계를 나누는 진입점을 거친다.
    private final AssessmentSubmissionService attempts;
    AssessmentAttemptController(AssessmentSubmissionService attempts) { this.attempts=attempts; }
    @PostMapping
    ResponseEntity<AttemptView> submit(HttpServletRequest http, @PathVariable String sessionId,
                                       @PathVariable String problemId, @RequestHeader(value="Idempotency-Key",required=false) String key,
                                       @Valid @RequestBody SubmitRequest request) {
        var member=AuthenticatedMember.require(http).userId().toString();
        return ResponseEntity.status(201).cacheControl(CacheControl.noStore())
                .body(attempts.submit(member,id(sessionId),id(problemId),key,request));
    }
    private UUID id(String value) {
        try { return UUID.fromString(value); }
        catch (IllegalArgumentException invalid) { throw AssessmentProblemService.notFound(); }
    }
}