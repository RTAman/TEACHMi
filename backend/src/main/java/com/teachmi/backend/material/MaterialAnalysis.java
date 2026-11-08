package com.teachmi.backend.material;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.task.TaskExecutor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 업로드한 파일의 분석: 텍스트 추출 → 원문 조각 저장 → 개념 후보 생성.
 * 분석마다 analysis_version을 쓰고, 결과를 저장할 때 자료의 현재 버전과 상태를 다시 확인한다.
 * 재시도 뒤에 이전 분석이 늦게 끝나도 그 결과는 버린다.
 */
@Component
class MaterialAnalysis {
    private static final Logger log = LoggerFactory.getLogger(MaterialAnalysis.class);
    private static final int MIN_TEXT_CHARS = 20;

    private final JdbcTemplate jdbc;
    private final TransactionTemplate transactions;
    private final TaskExecutor executor;

    MaterialAnalysis(JdbcTemplate jdbc, PlatformTransactionManager transactionManager,
                     @Qualifier("materialAnalysisExecutor") TaskExecutor executor) {
        this.jdbc = jdbc;
        this.transactions = new TransactionTemplate(transactionManager);
        this.executor = executor;
    }

    private record Job(MaterialFormat format, byte[] content) {}

    /** 현재 트랜잭션이 커밋된 뒤에 분석을 시작한다. 롤백되면 시작하지 않는다. */
    void enqueueAfterCommit(UUID materialId, int version) {
        Runnable start = () -> executor.execute(() -> analyze(materialId, version));
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    start.run();
                }
            });
        } else {
            start.run();
        }
    }

    /** 서버가 다시 켜지면 분석 중이던 자료를 이어서 처리한다. */
    @EventListener(ApplicationReadyEvent.class)
    void resumePending() {
        jdbc.query("SELECT id, analysis_version FROM teachmi.material WHERE status = 'analyzing' AND source_type = 'file'",
                rs -> {
                    var id = rs.getObject("id", UUID.class);
                    int version = rs.getInt("analysis_version");
                    executor.execute(() -> analyze(id, version));
                });
    }

    void analyze(UUID materialId, int version) {
        try {
            var job = transactions.execute(status -> load(materialId, version));
            if (job == null) {
                return;
            }
            List<MaterialTextExtractor.Page> pages;
            try {
                pages = MaterialTextExtractor.extract(job.format(), job.content());
            } catch (MaterialTextExtractor.UnreadableFile e) {
                finish(materialId, version, "format", List.of());
                return;
            }
            int textChars = pages.stream().mapToInt(page -> page.text().replaceAll("\\s", "").length()).sum();
            if (textChars < MIN_TEXT_CHARS) {
                finish(materialId, version, "no_text", List.of());
                return;
            }
            finish(materialId, version, null, pages);
        } catch (RuntimeException e) {
            // 원문·파일 내용은 로그에 남기지 않는다.
            log.warn("Material analysis failed: material={} version={} error={}", materialId, version,
                    e.getClass().getSimpleName());
            try {
                finish(materialId, version, "analysis", List.of());
            } catch (RuntimeException ignored) {
                log.warn("Could not record material analysis failure: material={}", materialId);
            }
        }
    }

    private Job load(UUID materialId, int version) {
        var rows = jdbc.query("""
                SELECT f.format, f.content FROM teachmi.material m JOIN teachmi.material_file f ON f.material_id = m.id
                WHERE m.id = ? AND m.status = 'analyzing' AND m.analysis_version = ?
                """, (rs, i) -> new Job(MaterialFormat.fromCode(rs.getString("format")), rs.getBytes("content")),
                materialId, version);
        return rows.isEmpty() ? null : rows.getFirst();
    }

    /** 자료를 잠그고 버전·상태가 그대로일 때만 결과를 저장한다. */
    private void finish(UUID materialId, int version, String errorCode, List<MaterialTextExtractor.Page> pages) {
        transactions.executeWithoutResult(status -> {
            var current = jdbc.query("""
                    SELECT analysis_version FROM teachmi.material WHERE id = ? AND status = 'analyzing' FOR UPDATE
                    """, (rs, i) -> rs.getInt(1), materialId);
            if (current.isEmpty() || current.getFirst() != version) {
                return;
            }
            var now = OffsetDateTime.now(ZoneOffset.UTC);
            if (errorCode != null) {
                jdbc.update("UPDATE teachmi.material SET status = 'failed', error_code = ?, updated_at = ? WHERE id = ?",
                        errorCode, now, materialId);
                return;
            }
            var sources = new ArrayList<ConceptCandidates.Source>();
            var chunkIds = new HashMap<Object, UUID>();
            int seq = 1;
            for (var page : pages) {
                var chunkId = UUID.randomUUID();
                jdbc.update("""
                        INSERT INTO teachmi.source_chunk(id, material_id, analysis_version, seq, location_kind, location_number, text, created_at)
                        VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                        """, chunkId, materialId, version, seq++, page.kind(), page.number(), page.text(), now);
                sources.add(new ConceptCandidates.Source(chunkId, page.title(), page.text()));
                chunkIds.put(chunkId, chunkId);
            }
            int order = 1;
            for (var candidate : ConceptCandidates.from(sources)) {
                var conceptId = UUID.randomUUID();
                jdbc.update("""
                        INSERT INTO teachmi.concept(id, material_id, analysis_version, name, scope, custom, seq, created_at, updated_at)
                        VALUES (?, ?, ?, ?, ?, FALSE, ?, ?, ?)
                        """, conceptId, materialId, version, candidate.name(), candidate.scope(), order++, now, now);
                for (Object sourceId : candidate.sourceIds()) {
                    jdbc.update("INSERT INTO teachmi.concept_source(concept_id, chunk_id) VALUES (?, ?)",
                            conceptId, chunkIds.get(sourceId));
                }
            }
            jdbc.update("UPDATE teachmi.material SET status = 'done', error_code = NULL, updated_at = ? WHERE id = ?",
                    now, materialId);
        });
    }
}
