package com.teachmi.backend.learning;

public interface LearningReteachGenerator {
    LearningContentGenerator.Question opening(Context context);

    /** C's private answer, solution, and assessment prompt are intentionally absent. */
    record Context(LearningContentGenerator.Context learning, ReteachFocus reteach) {}
}
