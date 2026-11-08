package com.teachmi.backend.learning;

import java.time.Instant;
import java.util.UUID;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

@Service
public class LearningReteachTransactions {
    private final LearningSessionService access;
    private final LearningSessionRepository sessions;
    private final LearningMessageRepository messages;
    private final ObjectProvider<ReteachProblemSource> problems;
    private final JdbcTemplate jdbc;
    private final ObjectMapper json;

    LearningReteachTransactions(LearningSessionService access, LearningSessionRepository sessions,
            LearningMessageRepository messages, ObjectProvider<ReteachProblemSource> problems,
            JdbcTemplate jdbc, ObjectMapper json) {
        this.access = access; this.sessions = sessions; this.messages = messages;
        this.problems = problems; this.jdbc = jdbc; this.json = json;
    }

    record Job(UUID id, UUID sessionId, String memberId, String key, String body, String targetJson,
               long inputVersion, UUID token, Instant leaseUntil, String state, String errorCode) {}
    record Claim(SessionView replay, Job job, LearningReteachGenerator.Context context) {}
    private record Fingerprint(String operation, UUID sessionId, StartReteachRequest request) {}

    @Transactional
    public Claim begin(String member, UUID id, String key, StartReteachRequest request) {
        access.validateKey(key);
        access.lockMember(member);
        var session = sessions.lockOwned(id, member).orElseThrow(LearningException::notFound);
        String body = json.writeValueAsString(new Fingerprint("reteach", id, request));
        var replay = access.replay(member, key, body);
        if (replay.isPresent()) return new Claim(replay.get(), null, null);
        if (!session.status.equals("active") || session.pendingTurnId != null) throw access.conflict(session);
        var existing = job(member, key);
        if (existing != null) {
            if (!existing.id().equals(session.activeReteachId) || session.version != existing.inputVersion()
                    || !session.stage.equals("chat") || !java.util.List.of("failed", "generating").contains(existing.state())) {
                throw access.conflict(session);
            }
            if (GenerationRecovery.blocksRetry(existing.errorCode())) throw LearningException.retryBlocked(existing.errorCode(),access.view(session));
            verifyTarget(target(existing));
            if (existing.state().equals("generating") && existing.leaseUntil().isAfter(Instant.now())) {
                throw new LearningException(503, "RETEACH_IN_PROGRESS", "재설명 질문을 준비 중입니다.", access.view(session));
            }
            jdbc.update("""
                    UPDATE teachmi.learning_reteach SET state='generating',claim_token=?,
                    lease_until=now()+interval '120 seconds',error_code=NULL WHERE id=?
                    """, UUID.randomUUID(), existing.id());
            var resumed = job(member, key);
            return new Claim(null, resumed, context(session, resumed));
        }
        if (!session.stage.equals("problem") || session.activeReteachId != null
                || session.version != request.expectedVersion()) throw access.conflict(session);
        var target = readTarget(member, id, request.problemId(), request.attemptId());
        long count = jdbc.queryForObject("""
                SELECT count(*) FROM teachmi.learning_reteach WHERE session_id=? AND problem_id=? AND attempt_id=?
                """, Long.class, id, request.problemId(), request.attemptId());
        if (count != 0) {
            throw new LearningException(409, "RETEACH_ALREADY_EXISTS", "이 오답 시도의 재설명 기록이 있습니다.", access.view(session));
        }
        UUID reteachId = UUID.randomUUID();
        session.activeReteachId = reteachId;
        session.returnReteachId = null;
        session.stage = "chat";
        session.chatStage = "case";
        session.touch();
        sessions.flush();
        jdbc.update("""
                INSERT INTO teachmi.learning_reteach
                (id,session_id,member_id,problem_id,attempt_id,target_json,focus,state,request_key,request_body,
                 expected_version,input_version,claim_token,lease_until,created_at)
                VALUES (?,?,?,?,?,?,?,'generating',?,?,?,?,?,now()+interval '120 seconds',now())
                """, reteachId, id, member, target.problemId(), target.attemptId(), json.writeValueAsString(target),
                target.focus(), key, body, request.expectedVersion(), session.version, UUID.randomUUID());
        var saved = job(member, key);
        return new Claim(null, saved, context(session, saved));
    }

    @Transactional
    public SessionView complete(Claim claim, LearningContentGenerator.Question question) {
        var job = claim.job();
        access.lockMember(job.memberId());
        var session = sessions.lockOwned(job.sessionId(), job.memberId()).orElseThrow(LearningException::notFound);
        var current = job(job.memberId(), job.key());
        if (!current.token().equals(job.token()) || !current.state().equals("generating")
                || !job.id().equals(session.activeReteachId) || !session.status.equals("active")
                || !session.stage.equals("chat") || session.version != job.inputVersion()) throw access.conflict(session);
        verifyTarget(target(job));
        access.verifyEvidence(question.sourceChunkIds(), claim.context().learning().concept());
        var history = messages.findBySessionIdOrderBySequenceAsc(session.id);
        long sequence = history.isEmpty() ? 1 : history.getLast().sequence + 1;
        var message = new LearningMessage(session.id, sequence, "assistant", "reteach_question", "case",
                question.text(), json.writeValueAsString(question.sourceChunkIds()), null);
        message.reteachId = job.id();
        session.touch();
        message = messages.saveAndFlush(message);
        jdbc.update("UPDATE teachmi.learning_reteach SET state='chat',question_message_id=?,error_code=NULL WHERE id=?",
                message.id, job.id());
        var result = access.view(session);
        access.remember(job.memberId(), job.key(), job.body(), result);
        return result;
    }

