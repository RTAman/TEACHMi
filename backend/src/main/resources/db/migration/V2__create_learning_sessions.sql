CREATE TABLE teachmi.learning_session (
    id UUID PRIMARY KEY,
    member_id VARCHAR(128) NOT NULL,
    material_id VARCHAR(128) NOT NULL,
    concept_id VARCHAR(128) NOT NULL,
    concept_snapshot TEXT NOT NULL,
    mode VARCHAR(16) CHECK (mode IN ('first', 'review')),
    stage VARCHAR(16) NOT NULL CHECK (stage IN ('mode', 'summary', 'chat', 'problem', 'report')),
    status VARCHAR(16) NOT NULL CHECK (status IN ('active', 'abandoned', 'completed')),
    version BIGINT NOT NULL DEFAULT 0,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL
);

CREATE UNIQUE INDEX learning_session_one_active_member
    ON teachmi.learning_session(member_id) WHERE status = 'active';
CREATE INDEX learning_session_member_created
    ON teachmi.learning_session(member_id, created_at DESC);

CREATE TABLE teachmi.learning_session_request (
    member_id VARCHAR(128) NOT NULL,
    request_key VARCHAR(128) NOT NULL,
    session_id UUID NOT NULL REFERENCES teachmi.learning_session(id),
    request_body TEXT NOT NULL,
    response_body TEXT NOT NULL,
    PRIMARY KEY (member_id, request_key)
);
