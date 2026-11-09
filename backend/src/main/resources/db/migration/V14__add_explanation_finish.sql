-- 처음 학습의 wrapup 답변을 AI가 통과시키면 바로 problem으로 넘기지 않고 이 메시지 ID를 저장한다.
-- 학생이 '설명 마치기'를 누르면 최종 설명으로 확정하고 problem 단계로 바꾼다.
-- final_explanation_id와 같이 외래 키 없이 저장한다.
ALTER TABLE teachmi.learning_session ADD COLUMN explanation_accepted_id UUID;
