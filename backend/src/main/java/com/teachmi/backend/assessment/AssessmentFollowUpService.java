package com.teachmi.backend.assessment;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;
import com.teachmi.backend.assessment.ProblemViews.ReteachRequest;
import com.teachmi.backend.assessment.ProblemViews.SolutionView;
import com.teachmi.backend.auth.ApiException;
import com.teachmi.backend.learning.LearningAssessmentAccess;
import com.teachmi.backend.learning.LearningReteachService;
import com.teachmi.backend.learning.LearningSessionService;
import com.teachmi.backend.learning.SessionView;
import com.teachmi.backend.learning.StartReteachRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 오답 뒤의 두 갈래: 다시 설명하기(B 대화로 전환)와 해설 보기(문항 확정). */
@Service
class AssessmentFollowUpService {
    private final LearningSessionService sessions;
    private final LearningAssessmentAccess access;
    private final LearningReteachService reteach;
    private final ReteachReturns returns;
    private final AssessmentProblemRepository problems;
    private final AssessmentAttemptRepository attempts;

    AssessmentFollowUpService(LearningSessionService sessions, LearningAssessmentAccess access,
                              LearningReteachService reteach, ReteachReturns returns, AssessmentProblemRepository problems,
                              AssessmentAttemptRepository attempts) {
        this.sessions = sessions;
        this.access = access;
        this.reteach = reteach;
        this.returns = returns;
        this.problems = problems;
        this.attempts = attempts;
    }

    /**
     * 오답 여부는 B가 AssessmentReteachSource로 확인한다. 여기서는 문항이 만들어진 뒤 학습 근거가 바뀌지 않았는지만 본다.
     * B는 AI 호출을 자기 트랜잭션 밖에서 하므로 이 메서드에는 @Transactional을 붙이지 않는다.
     */
    SessionView reteach(String memberId, UUID sessionId, UUID problemId, String key, ReteachRequest request) {
        var session = sessions.get(memberId, sessionId);
        var problem = problems.findByIdAndSessionIdAndMemberId(problemId, sessionId, memberId)
                .orElseThrow(AssessmentProblemService::notFound);
        // 이미 시작한 재설명의 같은 키 재전송은 B가 저장된 결과를 돌려주도록 검사 없이 넘긴다.
        boolean fresh = session.stage().equals("problem") && session.activeReteach() == null
                && session.problemReturn() == null;
        if (fresh && problem.evidenceVersion != session.version()) {
            throw new ApiException(409, "ASSESSMENT_EVIDENCE_STALE", "문항이 사용한 학습 근거 버전이 변경됐습니다.");
        }
        return reteach.start(memberId, sessionId, key, new StartReteachRequest(problemId.toString(),
                request.attemptId().toString(), request.expectedVersion()));
    }

    /** 제출한 문항의 정답과 풀이를 공개하고 열람 시점을 남긴다. 다시 열면 같은 내용을 돌려준다. */
    @Transactional
    SolutionView solution(String memberId, UUID sessionId, UUID problemId) {
        var session = access.lockOwned(memberId, sessionId);
        var problem = problems.findByIdAndSessionIdAndMemberId(problemId, sessionId, memberId)
                .orElseThrow(AssessmentProblemService::notFound);
        var history = attempts.findByProblemIdOrderByAttemptNo(problemId);
        var last = history.isEmpty() ? null : history.getLast().judgement;
        if (!"wrong".equals(last) && !"correct".equals(last)) {
            throw new ApiException(409, "SOLUTION_NOT_AVAILABLE", "답안을 채점한 뒤 해설을 볼 수 있습니다.");
        }
        if (problem.solutionViewedAt == null) {
            var returned = returns.pendingFor(session, problemId);
            if (returned != null) {
                returns.acknowledge(memberId, sessionId, returned, session.version());
            }
            var locked = access.lockReady(memberId, sessionId, null);
            if (returned != null) {
                returns.rebaseline(memberId, sessionId, locked.version());
            }
            if (problem.evidenceVersion != locked.version()) {
                throw new ApiException(409, "ASSESSMENT_EVIDENCE_STALE", "문항이 사용한 학습 근거 버전이 변경됐습니다.");
            }
            var now = Instant.now().truncatedTo(ChronoUnit.MICROS);
            problem.solutionViewedAt = now;
            if (problem.confirmedAt == null) {
                problem.confirmedAt = now;
            }
            problems.saveAndFlush(problem);
        }
        var next = problems.findBySessionIdAndMemberIdOrderBySequence(sessionId, memberId).stream()
                .filter(item -> item.sequence > problem.sequence).map(item -> item.id).findFirst().orElse(null);
        return new SolutionView(problem.id, problem.expectedAnswer, problem.solution, problem.solutionViewedAt,
                List.of(next == null ? "finish_problems" : "next_problem"), next);
    }
}
