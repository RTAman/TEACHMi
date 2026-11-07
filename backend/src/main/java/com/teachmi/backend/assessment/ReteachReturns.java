package com.teachmi.backend.assessment;

import java.util.UUID;
import com.teachmi.backend.auth.ApiException;
import com.teachmi.backend.learning.LearningReteachService;
import com.teachmi.backend.learning.ProblemReturn;
import com.teachmi.backend.learning.SessionView;
import org.springframework.stereotype.Component;

/**
 * 재설명에서 돌아온 문항을 C가 이어받을 때의 공통 처리.
 * 반드시 호출한 쪽의 트랜잭션 안에서, lockReady보다 먼저 호출한다.
 */
@Component
class ReteachReturns {
    private final LearningReteachService reteach;
    private final AssessmentProblemRepository problems;

    ReteachReturns(LearningReteachService reteach, AssessmentProblemRepository problems) {
        this.reteach = reteach;
        this.problems = problems;
    }

    /** 이 문항으로 돌아온 재설명이 있으면 반환한다. 다른 문항의 복귀가 남아 있으면 409를 낸다. */
    ProblemReturn pendingFor(SessionView session, UUID problemId) {
        var returned = session.problemReturn();
        if (returned == null) {
            return null;
        }
        if (!returned.problemId().equals(problemId.toString())) {
            throw new ApiException(409, "RETRY_PENDING", "다시 설명한 문항을 먼저 마무리하세요.");
        }
        return returned;
    }

    /**
     * B의 복귀 표시를 소비한다. lockReady는 복귀 표시가 남아 있으면 거절하므로 먼저 호출해야 하고,
     * B가 원래 오답이 최신 시도인지 다시 확인하므로 새 시도를 저장하기 전에 호출해야 한다.
     */
    void acknowledge(String memberId, UUID sessionId, ProblemReturn returned, long expectedVersion) {
        reteach.acknowledgeProblemReturn(memberId, sessionId, returned.reteachId(), expectedVersion);
    }

    /**
     * 재설명 대화는 학습 세션 version을 올리므로, 검증된 재설명 복귀 직후에만 문항의 근거 버전을 새 version으로 맞춘다.
     * 그 밖의 version 변경은 계속 ASSESSMENT_EVIDENCE_STALE로 거절된다.
     */
    void rebaseline(String memberId, UUID sessionId, long version) {
        var saved = problems.findBySessionIdAndMemberIdOrderBySequence(sessionId, memberId);
        saved.forEach(problem -> problem.evidenceVersion = version);
        problems.saveAllAndFlush(saved);
    }
}