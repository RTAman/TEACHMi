package com.teachmi.backend.assessment;

import java.util.List;
import com.teachmi.backend.learning.SessionView;
import org.springframework.stereotype.Component;

/** Gemini 문제 생성을 연결하기 전까지 쓰는 임시 감가상각 문항. 다른 개념의 시연에는 쓸 수 없다. */
@Component
class FixedDepreciationProblems implements ProblemGenerator {
    @Override
    public List<Draft> generate(SessionView session) {
        return List.of(
                new Draft("취득원가 600만 원, 내용연수 3년, 잔존가치 0원인 기계의 정액법 기준 연간 감가상각비는 얼마인가요?",
                        "대화의 컴퓨터 사례를 기계로 바꾸고 취득원가를 600만 원으로 변경",
                        "200만 원", "(600만 원 - 0원) ÷ 3년 = 연 200만 원",
                        "금액이 200만 원이고 원가를 내용연수에 나누는 이유를 설명해야 한다."),
                new Draft("취득원가 1,000만 원, 내용연수 5년, 잔존가치 100만 원인 차량의 정액법 기준 연간 감가상각비는 얼마인가요?",
                        "잔존가치가 있는 조건을 추가",
                        "180만 원", "(1,000만 원 - 100만 원) ÷ 5년 = 연 180만 원",
                        "금액이 180만 원이고 잔존가치를 뺀 금액을 배분하는 이유를 설명해야 한다."));
    }
}