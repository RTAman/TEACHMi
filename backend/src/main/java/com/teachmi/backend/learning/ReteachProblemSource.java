package com.teachmi.backend.learning;

import java.util.UUID;

/**
 * C verifies ownership, session/problem relation, the latest graded wrong attempt, and eligibility.
 * Keep the independent first attempt immutable. Focus is conceptual guidance, never a solution.
 */
public interface ReteachProblemSource {
    Target loadWrongAttempt(String memberId, UUID sessionId, String problemId, String attemptId);

    record Target(String memberId, UUID sessionId, String problemId, String attemptId,
                  String judgement, String focus) {}
}
