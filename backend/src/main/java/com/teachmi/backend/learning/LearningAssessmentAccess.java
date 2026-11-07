package com.teachmi.backend.learning;

import java.util.UUID;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** C writes share B's member -> owned session lock order and the caller's transaction. */
@Service
public class LearningAssessmentAccess {
    private final LearningSessionService access;
    private final LearningSessionRepository sessions;
    private final LearningMessageRepository messages;
    private final EntityManager entities;

    LearningAssessmentAccess(LearningSessionService access, LearningSessionRepository sessions,
                             LearningMessageRepository messages, EntityManager entities) {
        this.access = access; this.sessions = sessions; this.messages = messages;
        this.entities = entities;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public SessionView lockReady(String member, UUID id, Long expectedVersion) {
        var session = lockSession(member, id);
        if (!session.status.equals("active") || !session.stage.equals("problem")
                || session.pendingTurnId != null || session.activeReteachId != null
                || session.returnReteachId != null
                || (expectedVersion != null && session.version != expectedVersion)) {
            throw access.conflict(session);
        }
        var last = session.finalExplanationId == null ? null : messages.findById(session.finalExplanationId).orElse(null);
        if (last == null || !id.equals(last.sessionId) || last.reteachId != null
                || !last.role.equals("user") || !last.kind.equals("answer") || !last.chatStage.equals("wrapup")) {
            throw new LearningException(409, "FINAL_EXPLANATION_REQUIRED", "저장된 최초 학습의 최종 설명이 필요합니다.");
        }
        return access.view(session);
    }

    /** Lock before C reads request receipts, problems or a return marker. Historical replays are allowed. */
    @Transactional(propagation = Propagation.MANDATORY)
    public SessionView lockOwned(String member, UUID id) {
        return access.view(lockSession(member, id));
    }

    /** B's reteach transactions already hold the member/session locks. Do not discard their pending changes. */
    @Transactional(propagation = Propagation.MANDATORY)
    public void verifyFreshProblemEvidence(String member, UUID id, long evidenceVersion) {
        var session = sessions.findByIdAndMemberId(id, member).orElseThrow(LearningException::notFound);
        if (session.stage.equals("problem") && session.activeReteachId == null && session.returnReteachId == null
                && session.version != evidenceVersion) {
            throw new LearningException(409, "ASSESSMENT_EVIDENCE_STALE", "문항이 사용한 학습 근거 버전이 변경됐습니다.");
        }
    }

    private LearningSession lockSession(String member, UUID id) {
        if (TransactionSynchronizationManager.isCurrentTransactionReadOnly()) {
            throw new LearningException(409, "WRITABLE_TRANSACTION_REQUIRED", "쓰기 트랜잭션이 필요합니다.");
        }
        access.lockMember(member);
        var session = sessions.lockOwned(id, member).orElseThrow(LearningException::notFound);
        // C may have read this entity before waiting on the member lock; reload committed state.
        entities.refresh(session, LockModeType.PESSIMISTIC_WRITE);
        return session;
    }
}