    @Transactional
    public SessionView fail(Claim claim, String code) {
        var job = claim.job();
        access.lockMember(job.memberId());
        var session = sessions.lockOwned(job.sessionId(), job.memberId()).orElseThrow(LearningException::notFound);
        jdbc.update("""
                UPDATE teachmi.learning_reteach SET state='failed',error_code=?
                WHERE id=? AND claim_token=? AND state='generating'
                """, code, job.id(), job.token());
        return access.view(session);
    }

    /** C calls this first inside its own transaction, then selects/resumes the existing problem. */
    @Transactional
    public SessionView acknowledgeReturn(String member, UUID sessionId, UUID reteachId, long expectedVersion) {
        access.lockMember(member);
        var session = sessions.lockOwned(sessionId, member).orElseThrow(LearningException::notFound);
        var rows = jdbc.queryForList("SELECT target_json,state FROM teachmi.learning_reteach WHERE id=? AND session_id=? AND member_id=?",
                reteachId, sessionId, member);
        if (rows.isEmpty()) throw LearningException.notFound();
        if (!rows.getFirst().get("state").equals("returned")) throw access.conflict(session);
        if (session.returnReteachId == null && session.activeReteachId == null
                && session.status.equals("active") && session.stage.equals("problem")) return access.view(session);
        if (!reteachId.equals(session.returnReteachId) || !session.status.equals("active")
                || !session.stage.equals("problem") || session.version != expectedVersion) throw access.conflict(session);
        verifyTarget(json.readValue((String) rows.getFirst().get("target_json"), ReteachProblemSource.Target.class));
        session.returnReteachId = null;
        session.touch();
        sessions.flush();
        return access.view(session);
    }

    // The following hooks join the caller's existing turn transaction.
    ReteachFocus verifyActive(LearningSession session) {
        if (session.activeReteachId == null) return null;
        var rows = jdbc.queryForList("SELECT target_json,state FROM teachmi.learning_reteach WHERE id=?", session.activeReteachId);
        if (rows.isEmpty() || !rows.getFirst().get("state").equals("chat")) throw access.conflict(session);
        var target = json.readValue((String) rows.getFirst().get("target_json"), ReteachProblemSource.Target.class);
        verifyTarget(target);
        return new ReteachFocus(session.activeReteachId, target.focus());
    }

    void recordInitial(LearningSession session, UUID messageId) {
        if (session.activeReteachId != null && session.chatStage.equals("case")) {
            jdbc.update("UPDATE teachmi.learning_reteach SET initial_answer_id=? WHERE id=? AND initial_answer_id IS NULL",
                    messageId, session.activeReteachId);
        }
    }

    void finishIfReady(LearningSession session, UUID messageId) {
        if (session.activeReteachId != null && session.stage.equals("problem")) {
            jdbc.update("""
                    UPDATE teachmi.learning_reteach SET state='returned',final_explanation_id=?,completed_at=now()
                    WHERE id=? AND state='chat'
                    """, messageId, session.activeReteachId);
            session.returnReteachId = session.activeReteachId;
            session.activeReteachId = null;
        }
    }

    private ReteachProblemSource.Target readTarget(String member, UUID session, String problem, String attempt) {
        var source = problems.getIfAvailable();
        if (source == null) {
            throw new LearningException(503, "LEARNING_PROBLEM_INTEGRATION_PENDING", "C의 문항 소유권·오답 조회 연결이 필요합니다.");
        }
        var result = source.loadWrongAttempt(member, session, problem, attempt);
        if (result == null || !member.equals(result.memberId()) || !session.equals(result.sessionId())
                || !problem.equals(result.problemId())) throw LearningException.notFound();
        if (!attempt.equals(result.attemptId()) || !"wrong".equals(result.judgement())) {
            throw new LearningException(409, "WRONG_ATTEMPT_REQUIRED", "현재 오답 시도를 확인하세요.");
        }
        if (result.focus() == null || result.focus().isBlank() || result.focus().length() > 2000
                || result.focus().indexOf('\0') >= 0) {
            throw new LearningException(502, "INVALID_RETEACH_FOCUS", "재설명 보완 항목을 확인할 수 없습니다.");
        }
        return result;
    }

    private void verifyTarget(ReteachProblemSource.Target saved) {
        var current = readTarget(saved.memberId(), saved.sessionId(), saved.problemId(), saved.attemptId());
        if (!saved.equals(current)) {
            throw new LearningException(409, "RETEACH_ORIGIN_CHANGED", "원래 오답의 보완 항목이 변경됐습니다.");
        }
    }

    private ReteachProblemSource.Target target(Job job) {
        return json.readValue(job.targetJson(), ReteachProblemSource.Target.class);
    }
    private LearningReteachGenerator.Context context(LearningSession session, Job job) {
        return new LearningReteachGenerator.Context(access.contentContext(session, session.mode),
                new ReteachFocus(job.id(), target(job).focus()));
    }
    private Job job(String member, String key) {
        var rows = jdbc.query("SELECT * FROM teachmi.learning_reteach WHERE member_id=? AND request_key=?",
                (rs, row) -> new Job(rs.getObject("id", UUID.class), rs.getObject("session_id", UUID.class),
                        rs.getString("member_id"), rs.getString("request_key"), rs.getString("request_body"),
                        rs.getString("target_json"), rs.getLong("input_version"), rs.getObject("claim_token", UUID.class),
                        rs.getTimestamp("lease_until").toInstant(), rs.getString("state"),rs.getString("error_code")), member, key);
        return rows.isEmpty() ? null : rows.getFirst();
    }
}
