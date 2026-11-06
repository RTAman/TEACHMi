package com.teachmi.backend.material;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import com.teachmi.backend.learning.ConceptSource;
import com.teachmi.backend.learning.LearningException;
import com.teachmi.backend.learning.LearningSourceReader;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * B의 학습 세션이 쓰는 A 연결: 회원 소유의 개념 스냅샷과 실제 원문.
 * 없거나 다른 회원의 자료·개념·원문 조각이면 LearningException.notFound()를 던진다.
 */
@Component
class MaterialLearningSources implements ConceptSource, LearningSourceReader {
    private final JdbcTemplate jdbc;

    MaterialLearningSources(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    private record OwnedConcept(String name, String scope) {}

    @Override
    @Transactional(readOnly = true)
    public Snapshot loadOwnedConcept(String memberId, String materialId, String conceptId) {
        var ids = ids(memberId, materialId, conceptId);
        // 새 학습은 분석이 끝난 자료의 최신 분석 결과에 있는 개념으로만 시작한다.
        var concept = jdbc.query("""
                SELECT c.name, c.scope FROM teachmi.concept c JOIN teachmi.material m ON m.id = c.material_id
                WHERE c.id = ? AND m.id = ? AND m.user_id = ? AND m.status = 'done'
                  AND c.analysis_version = m.analysis_version
                """, (rs, i) -> new OwnedConcept(rs.getString("name"), rs.getString("scope")),
                ids[2], ids[1], ids[0]).stream().findFirst().orElseThrow(LearningException::notFound);
        var chunks = jdbc.queryForList("""
                SELECT sc.id FROM teachmi.concept_source cs JOIN teachmi.source_chunk sc ON sc.id = cs.chunk_id
                WHERE cs.concept_id = ? ORDER BY sc.seq
                """, UUID.class, ids[2]).stream().map(UUID::toString).toList();
        if (chunks.isEmpty()) {
            throw LearningException.notFound();
        }
        return new Snapshot(concept.name(), concept.scope(), chunks);
    }

    @Override
    @Transactional(readOnly = true)
    public OwnedSource readOwned(String memberId, String materialId, String conceptId, List<String> chunkIds) {
        var ids = ids(memberId, materialId, conceptId);
        // 이미 만든 학습 세션은 재분석 뒤에도 당시 개념과 원문으로 계속할 수 있도록 버전은 확인하지 않는다.
        Integer owned = jdbc.queryForObject("""
                SELECT COUNT(*) FROM teachmi.concept c JOIN teachmi.material m ON m.id = c.material_id
                WHERE c.id = ? AND m.id = ? AND m.user_id = ?
                """, Integer.class, ids[2], ids[1], ids[0]);
        if (owned == null || owned == 0 || chunkIds == null || chunkIds.isEmpty()) {
            throw LearningException.notFound();
        }
        var linked = new LinkedHashMap<String, String>();
        jdbc.query("""
                SELECT sc.id, sc.text FROM teachmi.concept_source cs JOIN teachmi.source_chunk sc ON sc.id = cs.chunk_id
                WHERE cs.concept_id = ? AND sc.material_id = ?
                """, rs -> {
            linked.put(rs.getObject("id", UUID.class).toString(), rs.getString("text"));
        }, ids[2], ids[1]);
        var chunks = new ArrayList<Chunk>();
        for (String id : chunkIds) {
            String text = linked.get(id);
            if (text == null) {
                throw LearningException.notFound();
            }
            chunks.add(new Chunk(id, text));
        }
        return new OwnedSource(memberId, materialId, conceptId, chunks);
    }

    private static UUID[] ids(String memberId, String materialId, String conceptId) {
        var member = parse(memberId);
        var material = parse(materialId);
        var concept = parse(conceptId);
        if (member.isEmpty() || material.isEmpty() || concept.isEmpty()) {
            throw LearningException.notFound();
        }
        return new UUID[] {member.get(), material.get(), concept.get()};
    }

    private static Optional<UUID> parse(String value) {
        try {
            return value == null ? Optional.empty() : Optional.of(UUID.fromString(value));
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }
    }
}
