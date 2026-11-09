package com.teachmi.backend.learning;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 30초 망설임 기록. 질문 하나에 한 번만 저장하고, 같은 질문을 다시 보내도 성공으로 처리한다.
 * 화면 입력 중에 보내므로 학습 세션의 version을 올리지 않는다. 그래야 다음 답변 전송의 expectedVersion이 그대로 유효하다.
 */
@Service
public class LearningHesitationService {
    private final JdbcTemplate jdbc;

    LearningHesitationService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Transactional
    void record(String memberId, UUID sessionId, HesitationRequest request) {
        var sessions = jdbc.query("""
                SELECT status, stage FROM teachmi.learning_session WHERE id=? AND member_id=?
                """, (rs, row) -> new String[] {rs.getString("status"), rs.getString("stage")}, sessionId, memberId);
        if (sessions.isEmpty()) throw LearningException.notFound();
        var session = sessions.getFirst();
        if (!session[0].equals("active") || !session[1].equals("chat")) {
            throw new LearningException(409, "HESITATION_NOT_ALLOWED", "대화 중인 학습에서만 망설임을 기록할 수 있습니다.");
        }
        var targets = jdbc.query("""
                SELECT role, kind, reteach_id FROM teachmi.learning_message WHERE id=? AND session_id=?
                """, (rs, row) -> new Target(rs.getString("role"), rs.getString("kind"),
                rs.getObject("reteach_id", UUID.class)), request.targetMessageId(), sessionId);
        if (targets.isEmpty() || !targets.getFirst().isQuestion()) {
            throw new LearningException(422, "INVALID_HESITATION_TARGET", "망설임은 AI 질문 메시지에만 기록할 수 있습니다.");
        }
        jdbc.update("""
                INSERT INTO teachmi.learning_hesitation (id,session_id,target_message_id,reteach_id,waited_seconds,created_at)
                VALUES (?,?,?,?,?,?) ON CONFLICT (session_id,target_message_id) DO NOTHING
                """, UUID.randomUUID(), sessionId, request.targetMessageId(), targets.getFirst().reteachId(),
                request.waitedSeconds(), Timestamp.from(Instant.now().truncatedTo(ChronoUnit.MICROS)));
    }

    /** 망설임이 기록된 질문 메시지 ID. 호출하는 쪽이 학습 소유권을 먼저 확인해야 한다. */
    public List<UUID> targetMessageIds(UUID sessionId) {
        return jdbc.queryForList("""
                SELECT target_message_id FROM teachmi.learning_hesitation WHERE session_id=? ORDER BY created_at,id
                """, UUID.class, sessionId);
    }

    private record Target(String role, String kind, UUID reteachId) {
        boolean isQuestion() {
            return role.equals("assistant") && kind.endsWith("_question");
        }
    }
}
