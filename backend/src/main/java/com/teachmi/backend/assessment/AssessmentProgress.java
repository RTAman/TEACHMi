package com.teachmi.backend.assessment;

import java.util.List;

/** 리포트를 만들 수 있을 만큼 문항 풀이가 끝났는지 판단한다. 리포트 생성과 B의 상태 확인이 같은 기준을 쓴다. */
final class AssessmentProgress {
    private AssessmentProgress() {}

    /** 정답·해설 확인으로 확정됐거나, 마지막 판정이 보류인 문항은 끝난 것으로 본다. 오답만 남은 문항은 끝나지 않았다. */
    static boolean finished(AssessmentProblem problem, List<AssessmentAttempt> attempts) {
        if (attempts.stream().anyMatch(attempt -> attempt.judgement == null)) {
            return false;
        }
        if (problem.confirmedAt != null) {
            return true;
        }
        return !attempts.isEmpty() && "pending_review".equals(attempts.getLast().judgement);
    }
}