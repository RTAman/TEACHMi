package com.teachmi.backend.learning.gemini;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.core.env.Environment;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import com.teachmi.backend.learning.CurrentMember;
import com.teachmi.backend.learning.LearningException;
import com.teachmi.backend.learning.LearningSessionService;

/** Read-only owner restoration plus explicit, CSRF-protected consent. Neither endpoint calls AI. */
@RestController
@RequestMapping("/api/v1/sessions/{sessionId}/grading-recovery")
class GradingRecoveryController {
    record Failure(String code,String message,com.teachmi.backend.learning.GenerationRecovery recovery) {}
    record SavedSubmission(String answer,String work,String idempotencyKey) {}
    record Problem(UUID problemId,int sequence,String question) {}
    record View(String state,boolean retryAllowed,UUID authorizationId,String expiresAt,UUID attemptId,
                UUID problemId,SavedSubmission savedSubmission,List<Problem> problems) {}
    record Consent(UUID attemptId,boolean acceptDuplicateCall) {}
    private final JdbcTemplate jdbc;
    private final LearningSessionService sessions;
    private final ObjectProvider<CurrentMember> members;
    private final Environment environment;
    GradingRecoveryController(JdbcTemplate jdbc,LearningSessionService sessions,ObjectProvider<CurrentMember> members,Environment environment) {
        this.jdbc=jdbc;this.sessions=sessions;this.members=members;this.environment=environment;
    }

    @GetMapping
    @Transactional(readOnly=true)
    ResponseEntity<View> get(HttpServletRequest http,@PathVariable UUID sessionId) {
        String member=owner(http,sessionId);
        var pending=jdbc.queryForList("""
                SELECT a.* FROM teachmi.assessment_attempt a WHERE a.member_id=? AND a.session_id=?
                AND (a.judgement IS NULL OR EXISTS (
                    SELECT 1 FROM teachmi.learning_ai_recovery_authorization r
                    JOIN teachmi.learning_ai_recovery_execution e ON e.authorization_id=r.id WHERE r.attempt_id=a.id))
                ORDER BY (a.judgement IS NULL) DESC,a.created_at DESC LIMIT 1
                """,member,sessionId);
        if(pending.isEmpty()) return response(new View("none",false,null,null,null,null,null,List.of()));
        var attempt=pending.getFirst();
        UUID attemptId=(UUID)attempt.get("id");
        var receipts=jdbc.queryForList("""
                SELECT r.*,e.replacement_generation_key,g.state AS child_state,g.lease_until>now() AS leased,
                       original.attempts AS original_attempts,(r.expires_at>now()) AS valid
                FROM teachmi.learning_ai_recovery_authorization r
                LEFT JOIN teachmi.learning_ai_recovery_execution e ON e.authorization_id=r.id
                LEFT JOIN teachmi.learning_ai_generation g ON g.generation_key=e.replacement_generation_key
                JOIN teachmi.learning_ai_generation original ON original.generation_key=r.original_generation_key
                WHERE r.member_id=? AND r.session_id=? AND r.attempt_id=? ORDER BY r.approved_at DESC LIMIT 1
                """,member,sessionId,attemptId);
        Map<String,Object> receipt=receipts.isEmpty()?Map.of():receipts.getFirst();
        String state=attempt.get("judgement")!=null?"completed":"review_required";
        boolean ready=false;
        UUID authorization=null;
        if(attempt.get("judgement")==null) {
            if(receipt.get("replacement_generation_key")!=null) {
                state="succeeded".equals(receipt.get("child_state"))?"result_available"
                        :"running".equals(receipt.get("child_state")) && Boolean.TRUE.equals(receipt.get("leased"))?"running":"exhausted";
            } else {
                var candidate=candidate(member,sessionId,attempt);
                if(candidate!=null && enabled() && ApprovedGradingRecovery.eligible(jdbc,candidate)) {
                    state="retry_available";ready=true;
                    if(Boolean.TRUE.equals(receipt.get("valid")) && receipt.get("original_generation_key").equals(candidate.get("original_generation_key")))
                        authorization=(UUID)receipt.get("id");
                }
            }
        }
        var problems=jdbc.query("SELECT id,sequence,question FROM teachmi.assessment_problem WHERE member_id=? AND session_id=? ORDER BY sequence",
                (row,n) -> new Problem(row.getObject(1,UUID.class),row.getInt(2),row.getString(3)),member,sessionId);
        return response(new View(state,ready,authorization,
                authorization==null?null:((java.sql.Timestamp)receipt.get("expires_at")).toInstant().toString(),
                attemptId,(UUID)attempt.get("problem_id"),new SavedSubmission((String)attempt.get("answer"),
                (String)attempt.get("work"),(String)attempt.get("request_key")),problems));
    }

