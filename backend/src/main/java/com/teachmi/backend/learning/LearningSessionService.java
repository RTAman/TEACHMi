package com.teachmi.backend.learning;

import java.util.UUID;
import java.util.List;
import java.util.Optional;
import java.util.function.Supplier;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Propagation;
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
        return createInternal(memberId,key,request,true);
    }

    /** Only inside LearningCommands: its external operation fingerprint and receipt own this creation. */
    @Transactional(propagation = Propagation.MANDATORY)
    public SessionView createInCommand(String memberId, CreateSessionRequest request) {
        return createInternal(memberId,null,request,false);
    }

    private SessionView createInternal(String memberId, String key, CreateSessionRequest request, boolean ownReceipt) {
        if (ownReceipt) validateKey(key);
        lockMember(memberId);
        String requestJson = json.writeValueAsString(request);
        if (ownReceipt) {
            var previous = replay(memberId, key, requestJson);
            if (previous.isPresent()) return previous.get();
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
        if (ownReceipt) remember(memberId, key, requestJson, result);
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

    LearningContentGenerator.Context contentContext(LearningSession session, String mode) {
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

    void verifyEvidence(List<String> sourceIds, ConceptSource.Snapshot concept) {
        if (sourceIds.isEmpty() || !concept.sourceChunkIds().containsAll(sourceIds)) {
            throw new LearningException(502, "INVALID_LEARNING_CONTENT", "선택 개념의 출처를 확인할 수 없습니다.");
        }
    }

    LearningException conflict(LearningSession session) {
        return new LearningException(409, "SESSION_STATE_CONFLICT", "학습 단계 또는 버전이 변경됐습니다.", view(session));
    }

    void validateKey(String key) {
        if (key == null || key.isBlank() || key.length() > 128 || key.indexOf('\0') >= 0) {
            throw new LearningException(422, "INVALID_IDEMPOTENCY_KEY",
                    "Idempotency-Key는 1~128자로 전달해야 합니다.");
        }
    }

    void lockMember(String memberId) {
        // This lock also serializes first starts when there is no existing session row.
        jdbc.queryForList("SELECT pg_advisory_xact_lock(hashtextextended(?, 0))",
                "teachmi:learning:" + memberId);
    }

    Optional<SessionView> replay(String memberId, String key, String requestJson) {
        var rows = jdbc.queryForList("""
                SELECT request_body, response_body FROM teachmi.learning_session_request
                WHERE member_id = ? AND request_key = ?
                """, memberId, key);
        if (rows.isEmpty()) {
            var reservations = jdbc.queryForList(
                    "SELECT request_body FROM teachmi.learning_turn WHERE member_id=? AND request_key=?",
                    memberId, key);
            if (!reservations.isEmpty() && !requestJson.equals(reservations.getFirst().get("request_body"))) {
                throw new LearningException(409, "IDEMPOTENCY_KEY_REUSED", "같은 키에 다른 요청을 보냈습니다.");
            }
            var reteachKeys = jdbc.queryForList(
                    "SELECT request_body FROM teachmi.learning_reteach WHERE member_id=? AND request_key=?", memberId, key);
            if (!reteachKeys.isEmpty() && !requestJson.equals(reteachKeys.getFirst().get("request_body"))) {
                throw new LearningException(409, "IDEMPOTENCY_KEY_REUSED", "같은 키에 다른 요청을 보냈습니다.");
            }
            return Optional.empty();
        }
        var saved = rows.getFirst();
        if (!requestJson.equals(saved.get("request_body"))) {
            throw new LearningException(409, "IDEMPOTENCY_KEY_REUSED", "같은 키에 다른 요청을 보냈습니다.");
        }
        return Optional.of(json.readValue((String) saved.get("response_body"), SessionView.class));
    }

    void remember(String memberId, String key, String requestJson, SessionView result) {
        jdbc.update("""
                INSERT INTO teachmi.learning_session_request(member_id, request_key, session_id,
                                                             request_body, response_body)
                VALUES (?, ?, ?, ?, ?)
                """, memberId, key, result.sessionId(), requestJson, json.writeValueAsString(result));
    }

    private record TransitionRequest(String operation, UUID sessionId, String mode, long expectedVersion) {}

    /** Committed resume snapshot for HTTP and C's home, including callers in an outer transaction. */
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ, propagation = Propagation.REQUIRES_NEW)
    public Optional<SessionView> getActive(String memberId) {
        return sessions.findActiveByMemberId(memberId).map(this::view);
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public SessionView get(String memberId, UUID sessionId) {
        return view(sessions.findByIdAndMemberId(sessionId, memberId)
                .orElseThrow(LearningException::notFound));
    }

    SessionView view(LearningSession session) {
        TurnProgress pending = null;
        if (session.pendingTurnId != null) {
            var rows = jdbc.query("""
                    SELECT *, lease_until > now() AS leased FROM teachmi.learning_turn WHERE id=?
                    """, (rs, row) -> new TurnProgress(rs.getObject("id", UUID.class), rs.getString("kind"),
                    rs.getString("state").equals("generating") && !rs.getBoolean("leased")
                            ? "interrupted" : rs.getString("state"),
                    rs.getObject("user_message_id", UUID.class), rs.getString("error_code"),
                    rs.getString("request_key"), rs.getLong("expected_version")), session.pendingTurnId);
            if (!rows.isEmpty()) pending = rows.getFirst();
        }
        var supports = jdbc.query("""
                SELECT * FROM teachmi.learning_support_event WHERE session_id=? ORDER BY created_at,id
                """, (rs, row) -> new SupportEventView(rs.getObject("id", UUID.class), rs.getString("kind"),
                rs.getObject("target_message_id", UUID.class), rs.getObject("request_message_id", UUID.class),
                rs.getObject("response_message_id", UUID.class), rs.getTimestamp("created_at").toInstant(),
                rs.getObject("reteach_id", UUID.class)), session.id);
        var episodes = jdbc.query("""
                SELECT *, lease_until > now() AS leased FROM teachmi.learning_reteach
                WHERE session_id=? ORDER BY created_at,id
                """, (rs, row) -> new ReteachView(rs.getObject("id", UUID.class), rs.getString("problem_id"),
                rs.getString("attempt_id"), rs.getString("focus"),
                rs.getString("state").equals("generating") && !rs.getBoolean("leased") ? "interrupted" : rs.getString("state"),
                rs.getObject("question_message_id", UUID.class), rs.getObject("initial_answer_id", UUID.class),
                rs.getObject("final_explanation_id", UUID.class), rs.getTimestamp("created_at").toInstant(),
                rs.getTimestamp("completed_at") == null ? null : rs.getTimestamp("completed_at").toInstant(),
                rs.getString("request_key"), rs.getLong("expected_version"), rs.getString("error_code")), session.id);
        var active = episodes.stream().filter(item -> item.reteachId().equals(session.activeReteachId)).findFirst().orElse(null);
        var returned = episodes.stream().filter(item -> item.reteachId().equals(session.returnReteachId)
                && item.state().equals("returned")).findFirst().orElse(null);
        ProblemReturn problemReturn = returned == null || !session.status.equals("active") ? null
                : new ProblemReturn(returned.reteachId(), returned.problemId(), returned.attemptId(),
                "/api/v1/sessions/" + session.id + "/problems/" + returned.problemId());
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
                                message.createdAt, message.supportEventId, message.reteachId)).toList(),
                session.initialAnswerId, session.finalExplanationId, supports, pending,
                actions(session, pending, active, problemReturn), active, problemReturn, episodes);
    }

    private List<String> actions(LearningSession session, TurnProgress pending, ReteachView active, ProblemReturn returned) {
        if (!session.status.equals("active")) return List.of();
        if (active != null && !active.state().equals("chat")) {
            return active.state().equals("generating") ? List.of("wait") : List.of("retry_reteach");
        }
        if (pending != null) return pending.state().equals("generating") ? List.of("wait") : List.of("retry_turn");
        return switch (session.stage) {
            case "mode" -> List.of("select_mode");
            case "summary" -> List.of("start_chat");
            case "chat" -> List.of("answer", "hint", "example", "dont_know");
            case "problem" -> returned == null ? List.of("start_problems") : List.of("retry_problem");
            case "report" -> List.of("view_report");
            default -> List.of();
        };
    }
}
