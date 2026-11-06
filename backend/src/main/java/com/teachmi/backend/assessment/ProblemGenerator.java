package com.teachmi.backend.assessment;

import java.util.List;
import com.teachmi.backend.learning.SessionView;

/** 학습 세션에 낼 문항 초안을 만든다. 구현을 바꿔도 저장과 조회 코드는 그대로 쓴다. */
interface ProblemGenerator {
    List<Draft> generate(SessionView session);

    record Draft(String question, String changedConditions, String expectedAnswer, String solution,
                 String gradingCriteria) {}
}