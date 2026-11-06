package com.teachmi.backend.assessment;

import java.util.Comparator;
import java.util.List;

/**
 * 첫 번째 시도에서 도움 없이 맞힌 문제만 독립 정답으로 계산한다.
 * 채점 보류 상태는 오답으로 계산하지 않는다.
 */
public record IndependentApplication(int independentCorrect, int total, int pendingReview, List<ProblemOutcome> outcomes) {
    public IndependentApplication {
        outcomes = List.copyOf(outcomes);
    }

    public static IndependentApplication of(List<List<AttemptFact>> problems) {
        var outcomes = problems.stream().map(IndependentApplication::outcome).toList();
        int correct = (int) outcomes.stream().filter(ProblemOutcome.FIRST_TRY_CORRECT::equals).count();
        int pending = (int) outcomes.stream().filter(ProblemOutcome.PENDING_REVIEW::equals).count();
        return new IndependentApplication(correct, problems.size(), pending, outcomes);
    }

    static ProblemOutcome outcome(List<AttemptFact> attempts) {
        var ordered = attempts.stream().sorted(Comparator.comparingInt(AttemptFact::attemptNo)).toList();
        if (ordered.isEmpty() || ordered.stream().anyMatch(attempt -> attempt.judgement() == null)) {
            return ProblemOutcome.UNFINISHED;
        }
        var first = ordered.getFirst();
        if (first.judgement().equals("correct") && first.reteachId() == null && !first.afterSolution()) {
            return ProblemOutcome.FIRST_TRY_CORRECT;
        }
        var last = ordered.getLast();
        return switch (last.judgement()) {
            case "pending_review" -> ProblemOutcome.PENDING_REVIEW;
            case "correct" -> last.afterSolution() ? ProblemOutcome.CORRECT_AFTER_SOLUTION : ProblemOutcome.CORRECT_AFTER_RETEACH;
            default -> ProblemOutcome.WRONG;
        };
    }
}