    @PostMapping("/consent")
    @Transactional(timeout=5)
    ResponseEntity<Map<String,Object>> consent(HttpServletRequest http,@PathVariable UUID sessionId,@RequestBody Consent request) {
        String member=owner(http,sessionId);
        if(!enabled() || request==null || request.attemptId()==null || !request.acceptDuplicateCall()) throw ApprovedGradingRecovery.denied();
        jdbc.queryForList("SELECT id FROM teachmi.learning_session WHERE id=? AND member_id=? FOR UPDATE",sessionId,member);
        jdbc.queryForList("SELECT pg_advisory_xact_lock(hashtextextended('teachmi:gemini:quota',0))");
        var attempts=jdbc.queryForList("SELECT * FROM teachmi.assessment_attempt WHERE id=? AND member_id=? AND session_id=? AND judgement IS NULL",
                request.attemptId(),member,sessionId);
        if(attempts.isEmpty()) throw ApprovedGradingRecovery.denied();
        var candidate=candidate(member,sessionId,attempts.getFirst());
        if(candidate==null || !ApprovedGradingRecovery.eligible(jdbc,candidate)) throw ApprovedGradingRecovery.denied();
        var active=jdbc.queryForList("SELECT id FROM teachmi.learning_ai_recovery_authorization WHERE original_generation_key=? "
                +"AND attempt_id=? AND member_id=? AND expires_at>now() ORDER BY approved_at DESC LIMIT 1",
                candidate.get("original_generation_key"),request.attemptId(),member);
        UUID id=active.isEmpty()?UUID.randomUUID():(UUID)active.getFirst().get("id");
        if(active.isEmpty()) jdbc.update("""
                INSERT INTO teachmi.learning_ai_recovery_authorization
                (id,original_generation_key,session_id,member_id,attempt_id,attempt_hash,session_version,
                 requested_by,policy_version,duplicate_cost_accepted,expires_at)
                VALUES (?,?,?,?,?,?,?,?,'user-confirmed-v1',true,now()+interval '15 minutes')
                """,id,candidate.get("original_generation_key"),sessionId,member,request.attemptId(),
                candidate.get("attempt_hash"),candidate.get("session_version"),member);
        // This consent receipt reserves no provider quota and does not dispatch the original request.
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(Map.of("authorizationId",id));
    }

    private Map<String,Object> candidate(String member,UUID session,Map<String,Object> attempt) {
        var rows=jdbc.queryForList("SELECT g.generation_key FROM teachmi.unresolved_ai_generation g WHERE g.member_id=? AND g.session_id=?",
                member,session);
        // Old receipts do not identify an attempt. Require a single unresolved receipt here; the common
        // pipeline must STILL match its exact model/prompt/body hash before consuming this authorization.
        if(rows.size()!=1) return null;
        String key=(String)rows.getFirst().get("generation_key");
        var viable=jdbc.queryForList("""
                SELECT g.generation_key FROM teachmi.learning_ai_generation g WHERE g.generation_key=?
                AND (g.state='uncertain' OR (g.state='running' AND g.lease_until<=now())) AND g.content_json IS NULL
                AND g.attempts<? AND NOT EXISTS(SELECT 1 FROM teachmi.learning_ai_recovery_execution e WHERE e.original_generation_key=g.generation_key)
                """,key,environment.getProperty("teachmi.gemini.max-attempts",Integer.class,2));
        if(viable.isEmpty()) return null;
        long version=jdbc.queryForObject("SELECT version FROM teachmi.learning_session WHERE id=? AND member_id=?",Long.class,session,member);
        return Map.of("original_generation_key",key,"member_id",member,"session_id",session,"attempt_id",attempt.get("id"),
                "attempt_hash",attempt.get("request_hash"),"session_version",version);
    }
    private boolean enabled() {
        return environment.getProperty("teachmi.gemini.enabled",Boolean.class,false)
                && environment.getProperty("teachmi.gemini.user-grading-recovery-enabled",Boolean.class,true);
    }
    private String owner(HttpServletRequest http,UUID sessionId) {
        var adapter=members.getIfAvailable();
        String member=adapter==null?(http.getUserPrincipal()==null?null:http.getUserPrincipal().getName()):adapter.requireId(http);
        if(member==null || member.isBlank()) throw new LearningException(401,"UNAUTHENTICATED","로그인이 필요합니다.");
        sessions.get(member,sessionId);
        return member;
    }
    private static ResponseEntity<View> response(View view) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(view);
    }
    @ExceptionHandler(LearningException.class)
    ResponseEntity<Failure> failure(LearningException error) {
        return ResponseEntity.status(error.status()).cacheControl(CacheControl.noStore())
                .body(new Failure(error.code(),error.getMessage(),error.recovery()));
    }
}
