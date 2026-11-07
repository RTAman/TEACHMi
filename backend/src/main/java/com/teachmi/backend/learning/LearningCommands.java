package com.teachmi.backend.learning;

import java.util.UUID;
import java.util.function.Function;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

/** Internal C commands share B's member lock and key space. DB-only callbacks, same transaction/thread. */
@Service
public class LearningCommands {
    private final LearningSessionService access;
    private final LearningAssessmentAccess locks;
    private final JdbcTemplate jdbc;
    private final ObjectMapper json;

    LearningCommands(LearningSessionService access, LearningAssessmentAccess locks, JdbcTemplate jdbc, ObjectMapper json) {
        this.access=access; this.locks=locks; this.jdbc=jdbc; this.json=json;
    }
    private record Fingerprint(String command, UUID sessionId, String body) {}

    public void validateKey(String key) { access.validateKey(key); }

    @Transactional
    public <T> T execute(String member, UUID sessionId, String key, String command, String canonicalBody,
                         Class<T> responseType, Function<SessionView,T> persistence) {
        access.validateKey(key);
        if (command == null || !command.startsWith("c/")) throw new IllegalArgumentException("Internal C command required");
        var locked=locks.lockOwned(member,sessionId);
        var fingerprint=json.writeValueAsString(new Fingerprint(command,sessionId,canonicalBody));
        var rows=jdbc.queryForList("""
                SELECT request_body,response_body FROM teachmi.learning_session_request WHERE member_id=? AND request_key=?
                """,member,key);
        if (!rows.isEmpty()) {
            var saved=rows.getFirst();
            if (!fingerprint.equals(saved.get("request_body"))) {
                throw new LearningException(409,"IDEMPOTENCY_KEY_REUSED","같은 키에 다른 요청을 보냈습니다.");
            }
            return json.readValue((String)saved.get("response_body"),responseType);
        }
        // Include in-flight B turns and reteach reservations, not only completed receipts.
        access.replay(member,key,fingerprint);
        var result=persistence.apply(locked);
        jdbc.update("""
                INSERT INTO teachmi.learning_session_request(member_id,request_key,session_id,request_body,response_body)
                VALUES (?,?,?,?,?)
                """,member,key,sessionId,fingerprint,json.writeValueAsString(result));
        return result;
    }
}
