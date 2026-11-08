package com.teachmi.backend.learning.gemini;

import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.boot.jdbc.autoconfigure.DataSourceProperties;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import com.zaxxer.hikari.HikariDataSource;
import com.teachmi.backend.learning.LearningContentGenerator.Context;
import com.teachmi.backend.learning.LearningException;
import com.teachmi.backend.learning.GenerationRecovery;

/**
 * These transactions survive a surrounding session transaction rolling back.
 * No FK to a session row is taken here, avoiding locks held by the caller.
 */
public class GeminiLedger implements AutoCloseable {
    private final JdbcTemplate jdbc;
    private final GeminiSettings settings;
    private final HikariDataSource pool;
    private final TransactionTemplate transaction;

    GeminiLedger(DataSourceProperties database, GeminiSettings settings) {
        this.pool = database.initializeDataSourceBuilder().type(HikariDataSource.class).build();
        pool.setPoolName("GeminiLedger");
        pool.setMaximumPoolSize(2);
        pool.setMinimumIdle(0);
        pool.setConnectionTimeout(3000);
        this.jdbc = new JdbcTemplate(pool);
        this.transaction = new TransactionTemplate(new DataSourceTransactionManager(pool));
        transaction.setTimeout(5);
        this.settings = settings;
    }

    public String claim(String key, Context context, int inputBytes) {
        return claim(key, context.memberId(), context.sessionId(), inputBytes);
    }

    String claim(String key, String memberId, UUID sessionId, int inputBytes) {
        return claim(key,memberId,sessionId,inputBytes,settings.maxOutputTokens());
    }

    String claim(String key,String memberId,UUID sessionId,int inputBytes,int outputTokens) {
        if(outputTokens<256 || outputTokens>settings.maxOutputTokens()) throw new IllegalArgumentException("Invalid output reservation");
        return transaction.execute(status -> claimInside(key, memberId, sessionId, inputBytes,outputTokens));
    }

    private String claimInside(String key, String memberId, UUID sessionId, int inputBytes,int outputTokens) {
        // Serialize project-wide quota reservations across all backend instances sharing this DB.
        jdbc.queryForList("SELECT pg_advisory_xact_lock(hashtextextended('teachmi:gemini:quota',0))");
        var rows = jdbc.queryForList("SELECT *, lease_until > now() AS leased, retry_after > now() AS cooling "
                + "FROM teachmi.learning_ai_generation WHERE generation_key=?", key);
        if (!rows.isEmpty()) {
            var row = rows.getFirst();
            String state = (String) row.get("state");
            if (state.equals("succeeded")) return (String) row.get("content_json");
            if (state.equals("uncertain") || (state.equals("running") && !(Boolean) row.get("leased"))) {
                // Expired leases are deliberately never automatically reclaimed.
                throw GeminiDiagnostics.uncertain("LEDGER_CLAIM",state.equals("uncertain")?(String)row.get("last_error_code"):"GENERATION_LEASE_EXPIRED",
                        "호출 결과 확인이 필요합니다. 자동 재호출을 중단했습니다.");
            }
            if (state.equals("running")) {
                throw new LearningException(503, "AI_GENERATION_IN_PROGRESS", "콘텐츠 생성이 진행 중입니다.",null,
                        GenerationRecovery.waitForResult("LEDGER_CLAIM","AI_GENERATION_IN_PROGRESS"));
            }
            if ((Boolean) row.get("cooling")) {
                throw new LearningException(429, "AI_RETRY_LATER", "잠시 후 같은 요청을 다시 시도하세요.");
            }
            if (((Number) row.get("attempts")).intValue() >= settings.maxAttempts()) {
                throw new LearningException(429, "AI_ATTEMPT_LIMIT", "이 콘텐츠의 생성 시도 한도에 도달했습니다.");
            }
        }
        // Prompt/model/output-limit changes create new hashes. They must not bypass an unresolved call.
        // Read only: historical uncertain and expired running receipts are never rewritten/reclaimed here.
        var unresolved=jdbc.queryForList("SELECT state,last_error_code,lease_until>now() AS leased FROM teachmi.learning_ai_generation "
                +"WHERE member_id=? AND session_id=? AND state IN ('uncertain','running') "
                +"ORDER BY CASE WHEN state='uncertain' THEN 0 ELSE 1 END,updated_at LIMIT 1",memberId,sessionId);
        if(!unresolved.isEmpty()) {
            var old=unresolved.getFirst();
            if("running".equals(old.get("state")) && Boolean.TRUE.equals(old.get("leased"))) {
                throw new LearningException(503,"AI_GENERATION_IN_PROGRESS","이 학습의 생성 결과를 기다리고 있습니다.",null,
                        GenerationRecovery.waitForResult("LEDGER_CLAIM","AI_GENERATION_IN_PROGRESS"));
            }
            throw GeminiDiagnostics.uncertain("LEDGER_CLAIM","uncertain".equals(old.get("state"))?(String)old.get("last_error_code"):"GENERATION_LEASE_EXPIRED",
                    "이 학습에 확인되지 않은 호출이 있습니다. 설정을 바꿔 재호출하지 말고 결과를 먼저 확인하세요.");
        }
        var counts = jdbc.queryForMap("""
                SELECT count(*) AS day_count,
                       count(*) FILTER (WHERE member_id=?) AS member_count
                FROM teachmi.learning_ai_call WHERE attempted_at >= date_trunc('day',now() AT TIME ZONE 'UTC') AT TIME ZONE 'UTC'
                """, memberId);
        // Include the previous day's last minute at UTC midnight.
        long minute = jdbc.queryForObject(
                "SELECT count(*) FROM teachmi.learning_ai_call WHERE attempted_at > now()-interval '1 minute'", Long.class);
        if (minute >= settings.requestsPerMinute()
                || ((Number) counts.get("day_count")).longValue() >= settings.requestsPerDay()
                || ((Number) counts.get("member_count")).longValue() >= settings.memberRequestsPerDay()) {
            throw new LearningException(429, "AI_USAGE_LIMIT", "학습 콘텐츠 요청 한도에 도달했습니다.");
        }
        jdbc.update("""
                INSERT INTO teachmi.learning_ai_generation
                    (generation_key,member_id,session_id,state,attempts,lease_until,retry_after,updated_at)
                VALUES (?,?,?,'running',1,now()+interval '120 seconds',now(),now())
                ON CONFLICT (generation_key) DO UPDATE SET state='running',
                    attempts=teachmi.learning_ai_generation.attempts+1,
                    lease_until=now()+interval '120 seconds',updated_at=now()
                """, key, memberId, sessionId);
        jdbc.update("""
                INSERT INTO teachmi.learning_ai_call
                    (id,generation_key,member_id,attempted_at,input_bytes,max_output_tokens)
                VALUES (?,?,?,now(),?,?)
                """, UUID.randomUUID(), key, memberId, inputBytes, outputTokens);
        return null;
    }

