package com.teachmi.backend.assessment;

import java.util.UUID;
import com.teachmi.backend.learning.LearningException;
import com.teachmi.backend.learning.ReteachProblemSource;
import org.springframework.stereotype.Component;

/**
 * B가 재설명을 시작하거나 같은 문항으로 돌아올 때 C의 오답 기록을 확인하는 연결 부품.
 * 정답과 풀이는 넘기지 않고, 채점 때 저장한 보완 개념(reteachFocus)만 돌려준다.
 */
@Component
class AssessmentReteachSource implements ReteachProblemSource {
    private final AssessmentProblemRepository problems;
    private final AssessmentAttemptRepository attempts;

    AssessmentReteachSource(AssessmentProblemRepository problems, AssessmentAttemptRepository attempts) {
        this.problems = problems;
        this.attempts = attempts;
    }

    @Override
    public Target loadWrongAttempt(String memberId, UUID sessionId, String problemId, String attemptId) {
        var problem = problems.findByIdAndSessionIdAndMemberId(uuid(problemId), sessionId, memberId)
                .orElseThrow(LearningException::notFound);
        var history = attempts.findByProblemIdOrderByAttemptNo(problem.id);
        var attempt = history.stream().filter(item -> item.id.toString().equals(attemptId)).findFirst()
                .orElseThrow(LearningException::notFound);
        if (problem.solutionViewedAt != null) {
            throw new LearningException(409, "SOLUTION_ALREADY_VIEWED", "해설을 본 문항은 다시 설명할 수 없습니다.");
        }
        // 최신 시도가 아니면 판정을 "outdated"로 보내 B가 409 WRONG_ATTEMPT_REQUIRED로 거절하게 한다.
        boolean latest = history.getLast().id.equals(attempt.id);
        String judgement = latest ? String.valueOf(attempt.judgement) : "outdated";
        return new Target(memberId, sessionId, problemId, attemptId, judgement, attempt.reteachFocus);
    }

    private static UUID uuid(String value) {
        try {
            return UUID.fromString(value);
        } catch (IllegalArgumentException invalid) {
            throw LearningException.notFound();
        }
    }
}