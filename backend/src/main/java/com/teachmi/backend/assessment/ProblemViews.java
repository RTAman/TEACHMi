package com.teachmi.backend.assessment;

import java.util.List;
import java.util.UUID;

/** 제출 전 응답. 정답·해설·채점 기준 필드를 두지 않아 실수로 내보낼 수 없게 한다. */
final class ProblemViews {
    private ProblemViews() {}

    record ProblemView(UUID problemId, int sequence, int total, String question) {}

    record ProblemSetView(UUID sessionId, int total, List<ProblemView> problems) {}
}