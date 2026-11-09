package com.teachmi.backend.assessment;

import java.time.OffsetDateTime;
import java.util.UUID;
import com.teachmi.backend.assessment.ReportViews.Change;
import com.teachmi.backend.assessment.ReportViews.Comparison;
import com.teachmi.backend.assessment.ReportViews.ReportContent;
import com.teachmi.backend.learning.LearningEvidenceView.SupportCounts;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * 리포트의 "지난 학습과의 비교"를 만든다.
 * 지난 학습은 같은 회원이 같은 자료·같은 개념으로 이번 학습보다 먼저 시작해 리포트까지 받은 학습 중 가장 최근 것이다.
 * 리포트를 열 때마다 계산하고 저장하지 않는다. 저장된 리포트 본문은 그대로 두고, 이미 만들어진 리포트에도 비교가 붙는다.
 */
@Component
class ReportComparisons {
    private final JdbcTemplate jdbc;
    private final ObjectMapper json;

    ReportComparisons(JdbcTemplate jdbc, ObjectMapper json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    /** 처음 학습하는 개념이면 null이다. 프론트는 이때 비교 카드를 숨긴다. */
    Comparison of(String memberId, UUID sessionId, ReportContent current) {
        // B의 learning_session은 읽기만 한다. 자료·개념·시작 시각으로 같은 개념의 바로 전 학습을 찾는다.
        var previous = jdbc.query("""
                SELECT prev.id, prev.created_at, report.content
                FROM teachmi.learning_session cur
                JOIN teachmi.learning_session prev
                  ON prev.member_id = cur.member_id
                 AND prev.material_id = cur.material_id
                 AND prev.concept_id = cur.concept_id
                 AND prev.created_at < cur.created_at
                JOIN teachmi.assessment_report report
                  ON report.session_id = prev.id AND report.member_id = cur.member_id
                WHERE cur.id = ? AND cur.member_id = ?
                ORDER BY prev.created_at DESC
                LIMIT 1
                """, (rs, row) -> new Previous(rs.getObject("id", UUID.class),
                rs.getObject("created_at", OffsetDateTime.class), rs.getString("content")), sessionId, memberId);
        if (previous.isEmpty()) {
            return null;
        }
        var before = previous.getFirst();
        // 지난 리포트는 예전 형식일 수 있어 필요한 숫자만 꺼내 읽는다. 값이 없으면 0으로 본다.
        var support = json.readTree(before.content()).path("explanation").path("requestedSupport");
        var now = current.explanation().requestedSupport();
        return new Comparison(before.sessionId(), before.createdAt().toInstant(),
                // 핵심 요소 판정 데이터가 아직 없어 비워 둔다. 데이터가 생기면 같은 자리에 채운다.
                null,
                new Change(hints(support), hints(now)),
                new Change(support.path("dontKnow").asInt(0), now == null ? 0 : now.dontKnow()));
    }

    /** 기획 정의: 힌트 사용 횟수는 힌트 보기와 쉬운 예시 요청을 합친 수다. */
    private static int hints(JsonNode support) {
        return support.path("hint").asInt(0) + support.path("example").asInt(0);
    }

    private static int hints(SupportCounts support) {
        return support == null ? 0 : support.hint() + support.example();
    }

    private record Previous(UUID sessionId, OffsetDateTime createdAt, String content) {}
}