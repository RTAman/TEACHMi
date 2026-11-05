ALTER TABLE teachmi.learning_session
    ADD COLUMN active_reteach_id UUID,
    ADD COLUMN return_reteach_id UUID;
ALTER TABLE teachmi.learning_message ADD COLUMN reteach_id UUID;
ALTER TABLE teachmi.learning_support_event ADD COLUMN reteach_id UUID;
ALTER TABLE teachmi.learning_turn ADD COLUMN reteach_id UUID;

CREATE TABLE teachmi.learning_reteach (
    id UUID PRIMARY KEY,
    session_id UUID NOT NULL REFERENCES teachmi.learning_session(id),
    member_id VARCHAR(128) NOT NULL,
    problem_id VARCHAR(128) NOT NULL,
    attempt_id VARCHAR(128) NOT NULL,
    target_json TEXT NOT NULL,
    focus TEXT NOT NULL,
    state VARCHAR(16) NOT NULL CHECK (state IN ('generating','failed','chat','returned')),
    request_key VARCHAR(128) NOT NULL,
    request_body TEXT NOT NULL,
    expected_version BIGINT NOT NULL,
    input_version BIGINT NOT NULL,
    claim_token UUID NOT NULL,
    lease_until TIMESTAMP WITH TIME ZONE NOT NULL,
    question_message_id UUID,
    initial_answer_id UUID,
    final_explanation_id UUID,
    error_code VARCHAR(96),
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    completed_at TIMESTAMP WITH TIME ZONE,
    UNIQUE(member_id,request_key),
    UNIQUE(session_id,problem_id,attempt_id)
);
CREATE INDEX learning_reteach_session ON teachmi.learning_reteach(session_id,created_at);
