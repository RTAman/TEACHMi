package com.teachmi.backend.assessment;

import java.util.List;
import com.teachmi.backend.learning.SessionView;

/** 학습 세션에 낼 문항 초안을 만든다. 구현을 바꿔도 저장과 조회 코드는 그대로 쓴다. */
interface ProblemGenerator {
    List<Draft> generate(SessionView session);

    /**
     * 회원 ID가 필요한 구현(Gemini)은 이 메서드를 재정의한다. AssessmentProblemService는 항상 이 메서드를 호출한다.
     * SessionView에는 회원 ID가 없어서, 기존 한 인자 구현과 테스트를 그대로 두려고 기본 메서드로 추가했다.
     */
    default List<Draft> generate(String memberId, SessionView session) {
        return generate(session);
    }

    /** sourceChunkIds는 문항이 근거로 삼은 강의 원문 조각이다. 원문에 근거하지 않은 임시 문항은 비어 있다. */
    record Draft(String question, String changedConditions, String expectedAnswer, String solution,
                 String gradingCriteria, List<String> sourceChunkIds) {
        public Draft {
            sourceChunkIds = sourceChunkIds == null ? List.of() : List.copyOf(sourceChunkIds);
        }

        Draft(String question, String changedConditions, String expectedAnswer, String solution,
              String gradingCriteria) {
            this(question, changedConditions, expectedAnswer, solution, gradingCriteria, List.of());
        }

        /** assessment_problem.source_chunk_ids에 저장할 JSON 배열. */
        String sourcesJson() {
            var quoted = sourceChunkIds.stream()
                    .map(id -> "\"" + id.replace("\\", "\\\\").replace("\"", "\\\"") + "\"").toList();
            return "[" + String.join(",", quoted) + "]";
        }
    }
}