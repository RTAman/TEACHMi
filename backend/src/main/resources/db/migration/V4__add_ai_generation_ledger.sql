CREATE TABLE teachmi.learning_ai_generation (
    generation_key VARCHAR(64) PRIMARY KEY,
    member_id VARCHAR(128) NOT NULL,
    session_id UUID NOT NULL,
    state VARCHAR(16) NOT NULL CHECK (state IN ('running','succeeded','failed','uncertain')),
    attempts INTEGER NOT NULL DEFAULT 0,
    lease_until TIMESTAMP WITH TIME ZONE NOT NULL,
    retry_after TIMESTAMP WITH TIME ZONE NOT NULL,
    content_json TEXT,
    last_error_code VARCHAR(96),
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL
);

CREATE TABLE teachmi.learning_ai_call (
    id UUID PRIMARY KEY,
    generation_key VARCHAR(64) NOT NULL REFERENCES teachmi.learning_ai_generation(generation_key),
    member_id VARCHAR(128) NOT NULL,
    attempted_at TIMESTAMP WITH TIME ZONE NOT NULL,
    input_bytes INTEGER NOT NULL,
    max_output_tokens INTEGER NOT NULL
);
CREATE INDEX learning_ai_call_time ON teachmi.learning_ai_call(attempted_at);
CREATE INDEX learning_ai_call_member_time ON teachmi.learning_ai_call(member_id, attempted_at);
