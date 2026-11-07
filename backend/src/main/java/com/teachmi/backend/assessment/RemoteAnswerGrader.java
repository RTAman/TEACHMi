package com.teachmi.backend.assessment;

import java.util.List;
import java.util.UUID;
import com.teachmi.backend.learning.SessionView;

/**
 * DB 트랜잭션 밖에서 실행하는 채점기(Gemini). AnswerGrader(트랜잭션 안의 로컬 채점)와 분리한 이유는
 * 외부 AI 호출이 학습 세션 잠금을 오래 붙잡지 않게 하기 위해서다.
 */
interface RemoteAnswerGrader {
    AnswerGrader.Grade grade(Input input);

    /** attemptId는 저장된 답안의 ID다. 같은 답안을 다시 채점하면 같은 입력이 되어 저장된 AI 결과를 다시 쓴다. */
    record Input(String memberId, SessionView session, UUID attemptId, String question, String expectedAnswer,
                 String solution, String gradingCriteria, List<String> problemSources, String answer, String work) {}
}