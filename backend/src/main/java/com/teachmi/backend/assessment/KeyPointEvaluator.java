package com.teachmi.backend.assessment;

import com.teachmi.backend.assessment.ReportViews.KeyPoints;
import com.teachmi.backend.learning.SessionView;

/** 첫 설명과 최종 설명에 들어간 핵심 요소를 판정한다. 리포트 저장 트랜잭션 밖에서 호출한다. */
interface KeyPointEvaluator {
    KeyPoints evaluate(Input input);

    record Input(String memberId, SessionView session, String initialAnswer, String finalExplanation) {}
}
