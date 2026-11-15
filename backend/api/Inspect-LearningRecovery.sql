-- Operator-only, read-only inspection. Supply an authorized session id with psql -v session_id=UUID.
-- Never paste these private identifiers into a public issue. No source/answer/AI JSON is selected.
\set ON_ERROR_STOP on
\if :{?session_id}
\else
\echo 'Set session_id explicitly. No database query was performed.'
\quit 2
\endif

BEGIN READ ONLY;
SET LOCAL statement_timeout = '5s';
SET LOCAL lock_timeout = '1s';

SELECT current_database() AS inspected_database;
SELECT generation_key, session_id, state, attempts, last_error_code, lease_until, updated_at,
       content_json IS NOT NULL AS has_saved_result
FROM teachmi.learning_ai_generation
WHERE session_id = :'session_id'::uuid
ORDER BY updated_at;

SELECT c.generation_key, c.attempted_at, c.input_bytes, c.max_output_tokens
FROM teachmi.learning_ai_call c
JOIN teachmi.learning_ai_generation g USING (generation_key)
WHERE g.session_id = :'session_id'::uuid
ORDER BY c.attempted_at;

COMMIT;
