package com.teachmi.backend.assessment;

import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class IndependentApplicationTest {
    private static final UUID RETEACH = UUID.randomUUID();

    private static AttemptFact first(String judgement) {
        return new AttemptFact(1, judgement, null, false);
    }

    private static AttemptFact retry(String judgement) {
        return new AttemptFact(2, judgement, RETEACH, false);
    }

    @Test
    void scenarioA_bothFirstTryCorrect() {
        var result = IndependentApplication.of(List.of(List.of(first("correct")), List.of(first("correct"))));
        assertThat(result.independentCorrect()).isEqualTo(2);
        assertThat(result.total()).isEqualTo(2);
    }

    @Test
    void scenarioB_oneFirstTryCorrect() {
        var result = IndependentApplication.of(List.of(List.of(first("correct")), List.of(first("wrong"))));
        assertThat(result.independentCorrect()).isEqualTo(1);
    }

    @Test
    void scenarioC_bothWrong() {
        var result = IndependentApplication.of(List.of(List.of(first("wrong")), List.of(first("wrong"))));
        assertThat(result.independentCorrect()).isZero();
        assertThat(result.outcomes()).containsOnly(ProblemOutcome.WRONG);
    }

    @Test
    void scenarioD_recoveryIsShownButNotCountedAsIndependent() {
        var result = IndependentApplication.of(List.of(
                List.of(first("wrong"), retry("correct")), List.of(first("correct"))));
        assertThat(result.independentCorrect()).isEqualTo(1);
        assertThat(result.outcomes()).containsExactly(
                ProblemOutcome.CORRECT_AFTER_RETEACH, ProblemOutcome.FIRST_TRY_CORRECT);
    }

    @Test
    void scenarioE_pendingIsSeparatedFromWrong() {
        var result = IndependentApplication.of(List.of(List.of(first("correct")), List.of(first("pending_review"))));
        assertThat(result.independentCorrect()).isEqualTo(1);
        assertThat(result.total()).isEqualTo(2);
        assertThat(result.pendingReview()).isEqualTo(1);
        assertThat(result.outcomes()).doesNotContain(ProblemOutcome.WRONG);
    }

    @Test
    void anAnswerAfterTheSolutionIsNeverIndependent() {
        var outcome = IndependentApplication.outcome(List.of(
                first("wrong"), new AttemptFact(2, "correct", null, true)));
        assertThat(outcome).isEqualTo(ProblemOutcome.CORRECT_AFTER_SOLUTION);
    }

    @Test
    void gradingInProgressStaysUnfinished() {
        assertThat(IndependentApplication.outcome(List.of(first(null)))).isEqualTo(ProblemOutcome.UNFINISHED);
        assertThat(IndependentApplication.outcome(List.of())).isEqualTo(ProblemOutcome.UNFINISHED);
    }
}