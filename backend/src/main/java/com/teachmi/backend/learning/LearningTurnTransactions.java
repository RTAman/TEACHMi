package com.teachmi.backend.learning;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

/** Commit user input before generation; commit the assistant and stage change together afterwards. */
@Service
public class LearningTurnTransactions {
    private final LearningSessionService access;
    private final LearningSessionRepository sessions;
    private final LearningMessageRepository messages;
    private final JdbcTemplate jdbc;
    private final ObjectMapper json;
    private final LearningReteachTransactions reteach;

    LearningTurnTransactions(LearningSessionService access, LearningSessionRepository sessions,
                             LearningMessageRepository messages, JdbcTemplate jdbc, ObjectMapper json,
                             LearningReteachTransactions reteach) {
        this.access = access;
        this.sessions = sessions;
        this.messages = messages;
        this.jdbc = jdbc;
        this.json = json;
        this.reteach = reteach;
    }

    record Pending(UUID id, UUID sessionId, String memberId, String key, String body, String kind,
                   String text, String chatStage, UUID userMessageId, UUID targetMessageId,
                   long inputVersion, long expectedVersion, UUID claimToken, Instant leaseUntil, String state, UUID reteachId, String errorCode) {}
    record Claim(SessionView replay, Pending pending, LearningDialogueGenerator.Context context) {}
    private record Fingerprint(String operation, UUID sessionId, TurnRequest request) {}

    @Transactional
    public Claim begin(String member, UUID id, String key, TurnRequest request) {
        access.validateKey(key);
        access.lockMember(member);
        var session = sessions.lockOwned(id, member).orElseThrow(LearningException::notFound);
        String body = json.writeValueAsString(new Fingerprint("turns", id, request));
        var replay = access.replay(member, key, body);
        if (replay.isPresent()) return new Claim(replay.get(), null, null);
        if (!session.status.equals("active") || !session.stage.equals("chat")) throw access.conflict(session);
        reteach.verifyActive(session);
        var existing = pending(member, key);
        // 통과된 마지막 설명 뒤에는 '설명 마치기'만 남는다. 진행 중이던 같은 전송의 재시도는 아래에서 처리한다.
        if (existing == null && session.awaitingFinish()) throw access.conflict(session);
        if (existing != null) {
            if (!existing.id().equals(session.pendingTurnId) || existing.inputVersion() != session.version) {
                throw access.conflict(session);
            }
            if (GenerationRecovery.blocksRetry(existing.errorCode())) throw LearningException.retryBlocked(existing.errorCode(),access.view(session));
            if (existing.state().equals("generating") && existing.leaseUntil().isAfter(Instant.now())) {
                throw new LearningException(503, "TURN_IN_PROGRESS", "같은 전송을 처리 중입니다.", access.view(session));
            }
            jdbc.update("""
                    UPDATE teachmi.learning_turn SET state='generating',claim_token=?,
                    lease_until=now()+interval '120 seconds',error_code=NULL WHERE id=?
                    """, UUID.randomUUID(), existing.id());
            var resumed = pending(member, key);
            return new Claim(null, resumed, context(session, resumed));
        }
        if (session.pendingTurnId != null || session.version != request.expectedVersion()) {
            throw access.conflict(session);
        }
        var history = messages.findBySessionIdOrderBySequenceAsc(id);
        var question = history.stream().filter(message -> message.role.equals("assistant")
                && message.kind.endsWith("_question") && message.chatStage.equals(session.chatStage)
                && java.util.Objects.equals(message.reteachId, session.activeReteachId))
                .reduce((left, right) -> right).orElseThrow(() -> access.conflict(session));
        var last = history.getLast();
        boolean answer = request.kind().equals("answer");
        UUID support = answer ? (last.role.equals("assistant") ? last.supportEventId : null) : UUID.randomUUID();
        String text = answer ? request.text() : switch (request.kind()) {
            case "hint" -> "힌트가 필요해요.";
            case "example" -> "쉬운 예시를 보여 주세요.";
            case "dont_know" -> "잘 모르겠어요.";
            default -> throw new IllegalArgumentException("Invalid turn kind");
        };
        var user = new LearningMessage(id, last.sequence + 1, "user", request.kind(), session.chatStage, text, "[]", support);
        user.reteachId = session.activeReteachId;
        user = messages.saveAndFlush(user);
        if (!answer) {
            jdbc.update("""
                    INSERT INTO teachmi.learning_support_event
                    (id,session_id,kind,target_message_id,request_message_id,created_at,reteach_id) VALUES (?,?,?,?,?,?,?)
                    """, support, id, request.kind(), question.id, user.id, java.sql.Timestamp.from(user.createdAt), session.activeReteachId);
        }
        if (answer && session.activeReteachId == null && session.chatStage.equals("case") && session.initialAnswerId == null) {
            session.initialAnswerId = user.id;
        }
        if (answer) reteach.recordInitial(session, user.id);
        UUID turnId = UUID.randomUUID();
        session.pendingTurnId = turnId;
        session.touch();
        sessions.flush();
        jdbc.update("""
                INSERT INTO teachmi.learning_turn
                (id,session_id,member_id,request_key,request_body,kind,text,chat_stage,
                 user_message_id,target_message_id,input_version,expected_version,claim_token,reteach_id,lease_until,state)
                VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,now()+interval '120 seconds','generating')
                """, turnId, id, member, key, body, request.kind(), text, session.chatStage,
                user.id, question.id, session.version, request.expectedVersion(), UUID.randomUUID(), session.activeReteachId);
        var saved = pending(member, key);
        return new Claim(null, saved, context(session, saved));
    }

