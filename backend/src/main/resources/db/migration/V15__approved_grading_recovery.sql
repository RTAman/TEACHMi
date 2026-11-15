-- Append-only recovery receipts. Original generations, reservations and answers are never reset.
-- Deliberately no FK to legacy tables: the ledger uses an independent connection/transaction.
CREATE TABLE teachmi.learning_ai_recovery_authorization (
    id UUID PRIMARY KEY,
    original_generation_key VARCHAR(64) NOT NULL CHECK (original_generation_key ~ '^[0-9a-f]{64}$'),
    session_id UUID NOT NULL,
    member_id VARCHAR(128) NOT NULL,
    attempt_id UUID NOT NULL,
    attempt_hash VARCHAR(64) NOT NULL,
    session_version BIGINT NOT NULL,
    requested_by VARCHAR(128) NOT NULL CHECK (requested_by=member_id),
    policy_version VARCHAR(32) NOT NULL,
    duplicate_cost_accepted BOOLEAN NOT NULL CHECK (duplicate_cost_accepted),
    approved_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    expires_at TIMESTAMPTZ NOT NULL
);
CREATE INDEX learning_ai_recovery_scope ON teachmi.learning_ai_recovery_authorization(member_id,session_id,attempt_id);
CREATE TABLE teachmi.learning_ai_recovery_execution (
    authorization_id UUID PRIMARY KEY REFERENCES teachmi.learning_ai_recovery_authorization(id),
    original_generation_key VARCHAR(64) NOT NULL UNIQUE,
    replacement_generation_key VARCHAR(64) NOT NULL UNIQUE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- A successful provider cache alone is insufficient: C must also commit the verdict.
CREATE VIEW teachmi.unresolved_ai_generation WITH (security_invoker=true) AS
SELECT g.* FROM teachmi.learning_ai_generation g
WHERE g.state IN ('uncertain','running') AND NOT EXISTS (
    SELECT 1 FROM teachmi.learning_ai_recovery_execution e
    JOIN teachmi.learning_ai_recovery_authorization r ON r.id=e.authorization_id
    JOIN teachmi.learning_ai_generation child ON child.generation_key=e.replacement_generation_key
        AND child.member_id=r.member_id AND child.session_id=r.session_id AND child.state='succeeded'
    JOIN teachmi.assessment_attempt a ON a.id=r.attempt_id AND a.member_id=r.member_id AND a.session_id=r.session_id
        AND a.request_hash=r.attempt_hash AND a.judgement IS NOT NULL AND a.graded_at IS NOT NULL
    WHERE e.original_generation_key=g.generation_key AND g.member_id=r.member_id AND g.session_id=r.session_id
);

REVOKE ALL ON teachmi.learning_ai_recovery_authorization,teachmi.learning_ai_recovery_execution,teachmi.unresolved_ai_generation FROM PUBLIC;
