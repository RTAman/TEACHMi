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

    /**
     * 같은 개념의 바로 전 학습과 이번 학습을 비교한 숫자. 처음 학습하는 개념이면 리포트의 comparison이 null이다.
     * hints는 힌트 보기와 쉬운 예시 요청을 합친 수, hesitations는 "잘 모르겠어" 요청 수다.
     * keyElements(혼자 말한 핵심 요소 수)는 판정 데이터가 생기기 전까지 null이다.
     */
    record Comparison(UUID previousSessionId, Instant previousStudiedAt, Change keyElements, Change hints,
                      Change hesitations) {}

    /** 지난 학습의 값과 이번 학습의 값. 차이("2회 줄었어요")는 화면에서 previous - current로 계산한다. */
    record Change(int previous, int current) {}

    /**
     * status는 ready(리포트 준비) 또는 confirmed(학생이 확인해 학습 종료)다.
     * comparison은 저장된 본문이 아니라 조회할 때마다 계산한다.
     */
    record ReportView(UUID reportId, UUID sessionId, String status, Instant createdAt, Instant confirmedAt,
                      Concept concept, Explanation explanation, Application application, List<NextStep> nextSteps,
                      Comparison comparison) {}
}