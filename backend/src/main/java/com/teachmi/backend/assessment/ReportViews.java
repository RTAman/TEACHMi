package com.teachmi.backend.assessment;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import com.teachmi.backend.learning.LearningEvidenceView.SupportCounts;

final class ReportViews {
    private ReportViews() {}

    /** DB에 저장하는 리포트 본문. 한 번 저장한 뒤에는 바꾸지 않는다. */
    record ReportContent(int evidenceSchemaVersion, Concept concept, Explanation explanation,
                         Application application, List<NextStep> nextSteps) {}

    record Concept(String name, String scope) {}

    /** 학생이 실제로 쓴 문장. 요약하거나 고치지 않는다. */
    record Quote(UUID messageId, String text) {}

    /**
     * result는 independent(도움 없이 설명) 또는 with_support(도움을 받은 뒤 설명)다.
     * 원리를 제대로 설명했는지에 대한 판정은 AI 평가가 연결된 뒤에 추가한다.
     */
    record Explanation(String result, Quote initialAnswer, Quote finalExplanation,
                       SupportCounts requestedSupport, SupportCounts deliveredSupport, int reteachCount) {}

    /** independentCorrect는 첫 시도에 도움 없이 맞힌 문항 수, total은 실제 출제 수다. 보류는 오답에 넣지 않는다. */
    record Application(int independentCorrect, int total, int pendingReview, List<ProblemResult> problems) {}

    record ProblemResult(UUID problemId, int sequence, String question, ProblemOutcome outcome,
                         boolean solutionViewed, List<AttemptResult> attempts) {}

    record AttemptResult(UUID attemptId, int attemptNo, String judgement, UUID reteachId) {}

    /** 다시 학습할 거리. 오답 때 채점기가 남긴 보완 개념을 쓴다. */
    record NextStep(UUID problemId, String focus) {}

    /** status는 ready(리포트 준비) 또는 confirmed(학생이 확인해 학습 종료)다. */
    record ReportView(UUID reportId, UUID sessionId, String status, Instant createdAt, Instant confirmedAt,
                      Concept concept, Explanation explanation, Application application, List<NextStep> nextSteps) {}
}