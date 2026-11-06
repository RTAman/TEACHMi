package com.teachmi.backend.learning;

import java.util.UUID;
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

    LearningAssessmentAccess(LearningSessionService access, LearningSessionRepository sessions,
                             LearningMessageRepository messages) {
        this.access = access; this.sessions = sessions; this.messages = messages;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public SessionView lockReady(String member, UUID id, Long expectedVersion) {
        if (TransactionSynchronizationManager.isCurrentTransactionReadOnly()) {
            throw new LearningException(409, "WRITABLE_TRANSACTION_REQUIRED", "쓰기 트랜잭션이 필요합니다.");
        }
        access.lockMember(member);
        var session = sessions.lockOwned(id, member).orElseThrow(LearningException::notFound);
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
}
