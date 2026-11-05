package com.teachmi.backend.learning;

import java.util.UUID;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

@Service
public class LearningSessionService {
    private final LearningSessionRepository sessions;
    private final ObjectProvider<ConceptSource> concepts;
    private final JdbcTemplate jdbc;
    private final ObjectMapper json;

    public LearningSessionService(LearningSessionRepository sessions, ObjectProvider<ConceptSource> concepts,
                                  JdbcTemplate jdbc, ObjectMapper json) {
        this.sessions = sessions;
        this.concepts = concepts;
        this.jdbc = jdbc;
        this.json = json;
    }

    @Transactional
    public SessionView create(String memberId, String key, CreateSessionRequest request) {
        if (key == null || key.isBlank() || key.length() > 128) {
            throw new LearningException(422, "INVALID_IDEMPOTENCY_KEY",
                    "Idempotency-Key는 1~128자로 전달해야 합니다.");
        }
        // Also serialize first-ever starts, when there is no session row to lock yet.
        jdbc.queryForList("SELECT pg_advisory_xact_lock(hashtextextended(?, 0))",
                "teachmi:learning:" + memberId);
        String requestJson = json.writeValueAsString(request);
        var previous = jdbc.queryForList("""
                SELECT request_body, response_body FROM teachmi.learning_session_request
                WHERE member_id = ? AND request_key = ?
                """, memberId, key);
        if (!previous.isEmpty()) {
            var saved = previous.getFirst();
            if (!requestJson.equals(saved.get("request_body"))) {
                throw new LearningException(409, "IDEMPOTENCY_KEY_REUSED", "같은 키에 다른 요청을 보냈습니다.");
            }
            return json.readValue((String) saved.get("response_body"), SessionView.class);
        }

        var active = sessions.findByMemberIdAndStatus(memberId, "active");
        if (active.isPresent()) {
            var current = active.get();
            if (!current.id.equals(request.replaceSessionId())
                    || request.expectedVersion() == null
                    || current.version != request.expectedVersion()) {
                throw new LearningException(409, "ACTIVE_SESSION_EXISTS",
                        "진행 중인 학습을 이어가거나 확인 후 새 학습을 시작하세요.", view(current));
            }
        } else if (request.replaceSessionId() != null) {
            throw new LearningException(409, "REPLACEMENT_CHANGED", "진행 중인 학습이 변경됐습니다.");
        }

        // Verify ownership before ending an existing session. Failures roll back the entire operation.
        var source = concepts.getIfAvailable();
        if (source == null) {
            throw new LearningException(503, "CONCEPT_SOURCE_UNAVAILABLE", "자료·개념 조회 연결이 필요합니다.");
        }
        var snapshot = java.util.Objects.requireNonNull(
                source.loadOwnedConcept(memberId, request.materialId(), request.conceptId()));
        active.ifPresent(LearningSession::abandon);
        sessions.flush();
        var created = sessions.saveAndFlush(new LearningSession(memberId, request,
                json.writeValueAsString(snapshot)));
        var result = view(created);
        jdbc.update("""
                INSERT INTO teachmi.learning_session_request(member_id, request_key, session_id,
                                                             request_body, response_body)
                VALUES (?, ?, ?, ?, ?)
                """, memberId, key, created.id, requestJson, json.writeValueAsString(result));
        return result;
    }

    @Transactional(readOnly = true)
    public SessionView get(String memberId, UUID sessionId) {
        return view(sessions.findByIdAndMemberId(sessionId, memberId)
                .orElseThrow(LearningException::notFound));
    }

    private SessionView view(LearningSession session) {
        return new SessionView(session.id, session.materialId, session.conceptId,
                json.readValue(session.conceptSnapshot, ConceptSource.Snapshot.class),
                session.mode, session.stage, session.status, session.version,
                session.createdAt, session.updatedAt,
                session.status.equals("active")
                        ? new SessionView.Resume(session.stage, "/api/v1/sessions/" + session.id) : null);
    }
}
