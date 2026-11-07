package com.teachmi.backend.assessment;

import java.util.UUID;
import com.teachmi.backend.assessment.RelearnViews.RelearnRequest;
import com.teachmi.backend.assessment.RelearnViews.RelearnView;
import com.teachmi.backend.auth.AuthenticatedMember;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

@RestController
class AssessmentRelearnController {
    private final AssessmentRelearnService relearns;

    AssessmentRelearnController(AssessmentRelearnService relearns) {
        this.relearns = relearns;
    }

    /** 리포트의 '한 번 더 설명하기'에서 호출한다. 새 학습은 B의 mode 단계에서 시작한다. */
    @PostMapping("/api/v1/sessions/{sessionId}/relearn")
    ResponseEntity<RelearnView> start(HttpServletRequest http, @PathVariable String sessionId,
                                      @RequestHeader(value = "Idempotency-Key", required = false) String key,
                                      @Valid @RequestBody(required = false) RelearnRequest request) {
        var member = AuthenticatedMember.require(http).userId().toString();
        return ResponseEntity.status(201).cacheControl(CacheControl.noStore())
                .body(relearns.start(member, id(sessionId), key, request));
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