    @Transactional
    public SessionView complete(Claim claim, LearningDialogueGenerator.Reply reply) {
        var turn = claim.pending();
        access.lockMember(turn.memberId());
        var session = sessions.lockOwned(turn.sessionId(), turn.memberId()).orElseThrow(LearningException::notFound);
        var current = pending(turn.memberId(), turn.key());
        if (!current.claimToken().equals(turn.claimToken()) || !current.state().equals("generating")
                || !turn.id().equals(session.pendingTurnId) || !session.status.equals("active")
                || !session.stage.equals("chat") || session.version != turn.inputVersion()
                || !java.util.Objects.equals(turn.reteachId(), session.activeReteachId)) {
            throw access.conflict(session);
        }
        reteach.verifyActive(session);
        access.verifyEvidence(reply.sourceChunkIds(), claim.context().learning().concept());
        if (!turn.kind().equals("answer") && reply.accepted()) {
            throw new LearningException(502, "INVALID_DIALOGUE_REPLY", "도움 응답의 단계 판정이 올바르지 않습니다.");
        }
        var user = messages.findById(turn.userMessageId()).orElseThrow();
        if (reply.accepted()) session.acceptAnswer(user.id);
        if (reply.accepted()) reteach.finishIfReady(session, user.id);
        String kind = turn.kind();
        if (kind.equals("answer")) {
            if (!reply.accepted()) kind = "followup_question";
            else if (session.stage.equals("problem") || session.awaitingFinish())
                kind = turn.reteachId() == null ? "learning_complete" : "reteach_complete";
            else if (session.chatStage.equals("wrapup")) kind = "wrapup_question";
            else kind = "stage_question";
        }
        session.pendingTurnId = null;
        session.touch();
        // Navigation follows the validated server transition. The model's cached payload stays untouched.
        String guidance = switch (kind) {
            case "learning_complete" -> "이제 응용문제를 풀어 보세요.";
            case "reteach_complete" -> "같은 문제에 다시 도전해 보세요.";
            default -> null;
        };
        String text = guidance == null || reply.text().endsWith(guidance)
                ? reply.text() : reply.text() + "\n\n" + guidance;
        var assistant = new LearningMessage(session.id, user.sequence + 1,
                "assistant", kind, session.chatStage, text, json.writeValueAsString(reply.sourceChunkIds()),
                turn.kind().equals("answer") ? null : user.supportEventId);
        assistant.reteachId = turn.reteachId();
        assistant = messages.saveAndFlush(assistant);
        if (!turn.kind().equals("answer")) {
            jdbc.update("UPDATE teachmi.learning_support_event SET response_message_id=? WHERE id=?",
                    assistant.id, user.supportEventId);
        }
        jdbc.update("UPDATE teachmi.learning_turn SET state='succeeded',error_code=NULL WHERE id=?", turn.id());
        sessions.flush();
        var result = access.view(session);
        access.remember(turn.memberId(), turn.key(), turn.body(), result);
        return result;
    }

    @Transactional
    public SessionView fail(Claim claim, String code) {
        var turn = claim.pending();
        access.lockMember(turn.memberId());
        var session = sessions.lockOwned(turn.sessionId(), turn.memberId()).orElseThrow(LearningException::notFound);
        jdbc.update("""
                UPDATE teachmi.learning_turn SET state='failed',error_code=?
                WHERE id=? AND claim_token=? AND state='generating'
                """, code, turn.id(), turn.claimToken());
        return access.view(session);
    }

    private LearningDialogueGenerator.Context context(LearningSession session, Pending turn) {
        var view = access.view(session);
        var all = view.messages().stream().filter(item -> java.util.Objects.equals(item.reteachId(), turn.reteachId())).toList();
        var target = all.stream().filter(message -> message.messageId().equals(turn.targetMessageId())).findFirst().orElseThrow();
        int start = all.size();
        int length = 0;
        while (start > 0 && all.size() - start < 20
                && length + all.get(start - 1).text().length() <= 24000) {
            length += all.get(--start).text().length();
        }
        var recent = all.subList(start, all.size());
        return new LearningDialogueGenerator.Context(access.contentContext(session, session.mode), turn.id(),
                turn.chatStage(), turn.kind(), turn.text(), target, recent, reteach.verifyActive(session));
    }

    private Pending pending(String member, String key) {
        var rows = jdbc.query("""
                SELECT * FROM teachmi.learning_turn WHERE member_id=? AND request_key=?
                """, (rs, row) -> new Pending(rs.getObject("id", UUID.class), rs.getObject("session_id", UUID.class),
                rs.getString("member_id"), rs.getString("request_key"), rs.getString("request_body"),
                rs.getString("kind"), rs.getString("text"), rs.getString("chat_stage"),
                rs.getObject("user_message_id", UUID.class), rs.getObject("target_message_id", UUID.class),
                rs.getLong("input_version"), rs.getLong("expected_version"), rs.getObject("claim_token", UUID.class),
                rs.getTimestamp("lease_until").toInstant(), rs.getString("state"), rs.getObject("reteach_id", UUID.class),rs.getString("error_code")), member, key);
        return rows.isEmpty() ? null : rows.getFirst();
    }
}
