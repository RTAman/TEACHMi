package com.teachmi.backend.learning.gemini;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import com.teachmi.backend.learning.GenerationRecovery;
import com.teachmi.backend.learning.LearningException;

/** One explicit owner-confirmed retry, inside the independent ledger transaction. Original receipts stay immutable. */
final class ApprovedGradingRecovery {
    record Claim(String key,String cached) {}
    private final JdbcTemplate jdbc;
    private final GeminiSettings settings;
    private final boolean enabled;
    ApprovedGradingRecovery(JdbcTemplate jdbc,GeminiSettings settings,boolean enabled) {
        this.jdbc=jdbc;this.settings=settings;this.enabled=enabled;
    }

    String cached(String original,String member,UUID session,UUID attempt) {
        var rows=jdbc.queryForList("""
                SELECT g.content_json FROM teachmi.learning_ai_recovery_authorization r
                JOIN teachmi.learning_ai_recovery_execution e ON e.authorization_id=r.id
                JOIN teachmi.learning_ai_generation g ON g.generation_key=e.replacement_generation_key
                JOIN teachmi.assessment_attempt a ON a.id=r.attempt_id AND a.request_hash=r.attempt_hash
                WHERE r.original_generation_key=? AND r.member_id=? AND r.session_id=? AND r.attempt_id=?
                AND a.member_id=r.member_id AND a.session_id=r.session_id
                AND g.member_id=r.member_id AND g.session_id=r.session_id AND g.state='succeeded'
                """,String.class,original,member,session,attempt);
        return rows.isEmpty()?null:rows.getFirst();
    }

    Claim claim(UUID authorization,String original,String member,UUID session,UUID attempt,int bytes,int tokens,Runnable quotas) {
        if(!enabled || attempt==null) throw denied();
        // Match the application's existing session -> quota lock order. No session lock crosses HTTP.
        var locked=jdbc.queryForList("SELECT id FROM teachmi.learning_session WHERE id=? AND member_id=? FOR UPDATE",session,member);
        if(locked.isEmpty()) throw denied();
        jdbc.queryForList("SELECT pg_advisory_xact_lock(hashtextextended('teachmi:gemini:quota',0))");
        var rows=jdbc.queryForList("""
                SELECT r.*,g.attempts,r.expires_at>now() AS authorization_valid FROM teachmi.learning_ai_recovery_authorization r
                JOIN teachmi.learning_ai_generation g ON g.generation_key=r.original_generation_key
                WHERE r.id=? AND r.original_generation_key=? AND r.member_id=? AND r.session_id=? AND r.attempt_id=?
                AND g.member_id=r.member_id AND g.session_id=r.session_id
                AND (g.state='uncertain' OR (g.state='running' AND g.lease_until<=now())) AND g.content_json IS NULL
                """,authorization,original,member,session,attempt);
        if(rows.isEmpty()) throw denied();
        var approval=rows.getFirst();
        var previous=jdbc.queryForList("""
                SELECT g.* FROM teachmi.learning_ai_recovery_execution e
                JOIN teachmi.learning_ai_generation g ON g.generation_key=e.replacement_generation_key
                WHERE e.original_generation_key=?
                """,original);
        if(!previous.isEmpty()) {
            var row=previous.getFirst();
            if("succeeded".equals(row.get("state"))) return new Claim((String)row.get("generation_key"),(String)row.get("content_json"));
            // Even a validation failure consumes this one user authorization. Never reserve twice.
            throw new LearningException(409,"AI_RECOVERY_CONSUMED","승인된 재채점은 이미 실행됐습니다. 저장 상태를 확인하세요.",null,
                    GenerationRecovery.review("LEDGER_CLAIM","RECOVERY_ALREADY_CONSUMED"));
        }
        if(!Boolean.TRUE.equals(approval.get("authorization_valid"))
                || !eligible(jdbc,approval)) throw denied();
        if(((Number)approval.get("attempts")).intValue()+1>settings.maxAttempts())
            throw new LearningException(429,"AI_ATTEMPT_LIMIT","기존 호출을 포함한 생성 시도 한도에 도달했습니다.");
        if(Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM teachmi.unresolved_ai_generation "
                +"WHERE member_id=? AND session_id=? AND generation_key<>?)",Boolean.class,member,session,original))) throw denied();
        quotas.run();
        String key=digest(original+"\nuser-confirmed-grading-recovery\n"+authorization);
        jdbc.update("""
                INSERT INTO teachmi.learning_ai_generation(generation_key,member_id,session_id,state,attempts,lease_until,retry_after,updated_at)
                VALUES (?,?,?,'running',1,now()+interval '120 seconds',now(),now())
                """,key,member,session);
        jdbc.update("INSERT INTO teachmi.learning_ai_recovery_execution(authorization_id,original_generation_key,replacement_generation_key) VALUES (?,?,?)",
                authorization,original,key);
        jdbc.update("INSERT INTO teachmi.learning_ai_call(id,generation_key,member_id,attempted_at,input_bytes,max_output_tokens) VALUES (?,?,?,now(),?,?)",
                UUID.randomUUID(),key,member,bytes,tokens);
        return new Claim(key,null);
    }

    static boolean eligible(JdbcTemplate jdbc,Map<String,Object> approval) {
        return Boolean.TRUE.equals(jdbc.queryForObject("""
                SELECT EXISTS(SELECT 1 FROM teachmi.assessment_attempt a
                JOIN teachmi.assessment_problem p ON p.id=a.problem_id AND p.session_id=a.session_id AND p.member_id=a.member_id
                JOIN teachmi.learning_session s ON s.id=a.session_id AND s.member_id=a.member_id
                WHERE a.id=? AND a.request_hash=? AND a.member_id=? AND a.session_id=? AND s.version=?
                AND a.judgement IS NULL AND NOT a.after_solution AND s.status='active' AND s.stage='problem'
                AND s.pending_turn_id IS NULL AND s.active_reteach_id IS NULL AND s.return_reteach_id IS NULL
                AND p.evidence_version=s.version AND p.confirmed_at IS NULL AND p.solution_viewed_at IS NULL
                AND NOT EXISTS(SELECT 1 FROM teachmi.assessment_attempt newer WHERE newer.problem_id=p.id AND newer.attempt_no>a.attempt_no))
                """,Boolean.class,approval.get("attempt_id"),approval.get("attempt_hash"),approval.get("member_id"),
                approval.get("session_id"),approval.get("session_version")));
    }

    static LearningException denied() {
        return new LearningException(409,"AI_RECOVERY_NOT_AUTHORIZED","재채점 안내 확인과 원래 저장된 동일 답안이 필요합니다.",null,
                GenerationRecovery.review("LEDGER_CLAIM","RECOVERY_NOT_AUTHORIZED"));
    }
    private static String digest(String input) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(input.getBytes(StandardCharsets.UTF_8))); }
        catch(java.security.NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }
}
