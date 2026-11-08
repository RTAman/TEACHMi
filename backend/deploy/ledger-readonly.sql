-- psql variables member/session are validated UUIDs by ops.py. Never select source text, answers or content_json.
BEGIN READ ONLY;
SET LOCAL statement_timeout = '5s';
SELECT g.generation_key,
       g.state,
       g.attempts,
       CASE WHEN g.last_error_code ~ '^[A-Z0-9_]{1,80}$' THEN g.last_error_code ELSE 'UNCLASSIFIED' END AS diagnostic,
       g.lease_until,
       g.retry_after,
       g.updated_at,
       count(c.id) AS recorded_dispatches,
       max(c.attempted_at) AS last_dispatch_at,
       max(c.max_output_tokens) AS output_cap
FROM teachmi.learning_ai_generation g
LEFT JOIN teachmi.learning_ai_call c ON c.generation_key = g.generation_key
WHERE g.member_id = :'member' AND g.session_id = :'session'::uuid
GROUP BY g.generation_key
ORDER BY g.updated_at;
COMMIT;
