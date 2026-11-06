package com.teachmi.backend.assessment;

/** Synchronous local grading only. A future remote grader needs a persisted claim outside DB locks. */
public interface AnswerGrader {
    Grade grade(Input input);
    record Input(String question, String expectedAnswer, String gradingCriteria, String answer, String work) {}
    record Grade(String judgement, String reteachFocus) {}
}
