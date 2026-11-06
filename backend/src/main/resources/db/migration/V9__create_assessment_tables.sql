-- C가 독립 문항과 답안 시도를 저장한다. 정답과 해설은 제출 전 응답에 포함하지 않는다.
-- B의 세션·재설명 ID는 B 서비스로 확인한다. B 테이블 초기화에 영향을 주지 않도록 FK를 두지 않는다.
CREATE TABLE teachmi.assessment_problem (
                                            id UUID PRIMARY KEY,
                                            session_id UUID NOT NULL,
                                            member_id VARCHAR(128) NOT NULL,
                                            sequence INTEGER NOT NULL CHECK (sequence IN (1, 2)),
                                            question TEXT NOT NULL,
                                            changed_conditions TEXT NOT NULL,
                                            source_chunk_ids TEXT NOT NULL,
                                            expected_answer TEXT NOT NULL,
                                            solution TEXT NOT NULL,
                                            grading_criteria TEXT NOT NULL,
                                            evidence_version BIGINT NOT NULL CHECK (evidence_version >= 0),
                                            solution_viewed_at TIMESTAMP WITH TIME ZONE,
                                            confirmed_at TIMESTAMP WITH TIME ZONE,
                                            created_at TIMESTAMP WITH TIME ZONE NOT NULL,
                                            UNIQUE (session_id, sequence),
                                            UNIQUE (id, session_id, member_id)
);
CREATE INDEX assessment_problem_member ON teachmi.assessment_problem(member_id, created_at DESC);

CREATE TABLE teachmi.assessment_attempt (
                                            id UUID PRIMARY KEY,
                                            problem_id UUID NOT NULL,
                                            session_id UUID NOT NULL,
                                            member_id VARCHAR(128) NOT NULL,
                                            attempt_no INTEGER NOT NULL CHECK (attempt_no >= 1),
                                            answer TEXT NOT NULL,
                                            work TEXT NOT NULL,
    -- NULL은 답안이 저장됐고 아직 채점 전이라는 뜻이다. 시도를 늘리지 않고 채점만 재시도할 수 있다.
                                            judgement VARCHAR(16) CHECK (judgement IN ('correct', 'wrong', 'pending_review')),
                                            reteach_focus TEXT CHECK (char_length(reteach_focus) BETWEEN 1 AND 2000),
                                            reteach_id UUID,
                                            after_solution BOOLEAN NOT NULL DEFAULT FALSE,
                                            request_key VARCHAR(128) NOT NULL,
                                            request_hash VARCHAR(64) NOT NULL,
                                            created_at TIMESTAMP WITH TIME ZONE NOT NULL,
                                            graded_at TIMESTAMP WITH TIME ZONE,
                                            FOREIGN KEY (problem_id, session_id, member_id)
                                                REFERENCES teachmi.assessment_problem(id, session_id, member_id),
                                            UNIQUE (problem_id, attempt_no),
                                            UNIQUE (member_id, request_key),
                                            CHECK ((judgement IS NULL) = (graded_at IS NULL))
);
CREATE INDEX assessment_attempt_session ON teachmi.assessment_attempt(session_id, created_at);