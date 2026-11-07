-- 리포트의 보완 항목에서 시작한 재학습과 원본 학습의 연결. 새 학습 세션 하나에 한 줄이다.
CREATE TABLE teachmi.assessment_relearn (
                                            session_id UUID PRIMARY KEY,
                                            origin_session_id UUID NOT NULL,
                                            report_id UUID NOT NULL REFERENCES teachmi.assessment_report(id),
                                            member_id VARCHAR(128) NOT NULL,
    -- 보완 항목을 고르지 않고 다시 학습하면 둘 다 NULL이다.
                                            problem_id UUID,
                                            focus TEXT CHECK (char_length(focus) BETWEEN 1 AND 2000),
                                            created_at TIMESTAMP WITH TIME ZONE NOT NULL,
                                            CHECK ((problem_id IS NULL) = (focus IS NULL))
);
CREATE INDEX assessment_relearn_origin ON teachmi.assessment_relearn(origin_session_id, created_at);