package com.teachmi.backend.assessment;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;

/** 제출 전 응답. 정답·해설·채점 기준 필드를 두지 않아 실수로 내보낼 수 없게 한다. */
final class ProblemViews {
    private ProblemViews() {}

    record ProblemView(UUID problemId, int sequence, int total, String question) {}

    record ProblemSetView(UUID sessionId, int total, List<ProblemView> problems) {}

    /** expectedVersion에는 최신 학습 세션 조회(GET /sessions/{id})의 version을 보낸다. */
    record ReteachRequest(@NotNull UUID attemptId, @NotNull @PositiveOrZero Long expectedVersion) {}

    /** 답안을 채점한 뒤에만 만들어지는 응답이라 정답과 풀이를 담는다. */
    record SolutionView(UUID problemId, String answer, String solution, Instant viewedAt,
                        List<String> availableActions, UUID nextProblemId) {}
}