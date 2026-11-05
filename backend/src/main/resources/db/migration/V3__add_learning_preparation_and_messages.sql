ALTER TABLE teachmi.learning_session
    ADD COLUMN summary_json TEXT,
    ADD COLUMN chat_stage VARCHAR(16)
        CHECK (chat_stage IN ('case', 'concept', 'reason', 'wrapup'));

CREATE TABLE teachmi.learning_message (
    id UUID PRIMARY KEY,
    session_id UUID NOT NULL REFERENCES teachmi.learning_session(id),
    sequence BIGINT NOT NULL CHECK (sequence > 0),
    role VARCHAR(16) NOT NULL,
    kind VARCHAR(32) NOT NULL,
    chat_stage VARCHAR(16) NOT NULL,
    text TEXT NOT NULL,
    source_chunk_ids TEXT NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    UNIQUE (session_id, sequence)
);
