package com.teachmi.backend.learning;

import java.util.UUID;
import java.util.List;
import java.util.Optional;
import java.util.function.Supplier;
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
    private final LearningMessageRepository messages;
    private final ObjectProvider<LearningContentGenerator> generators;

    public LearningSessionService(LearningSessionRepository sessions, ObjectProvider<ConceptSource> concepts,
                                  JdbcTemplate jdbc, ObjectMapper json, LearningMessageRepository messages,
                                  ObjectProvider<LearningContentGenerator> generators) {
        this.sessions = sessions;
        this.concepts = concepts;
        this.jdbc = jdbc;
        this.json = json;
        this.messages = messages;
        this.generators = generators;
    }

    @Transactional
    public SessionView create(String memberId, String key, CreateSessionRequest request) {
        validateKey(key);
        lockMember(memberId);
        String requestJson = json.writeValueAsString(request);
        var previous = replay(memberId, key, requestJson);
        if (previous.isPresent()) {
            return previous.get();
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
        remember(memberId, key, requestJson, result);
        return result;
    }

    @Transactional
    public SessionView chooseMode(String memberId, UUID sessionId, String key, ChooseModeRequest request) {
        return transition(memberId, sessionId, key, "mode", request.mode(), request.expectedVersion());
    }

    @Transactional
    public SessionView startChat(String memberId, UUID sessionId, String key, StartChatRequest request) {
        return transition(memberId, sessionId, key, "chat/start", null, request.expectedVersion());
    }

    private SessionView transition(String memberId, UUID id, String key, String operation,
                                   String mode, long expectedVersion) {
        validateKey(key);
        lockMember(memberId);
        var session = sessions.lockOwned(id, memberId).orElseThrow(LearningException::notFound);
        String requestJson = json.writeValueAsString(new TransitionRequest(operation, id, mode, expectedVersion));
        var previous = replay(memberId, key, requestJson);
        if (previous.isPresent()) {
            return previous.get();
        }
        if (!session.status.equals("active") || session.version != expectedVersion) {
            throw conflict(session);
        }
        if (operation.equals("mode")) {
            if (!session.stage.equals("mode") || session.mode != null) {
                throw conflict(session);
            }
            var generator = requireGenerator();
            var context = contentContext(session, mode);
            if (mode.equals("first")) {
                var summary = generate(() -> generator.summarize(context));
                verifyEvidence(summary.sourceChunkIds(), context.concept());
                session.selectMode(mode, json.writeValueAsString(summary));
            } else {
                saveOpeningQuestion(session, generator, context);
                session.selectMode(mode, null);
            }
        } else {
            if (!"first".equals(session.mode)) {
                throw conflict(session);
            }
            if (session.stage.equals("summary") && session.summaryJson != null) {
                saveOpeningQuestion(session, requireGenerator(), contentContext(session, session.mode));
                session.startChat();
            } else if (!session.stage.equals("chat") || !messages.existsBySessionId(id)) {
                throw conflict(session);
            }
            // Reopening an already started first-mode chat returns its saved question without an update.
        }
        sessions.flush();
        var result = view(session);
        remember(memberId, key, requestJson, result);
        return result;
    }

    private void saveOpeningQuestion(LearningSession session, LearningContentGenerator generator,
                                     LearningContentGenerator.Context context) {
        var question = generate(() -> generator.openingQuestion(context));
        verifyEvidence(question.sourceChunkIds(), context.concept());
        messages.saveAndFlush(new LearningMessage(session.id, question.text(),
                json.writeValueAsString(question.sourceChunkIds())));
    }

    private LearningContentGenerator.Context contentContext(LearningSession session, String mode) {
        return new LearningContentGenerator.Context(session.memberId, session.id, session.materialId,
                session.conceptId, mode, json.readValue(session.conceptSnapshot, ConceptSource.Snapshot.class),
                session.summaryJson == null ? null
                        : json.readValue(session.summaryJson, LearningContentGenerator.Summary.class));
    }

    private LearningContentGenerator requireGenerator() {
        var generator = generators.getIfAvailable();
        if (generator == null) {
            throw new LearningException(503, "LEARNING_CONTENT_UNAVAILABLE", "학습 콘텐츠 생성 연결이 필요합니다.");
        }
        return generator;
    }

    private <T> T generate(Supplier<T> action) {
        try {
            return java.util.Objects.requireNonNull(action.get());
        } catch (LearningException ex) {
            throw ex;
        } catch (RuntimeException ex) {
            throw new LearningException(502, "LEARNING_CONTENT_FAILED", "학습 콘텐츠 생성에 실패했습니다. 다시 시도하세요.");
        }
    }

    private void verifyEvidence(List<String> sourceIds, ConceptSource.Snapshot concept) {
        if (sourceIds.isEmpty() || !concept.sourceChunkIds().containsAll(sourceIds)) {
            throw new LearningException(502, "INVALID_LEARNING_CONTENT", "선택 개념의 출처를 확인할 수 없습니다.");
        }
    }

    private LearningException conflict(LearningSession session) {
        return new LearningException(409, "SESSION_STATE_CONFLICT", "학습 단계 또는 버전이 변경됐습니다.", view(session));
    }

    private void validateKey(String key) {
        if (key == null || key.isBlank() || key.length() > 128) {
            throw new LearningException(422, "INVALID_IDEMPOTENCY_KEY",
                    "Idempotency-Key는 1~128자로 전달해야 합니다.");
        }
    }

    private void lockMember(String memberId) {
        // This lock also serializes first starts when there is no existing session row.
        jdbc.queryForList("SELECT pg_advisory_xact_lock(hashtextextended(?, 0))",
                "teachmi:learning:" + memberId);
    }

    private Optional<SessionView> replay(String memberId, String key, String requestJson) {
        var rows = jdbc.queryForList("""
                SELECT request_body, response_body FROM teachmi.learning_session_request
                WHERE member_id = ? AND request_key = ?
                """, memberId, key);
        if (rows.isEmpty()) {
            return Optional.empty();
        }
        var saved = rows.getFirst();
        if (!requestJson.equals(saved.get("request_body"))) {
            throw new LearningException(409, "IDEMPOTENCY_KEY_REUSED", "같은 키에 다른 요청을 보냈습니다.");
        }
        return Optional.of(json.readValue((String) saved.get("response_body"), SessionView.class));
    }

    private void remember(String memberId, String key, String requestJson, SessionView result) {
        jdbc.update("""
                INSERT INTO teachmi.learning_session_request(member_id, request_key, session_id,
                                                             request_body, response_body)
                VALUES (?, ?, ?, ?, ?)
                """, memberId, key, result.sessionId(), requestJson, json.writeValueAsString(result));
    }

    private record TransitionRequest(String operation, UUID sessionId, String mode, long expectedVersion) {}

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
                        ? new SessionView.Resume(session.stage, "/api/v1/sessions/" + session.id) : null,
                session.summaryJson == null ? null
                        : json.readValue(session.summaryJson, LearningContentGenerator.Summary.class),
                session.chatStage,
                messages.findBySessionIdOrderBySequenceAsc(session.id).stream()
                        .map(message -> new LearningMessageView(message.id, message.sequence, message.role,
                                message.kind, message.chatStage, message.text,
                                List.of(json.readValue(message.sourceChunkIds, String[].class)),
                                message.createdAt)).toList());
    }
}