    public void succeed(String key, String contentJson) {
        transaction.executeWithoutResult(status -> jdbc.update(
                "UPDATE teachmi.learning_ai_generation SET state='succeeded',content_json=?,updated_at=now() "
                        + "WHERE generation_key=? AND state='running'", contentJson, key));
    }

    /** Historical success lookup never reserves quota, changes a state, or reopens an uncertain row. */
    String succeeded(String key,String member,UUID session) {
        var values=jdbc.queryForList("SELECT content_json FROM teachmi.learning_ai_generation "
                +"WHERE generation_key=? AND member_id=? AND session_id=? AND state='succeeded'",String.class,key,member,session);
        return values.isEmpty()?null:values.getFirst();
    }

    /** Scoped read-only lookup; historical cache discovery must never reserve or rewrite a receipt. */
    java.util.Map<String,String> succeededAmong(java.util.List<String> keys,String member,UUID session) {
        if(keys.isEmpty()) return java.util.Map.of();
        var parameters=new java.util.ArrayList<Object>();parameters.add(member);parameters.add(session);parameters.addAll(keys);
        var placeholders=String.join(",",java.util.Collections.nCopies(keys.size(),"?"));
        var found=new java.util.HashMap<String,String>();
        jdbc.query("SELECT generation_key,content_json FROM teachmi.learning_ai_generation WHERE member_id=? AND session_id=? "
                        +"AND state='succeeded' AND generation_key IN ("+placeholders+")",
                (org.springframework.jdbc.core.RowCallbackHandler)row -> found.put(row.getString(1),row.getString(2)),parameters.toArray());
        return found;
    }

    /** Only the exact failed request may exhaust its attempts; no session-wide inference from old hashes. */
    boolean failedAtLimit(String key) {
        return Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM teachmi.learning_ai_generation "
                +"WHERE generation_key=? AND state='failed' AND attempts>=?)",Boolean.class,key,settings.maxAttempts()));
    }

    public void fail(String key, boolean uncertain, int retrySeconds, String reason) {
        transaction.executeWithoutResult(status -> jdbc.update(
                "UPDATE teachmi.learning_ai_generation SET state=?,retry_after=now()+make_interval(secs=>?),"
                        + "last_error_code=?,updated_at=now() WHERE generation_key=? AND state='running'",
                uncertain ? "uncertain" : "failed", retrySeconds, reason, key));
    }

    @Override public void close() { pool.close(); }
}
