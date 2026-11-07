package com.teachmi.backend.assessment;

import java.util.UUID;
import com.teachmi.backend.assessment.ProblemViews.ProblemSetView;
import com.teachmi.backend.assessment.ProblemViews.ProblemView;
import com.teachmi.backend.assessment.ProblemViews.ReteachRequest;
import com.teachmi.backend.assessment.ProblemViews.SolutionView;
import com.teachmi.backend.auth.AuthenticatedMember;
import com.teachmi.backend.learning.SessionView;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/sessions/{sessionId}/problems")
class AssessmentProblemController {
    private final AssessmentProblemService problems;
    private final AssessmentFollowUpService followUps;

    AssessmentProblemController(AssessmentProblemService problems, AssessmentFollowUpService followUps) {
        this.problems = problems;
        this.followUps = followUps;
    }

    /** 세션당 문항이 한 번만 고정되므로 재전송해도 문항이 늘지 않는다. */
    @PostMapping
    ResponseEntity<ProblemSetView> start(HttpServletRequest http, @PathVariable String sessionId) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(problems.start(member(http), id(sessionId)));
    }

    @GetMapping("/{problemId}")
    ResponseEntity<ProblemView> get(HttpServletRequest http, @PathVariable String sessionId,
                                    @PathVariable String problemId) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(problems.get(member(http), id(sessionId), id(problemId)));
    }

    /** 성공하면 B의 학습 세션이 재설명 대화(stage=chat)로 바뀐 상태를 돌려준다. */
    @PostMapping("/{problemId}/reteach")
    ResponseEntity<SessionView> reteach(HttpServletRequest http, @PathVariable String sessionId,
                                        @PathVariable String problemId,
                                        @RequestHeader(value = "Idempotency-Key", required = false) String key,
                                        @Valid @RequestBody ReteachRequest request) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(followUps.reteach(member(http), id(sessionId), id(problemId), key, request));
    }

    @PostMapping("/{problemId}/solution")
    ResponseEntity<SolutionView> solution(HttpServletRequest http, @PathVariable String sessionId,
                                          @PathVariable String problemId) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(followUps.solution(member(http), id(sessionId), id(problemId)));
    }

    private static String member(HttpServletRequest http) {
        return AuthenticatedMember.require(http).userId().toString();
    }

    /** 형식이 틀린 ID는 없는 문항과 같은 404로 처리한다. */
    private static UUID id(String value) {
        try {
            return UUID.fromString(value);
        } catch (IllegalArgumentException invalid) {
            throw AssessmentProblemService.notFound();
        }
    }
}