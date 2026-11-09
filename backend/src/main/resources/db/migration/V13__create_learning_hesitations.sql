-- 질문이 뜬 뒤 30초 이상 입력이 없었던 '망설임'을 질문당 한 번 기록한다.
-- 프론트엔드가 시간을 재서 보내며, 학습 세션의 version은 바꾸지 않는다.
-- final_explanation_id처럼 외래 키 없이 저장하고, 세션·질문 소속은 LearningHesitationService가 확인한다.
-- (외래 키를 걸면 학습 테이블을 TRUNCATE하는 기존 테스트가 모두 이 테이블을 알아야 한다.)
CREATE TABLE teachmi.learning_hesitation (
    id UUID PRIMARY KEY,
    session_id UUID NOT NULL,
    target_message_id UUID NOT NULL,
    reteach_id UUID,
    waited_seconds INTEGER NOT NULL CHECK (waited_seconds BETWEEN 30 AND 86400),
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    UNIQUE (session_id, target_message_id)
);
