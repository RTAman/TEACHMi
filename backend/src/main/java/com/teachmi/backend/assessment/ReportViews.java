package com.teachmi.backend.assessment;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import com.teachmi.backend.learning.LearningEvidenceView.SupportCounts;

final class ReportViews {
    private ReportViews() {}

    /** DB에 저장하는 리포트 본문. 한 번 저장한 뒤에는 바꾸지 않는다. */
    record ReportContent(int evidenceSchemaVersion, Concept concept, Explanation explanation,
                         Application application, List<NextStep> nextSteps, KeyPoints keyPoints, Flow flow) {}
    // keyPoints·flow가 생기기 전에 저장한 리포트는 두 값이 null로 읽힌다.

    /**
     * 학생 설명에 들어간 개념의 핵심 요소. AI가 원문을 근거로 판정한다.
     * initialCount는 첫 설명, finalCount는 최종 설명에 들어간 요소 수다(예: 1 → 3개).
     * AI가 연결되지 않았거나 판정에 실패하면 리포트에서 null이다.
     */
    record KeyPoints(int total, int initialCount, int finalCount, List<KeyPoint> items) {}

    record KeyPoint(String point, boolean inInitial, boolean inFinal) {}

    /**
     * 처음 학습 대화의 흐름. 학생의 답변·도움 요청 하나가 점 하나다(재설명 대화는 넣지 않는다).
     * hesitationCount는 30초 이상 입력이 없었던 질문 수다.
     */
    record Flow(int hesitationCount, List<FlowPoint> points) {}

    /**
     * stageLevel은 case=1, concept=2, reason=3, wrapup=4다. action은 answer·hint·example·dont_know다.
     * accepted는 answer가 그 단계를 통과했는지이며, 도움 요청은 null이다.
     * hesitated는 이 행동 직전 질문에서 30초 이상 망설였는지다.
     */
    record FlowPoint(int index, String chatStage, int stageLevel, String action, Boolean accepted,
                     long secondsFromStart, boolean hesitated) {}

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
                      Concept concept, Explanation explanation, Application application, List<NextStep> nextSteps,
                      KeyPoints keyPoints, Flow flow) {}
}