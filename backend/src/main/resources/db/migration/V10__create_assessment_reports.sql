-- C가 학습 세션당 한 번 확정하는 성장 리포트. content는 확정 시점의 판정과 근거를 그대로 보관한다.
CREATE TABLE teachmi.assessment_report (
                                           id UUID PRIMARY KEY,
                                           session_id UUID NOT NULL UNIQUE,
                                           member_id VARCHAR(128) NOT NULL,
    -- 리포트 내용이 바뀐 횟수. 확인(confirmed_at)만으로는 올리지 않는다.
                                           revision BIGINT NOT NULL CHECK (revision >= 0),
    -- 리포트가 사용한 B 학습 근거의 version.
                                           evidence_version BIGINT NOT NULL CHECK (evidence_version >= 0),
                                           content TEXT NOT NULL,
                                           created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    -- 학생이 리포트를 확인한 시점. 학습 종료(completeLearning)의 기준이다.
                                           confirmed_at TIMESTAMP WITH TIME ZONE
);
CREATE INDEX assessment_report_member ON teachmi.assessment_report(member_id, created_at DESC);