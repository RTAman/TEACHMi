ALTER TABLE teachmi.learning_session
    ADD COLUMN pending_turn_id UUID,
    ADD COLUMN initial_answer_id UUID,
    ADD COLUMN final_explanation_id UUID;
ALTER TABLE teachmi.learning_message ADD COLUMN support_event_id UUID;

CREATE TABLE teachmi.learning_support_event (
    id UUID PRIMARY KEY,
    session_id UUID NOT NULL REFERENCES teachmi.learning_session(id),
    kind VARCHAR(16) NOT NULL CHECK (kind IN ('hint','example','dont_know')),
    target_message_id UUID NOT NULL REFERENCES teachmi.learning_message(id),
    request_message_id UUID NOT NULL REFERENCES teachmi.learning_message(id),
    response_message_id UUID REFERENCES teachmi.learning_message(id),
    created_at TIMESTAMP WITH TIME ZONE NOT NULL
);
CREATE INDEX learning_support_event_session ON teachmi.learning_support_event(session_id,created_at);

CREATE TABLE teachmi.learning_turn (
    id UUID PRIMARY KEY,
    session_id UUID NOT NULL REFERENCES teachmi.learning_session(id),
    member_id VARCHAR(128) NOT NULL,
    request_key VARCHAR(128) NOT NULL,
    request_body TEXT NOT NULL,
    kind VARCHAR(16) NOT NULL CHECK (kind IN ('answer','hint','example','dont_know')),
    text TEXT NOT NULL,
    chat_stage VARCHAR(16) NOT NULL CHECK (chat_stage IN ('case','concept','reason','wrapup')),
    user_message_id UUID NOT NULL REFERENCES teachmi.learning_message(id),
    target_message_id UUID NOT NULL REFERENCES teachmi.learning_message(id),
    input_version BIGINT NOT NULL,
    expected_version BIGINT NOT NULL,
    claim_token UUID NOT NULL,
    lease_until TIMESTAMP WITH TIME ZONE NOT NULL,
    state VARCHAR(16) NOT NULL CHECK (state IN ('generating','failed','succeeded')),
    error_code VARCHAR(96),
    UNIQUE(member_id,request_key)
);
CREATE INDEX learning_turn_session ON teachmi.learning_turn(session_id);
