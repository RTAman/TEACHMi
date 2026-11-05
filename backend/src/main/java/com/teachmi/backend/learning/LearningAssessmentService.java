package com.teachmi.backend.learning;

import java.util.UUID;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.validation.annotation.Validated;
import tools.jackson.databind.ObjectMapper;

/** C-only coordination: lock B, persist C, verify persisted C facts, then update B and the receipt. */
@Service
@Validated
public class LearningAssessmentService {
    @FunctionalInterface
    public interface Persistence {
        /** DB writes only, on this thread and datasource in the existing transaction; propagate failures. */
        void save(SessionView lockedSession);
    }

    private record Fingerprint(String operation, UUID sessionId, AssessmentTransitionRequest request) {}
    private record ReportLink(String reportId, long revision, long evidenceVersion) {}

    private final LearningSessionService access;
    private final LearningSessionRepository sessions;
    private final LearningMessageRepository messages;
    private final ObjectProvider<AssessmentStateSource> sources;
    private final ObjectMapper json;

    LearningAssessmentService(LearningSessionService access, LearningSessionRepository sessions,
            LearningMessageRepository messages, ObjectProvider<AssessmentStateSource> sources, ObjectMapper json) {
        this.access = access; this.sessions = sessions; this.messages = messages; this.sources = sources; this.json = json;
    }

    @Transactional
    public SessionView publishReport(@NotBlank @Size(max = 128) String memberId, @NotNull UUID sessionId,
            String key, @NotNull @Valid AssessmentTransitionRequest request, @NotNull Persistence persistence) {
        return transition(memberId, sessionId, key, request, persistence, false);
    }

    @Transactional
    public SessionView completeLearning(@NotBlank @Size(max = 128) String memberId, @NotNull UUID sessionId,
            String key, @NotNull @Valid AssessmentTransitionRequest request, @NotNull Persistence persistence) {
        return transition(memberId, sessionId, key, request, persistence, true);
    }

    private SessionView transition(String member, UUID id, String key, AssessmentTransitionRequest request,
            Persistence persistence, boolean complete) {
        access.validateKey(key);
        if (TransactionSynchronizationManager.isCurrentTransactionReadOnly()) {
            throw new LearningException(409, "WRITABLE_TRANSACTION_REQUIRED", "상태 전환에는 쓰기 트랜잭션이 필요합니다.");
        }
        access.lockMember(member);
        var session = sessions.lockOwned(id, member).orElseThrow(LearningException::notFound);
        String body = json.writeValueAsString(new Fingerprint(complete ? "assessment/complete" : "assessment/report-ready", id, request));
        var replay = access.replay(member, key, body);
        if (replay.isPresent()) return replay.get();
        verifySession(session, request, complete);
        requireFinalExplanation(session);
        ReportLink previous = null;
        if (complete) {
            if (session.assessmentReportSnapshot == null) {
                throw new LearningException(409, "REPORT_HANDOFF_REQUIRED", "검증된 리포트 준비 전환이 필요합니다.");
            }
            previous = json.readValue(session.assessmentReportSnapshot, ReportLink.class);
            if (!request.reportId().equals(previous.reportId())) throw reportChanged();
        }
        var source = sources.getIfAvailable();
        if (source == null) {
            throw new LearningException(503, "ASSESSMENT_INTEGRATION_PENDING", "C의 실제 평가·리포트 상태 조회 연결이 필요합니다.");
        }
        // Successful replays and rejected commands never execute C's writes.
        persistence.save(access.view(session));
        var state = source.loadOwnedReport(member, id, request.reportId());
        verifySession(session, request, complete);
        if (state == null || !member.equals(state.memberId()) || !id.equals(state.sessionId())
                || !request.reportId().equals(state.reportId())) throw LearningException.notFound();
        if (state.revision() < 0 || state.evidenceVersion() < 0) {
            throw new LearningException(502, "INVALID_ASSESSMENT_STATE", "C의 리포트 버전과 근거를 확인할 수 없습니다.");
        }
        if (!state.assessmentComplete() || !state.reportReady()) {
            throw new LearningException(409, "ASSESSMENT_NOT_READY", "C의 평가와 리포트 저장이 완료되어야 합니다.");
        }
        var link = new ReportLink(state.reportId(), state.revision(), state.evidenceVersion());
        if (complete) {
            if (!previous.equals(link)) throw reportChanged();
            if (!state.completionAllowed()) {
                throw new LearningException(409, "LEARNING_COMPLETION_NOT_APPROVED", "C의 학습 종료 기준 확인이 필요합니다.");
            }
            session.status = "completed";
        } else {
            if (state.evidenceVersion() != session.version) {
                throw new LearningException(409, "ASSESSMENT_EVIDENCE_STALE", "리포트가 사용한 학습 근거 버전이 변경됐습니다.");
            }
            session.assessmentReportSnapshot = json.writeValueAsString(link);
            session.stage = "report";
        }
        session.touch();
        sessions.flush();
        var result = access.view(session);
        access.remember(member, key, body, result);
        return result;
    }

    private void requireFinalExplanation(LearningSession session) {
        var message = session.finalExplanationId == null ? null : messages.findById(session.finalExplanationId).orElse(null);
        if (message == null || !session.id.equals(message.sessionId) || message.reteachId != null
                || !message.role.equals("user") || !message.kind.equals("answer") || !message.chatStage.equals("wrapup")) {
            throw new LearningException(409, "FINAL_EXPLANATION_REQUIRED", "저장된 최초 학습의 최종 설명이 필요합니다.");
        }
    }

    private void verifySession(LearningSession session, AssessmentTransitionRequest request, boolean complete) {
        if (!session.status.equals("active") || session.version != request.expectedVersion()
                || !session.stage.equals(complete ? "report" : "problem") || session.pendingTurnId != null
                || session.activeReteachId != null || session.returnReteachId != null) throw access.conflict(session);
    }

    private LearningException reportChanged() {
        return new LearningException(409, "ASSESSMENT_REPORT_CHANGED", "준비된 리포트와 종료 요청의 리포트 근거가 다릅니다.");
    }
}
