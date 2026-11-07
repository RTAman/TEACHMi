package com.teachmi.backend.assessment;

import java.util.Objects;
import java.util.UUID;
import com.teachmi.backend.assessment.RelearnViews.RelearnRequest;
import com.teachmi.backend.assessment.RelearnViews.RelearnView;
import com.teachmi.backend.assessment.ReportViews.NextStep;
import com.teachmi.backend.assessment.ReportViews.ReportContent;
import com.teachmi.backend.auth.ApiException;
import com.teachmi.backend.learning.CreateSessionRequest;
import com.teachmi.backend.learning.LearningSessionService;
import com.teachmi.backend.learning.SessionView;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

/** 확인한 리포트에서 같은 개념의 새 학습을 시작하고, 원본 학습·리포트·보완 항목과 연결해 둔다. */
@Service
class AssessmentRelearnService {
    private final LearningSessionService sessions;
    private final AssessmentReportRepository reports;
    private final AssessmentRelearnRepository relearns;
    private final ObjectMapper json;

    AssessmentRelearnService(LearningSessionService sessions, AssessmentReportRepository reports,
                             AssessmentRelearnRepository relearns, ObjectMapper json) {
        this.sessions = sessions;
        this.reports = reports;
        this.relearns = relearns;
        this.json = json;
    }

    /** B의 세션 생성과 C의 연결 저장을 한 트랜잭션으로 묶는다. 둘 중 하나가 실패하면 둘 다 취소된다. */
    @Transactional
    RelearnView start(String memberId, UUID originId, String key, RelearnRequest body) {
        var request = body == null ? new RelearnRequest(null, null, null) : body;
        if ((request.replaceSessionId() == null) != (request.expectedVersion() == null)) {
            throw invalid("새 시작에는 replaceSessionId와 expectedVersion을 함께 보내세요.");
        }
        var origin = sessions.get(memberId, originId);
        var report = reports.findBySessionIdAndMemberId(originId, memberId)
                .orElseThrow(AssessmentReportService::notReady);
        if (report.confirmedAt == null) {
            throw new ApiException(409, "REPORT_NOT_CONFIRMED", "리포트를 확인한 뒤 다시 학습할 수 있습니다.");
        }
        var content = json.readValue(report.content, ReportContent.class);
        String focus = null;
        if (request.problemId() != null) {
            focus = content.nextSteps().stream().filter(step -> step.problemId().equals(request.problemId()))
                    .map(NextStep::focus).findFirst()
                    .orElseThrow(() -> invalid("리포트의 보완 항목에 있는 문항을 고르세요."));
        }
        // 회원 잠금·진행 중 학습 확인·같은 키 재전송은 B가 처리한다. C는 그 전에 아무것도 잠그거나 저장하지 않는다.
        var created = sessions.create(memberId, key, new CreateSessionRequest(origin.materialId(),
                origin.conceptId(), request.replaceSessionId(), request.expectedVersion()));
        var saved = relearns.findById(created.sessionId());
        if (saved.isPresent()) {
            // 같은 키의 재전송이다. 처음과 다른 보완 항목을 보냈다면 거절한다.
            if (!saved.get().originSessionId.equals(originId)
                    || !Objects.equals(saved.get().problemId, request.problemId())) {
                throw new ApiException(409, "IDEMPOTENCY_KEY_REUSED", "같은 키에 다른 요청을 보냈습니다.");
            }
            return view(created, saved.get());
        }
        if (!created.materialId().equals(origin.materialId()) || !created.conceptId().equals(origin.conceptId())) {
            // 이 키가 재학습이 아닌 다른 학습 생성에 이미 쓰였다.
            throw new ApiException(409, "IDEMPOTENCY_KEY_REUSED", "같은 키에 다른 요청을 보냈습니다.");
        }
        var link = relearns.saveAndFlush(new AssessmentRelearn(created.sessionId(), report, request.problemId(), focus));
        return view(created, link);
    }

    private static RelearnView view(SessionView session, AssessmentRelearn link) {
        return new RelearnView(session, link.originSessionId, link.reportId, link.problemId, link.focus);
    }

    /** 입력값 오류(422)를 C 오류 형식으로 만든다. */
    private static ApiException invalid(String message) {
        return new ApiException(422, "INVALID_REQUEST", message);
    }
}