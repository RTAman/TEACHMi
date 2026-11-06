package com.teachmi.backend.material;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import com.teachmi.backend.auth.ApiException;
import com.teachmi.backend.material.MaterialViews.AddConceptRequest;
import com.teachmi.backend.material.MaterialViews.ConceptInput;
import com.teachmi.backend.material.MaterialViews.ConceptView;
import com.teachmi.backend.material.MaterialViews.CreateTextMaterialRequest;
import com.teachmi.backend.material.MaterialViews.MaterialView;
import com.teachmi.backend.material.MaterialViews.SourceView;
import com.teachmi.backend.material.MaterialViews.UpdateConceptRequest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 회원별 강의자료, 원문 조각, 개념 후보. 모든 조회·변경은 현재 회원 소유인지 먼저 확인한다. */
@Service
public class MaterialService {
    static final int CHUNK_TARGET_CHARS = 2000;

    private final JdbcTemplate jdbc;

    MaterialService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    record Created<T>(T value, boolean replayed) {}

    private record MaterialRow(UUID id, String sourceType, String title, String fileName, String status,
                               String errorCode, int analysisVersion, OffsetDateTime createdAt,
                               OffsetDateTime updatedAt) {}

    private record ChunkRow(UUID id, int seq, String kind, int number) {}

    private record ConceptRow(UUID id, String name, String scope, boolean custom) {}

    // ---------------------------------------------------------------- 직접 입력 자료

    @Transactional
    public Created<MaterialView> createText(UUID userId, String key, CreateTextMaterialRequest request) {
        String text = normalizeText(request.text());
        if (text.isBlank()) {
            throw ApiException.invalid("TEXT_REQUIRED", "자료 내용을 입력하세요.");
        }
        ConceptInput concept = request.concept();
        if (concept != null) {
            requireConcept(concept.name(), concept.scope());
        }
        String title = request.title() == null || request.title().isBlank() ? defaultTitle(text) : request.title().strip();
        String hash = hash("text", title, text, concept == null ? null : concept.name().strip(),
                concept == null ? null : concept.scope().strip());
        var replay = claimKey(userId, key, "create_material", hash);
        if (replay.isPresent()) {
            return new Created<>(load(userId, replay.get()), true);
        }

        var materialId = UUID.randomUUID();
        var now = now();
        jdbc.update("""
                INSERT INTO teachmi.material(id, user_id, source_type, title, status, analysis_version, created_at, updated_at)
                VALUES (?, ?, 'text', ?, 'done', 1, ?, ?)
                """, materialId, userId, title, now, now);
        var chunkIds = new ArrayList<UUID>();
        int seq = 1;
        for (String part : split(text)) {
            chunkIds.add(insertChunk(materialId, 1, seq, "input", seq, part));
            seq++;
        }
        if (concept != null) {
            insertConcept(materialId, 1, concept.name().strip(), concept.scope().strip(), true, 0, chunkIds);
        }
        recordKey(userId, key, "create_material", hash, materialId);
        return new Created<>(load(userId, materialId), false);
    }

    // ---------------------------------------------------------------- 조회

    @Transactional(readOnly = true)
    public MaterialView get(UUID userId, UUID materialId) {
        return load(userId, materialId);
    }

    // ---------------------------------------------------------------- 개념 추가·수정

    @Transactional
    public Created<ConceptView> addConcept(UUID userId, UUID materialId, String key, AddConceptRequest request) {
        requireConcept(request.name(), request.scope());
        var material = lockOwned(userId, materialId);
        if (!"done".equals(material.status())) {
            throw new ApiException(409, "MATERIAL_NOT_READY", "자료 분석이 끝난 뒤 개념을 추가할 수 있습니다.");
        }
        List<String> requested = request.sourceChunkIds() == null ? List.of() : request.sourceChunkIds();
        String evidence = request.evidenceText() == null ? "" : normalizeText(request.evidenceText());
        String hash = hash("concept", materialId.toString(), request.name().strip(), request.scope().strip(),
                evidence, String.join(",", requested));
        var replay = claimKey(userId, key, "add_concept", hash);
        if (replay.isPresent()) {
            return new Created<>(loadConcept(materialId, replay.get()), true);
        }

        var chunks = chunks(materialId, material.analysisVersion());
        List<UUID> sources;
        if (!requested.isEmpty()) {
            var owned = new LinkedHashMap<String, UUID>();
            chunks.forEach(chunk -> owned.put(chunk.id().toString(), chunk.id()));
            sources = new ArrayList<>();
            for (String id : new LinkedHashSet<>(requested)) {
                var chunk = owned.get(id);
                if (chunk == null) {
                    throw ApiException.invalid("INVALID_SOURCE", "이 자료에 없는 원문 조각입니다.");
                }
                sources.add(chunk);
            }
        } else if (!evidence.isBlank()) {
            int nextSeq = chunks.stream().mapToInt(ChunkRow::seq).max().orElse(0) + 1;
            int nextInput = (int) chunks.stream().filter(chunk -> chunk.kind().equals("input")).count() + 1;
            sources = List.of(insertChunk(materialId, material.analysisVersion(), nextSeq, "input", nextInput, evidence));
        } else if ("text".equals(material.sourceType())) {
            sources = chunks.stream().map(ChunkRow::id).toList();
        } else {
            throw ApiException.invalid("EVIDENCE_REQUIRED", "개념의 근거가 되는 원문을 고르거나 입력하세요.");
        }
        Integer minSeq = jdbc.queryForObject(
                "SELECT MIN(seq) FROM teachmi.concept WHERE material_id = ? AND analysis_version = ?",
                Integer.class, materialId, material.analysisVersion());
        int seq = minSeq == null ? 0 : minSeq - 1;
        var conceptId = insertConcept(materialId, material.analysisVersion(), request.name().strip(),
                request.scope().strip(), true, seq, sources);
        touch(materialId);
        recordKey(userId, key, "add_concept", hash, conceptId);
        return new Created<>(loadConcept(materialId, conceptId), false);
    }

    @Transactional
    public ConceptView updateConcept(UUID userId, UUID materialId, UUID conceptId, UpdateConceptRequest request) {
        boolean hasName = request.name() != null;
        boolean hasScope = request.scope() != null;
        if (!hasName && !hasScope) {
            throw ApiException.invalid("NOTHING_TO_UPDATE", "수정할 이름이나 범위를 입력하세요.");
        }
        if ((hasName && request.name().isBlank()) || (hasScope && request.scope().isBlank())) {
            throw ApiException.invalid("CONCEPT_REQUIRED", "개념 이름과 학습 범위를 입력하세요.");
        }
        var material = lockOwned(userId, materialId);
        int updated = jdbc.update("""
                UPDATE teachmi.concept SET name = COALESCE(?, name), scope = COALESCE(?, scope), custom = TRUE, updated_at = ?
                WHERE id = ? AND material_id = ? AND analysis_version = ?
                """, hasName ? request.name().strip() : null, hasScope ? request.scope().strip() : null, now(),
                conceptId, materialId, material.analysisVersion());
        if (updated == 0) {
            throw ApiException.notFound();
        }
        touch(materialId);
        return loadConcept(materialId, conceptId);
    }

    // ---------------------------------------------------------------- 내부 구현

    private MaterialView load(UUID userId, UUID materialId) {
        var material = findOwned(userId, materialId, false).orElseThrow(ApiException::notFound);
        var chunks = chunks(materialId, material.analysisVersion());
        var sources = chunks.stream().map(chunk -> new SourceView(chunk.id().toString(), chunk.seq(), chunk.kind(),
                chunk.number(), label(chunk.kind(), chunk.number(), chunk.number()))).toList();
        var concepts = jdbc.query("""
                SELECT id, name, scope, custom FROM teachmi.concept
                WHERE material_id = ? AND analysis_version = ? ORDER BY seq, created_at
                """, (rs, i) -> new ConceptRow(rs.getObject("id", UUID.class), rs.getString("name"),
                        rs.getString("scope"), rs.getBoolean("custom")),
                materialId, material.analysisVersion()).stream().map(row -> conceptView(row)).toList();
        return new MaterialView(materialId.toString(), material.sourceType(), material.title(), material.fileName(),
                material.status(), material.errorCode(), material.analysisVersion(), concepts, sources,
                material.createdAt().toInstant().toString(), material.updatedAt().toInstant().toString());
    }

    private ConceptView loadConcept(UUID materialId, UUID conceptId) {
        return jdbc.query("SELECT id, name, scope, custom FROM teachmi.concept WHERE id = ? AND material_id = ?",
                        (rs, i) -> new ConceptRow(rs.getObject("id", UUID.class), rs.getString("name"),
                                rs.getString("scope"), rs.getBoolean("custom")), conceptId, materialId)
                .stream().findFirst().map(this::conceptView).orElseThrow(ApiException::notFound);
    }

    private ConceptView conceptView(ConceptRow row) {
        var linked = jdbc.query("""
                SELECT sc.id, sc.seq, sc.location_kind, sc.location_number
                FROM teachmi.concept_source cs JOIN teachmi.source_chunk sc ON sc.id = cs.chunk_id
                WHERE cs.concept_id = ? ORDER BY sc.seq
                """, (rs, i) -> new ChunkRow(rs.getObject("id", UUID.class), rs.getInt("seq"),
                rs.getString("location_kind"), rs.getInt("location_number")), row.id());
        return new ConceptView(row.id().toString(), row.name(), row.scope(), row.custom(),
                linked.stream().map(chunk -> chunk.id().toString()).toList(), rangeLabel(linked));
    }

    private Optional<MaterialRow> findOwned(UUID userId, UUID materialId, boolean forUpdate) {
        return jdbc.query("""
                SELECT id, source_type, title, file_name, status, error_code, analysis_version, created_at, updated_at
                FROM teachmi.material WHERE id = ? AND user_id = ?
                """ + (forUpdate ? " FOR UPDATE" : ""), (rs, i) -> new MaterialRow(rs.getObject("id", UUID.class),
                rs.getString("source_type"), rs.getString("title"), rs.getString("file_name"), rs.getString("status"),
                rs.getString("error_code"), rs.getInt("analysis_version"),
                rs.getObject("created_at", OffsetDateTime.class), rs.getObject("updated_at", OffsetDateTime.class)),
                materialId, userId).stream().findFirst();
    }

    private MaterialRow lockOwned(UUID userId, UUID materialId) {
        return findOwned(userId, materialId, true).orElseThrow(ApiException::notFound);
    }

    private List<ChunkRow> chunks(UUID materialId, int version) {
        return jdbc.query("""
                SELECT id, seq, location_kind, location_number FROM teachmi.source_chunk
                WHERE material_id = ? AND analysis_version = ? ORDER BY seq
                """, (rs, i) -> new ChunkRow(rs.getObject("id", UUID.class), rs.getInt("seq"),
                rs.getString("location_kind"), rs.getInt("location_number")), materialId, version);
    }

    private UUID insertChunk(UUID materialId, int version, int seq, String kind, int number, String text) {
        var id = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO teachmi.source_chunk(id, material_id, analysis_version, seq, location_kind, location_number, text, created_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                """, id, materialId, version, seq, kind, number, text, now());
        return id;
    }

    private UUID insertConcept(UUID materialId, int version, String name, String scope, boolean custom, int seq,
                               List<UUID> chunkIds) {
        var id = UUID.randomUUID();
        var now = now();
        jdbc.update("""
                INSERT INTO teachmi.concept(id, material_id, analysis_version, name, scope, custom, seq, created_at, updated_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, id, materialId, version, name, scope, custom, seq, now, now);
        for (UUID chunk : chunkIds) {
            jdbc.update("INSERT INTO teachmi.concept_source(concept_id, chunk_id) VALUES (?, ?)", id, chunk);
        }
        return id;
    }

    private void touch(UUID materialId) {
        jdbc.update("UPDATE teachmi.material SET updated_at = ? WHERE id = ?", now(), materialId);
    }

    /**
     * 같은 회원·같은 키의 요청을 트랜잭션 잠금으로 직렬화한다. 이미 처리한 같은 요청이면 그 결과 ID를,
     * 다른 요청에 키를 재사용하면 409를 돌려준다.
     */
    private Optional<UUID> claimKey(UUID userId, String key, String operation, String hash) {
        if (key == null || key.isBlank() || key.length() > 128) {
            throw ApiException.invalid("IDEMPOTENCY_KEY_REQUIRED", "Idempotency-Key 헤더(1~128자)가 필요합니다.");
        }
        jdbc.queryForObject("SELECT pg_advisory_xact_lock(hashtextextended(?, 0))", Object.class,
                "material-request:" + userId + ":" + key);
        var existing = jdbc.query("""
                SELECT operation, request_hash, result_id FROM teachmi.material_request WHERE user_id = ? AND request_key = ?
                """, (rs, i) -> new Object[] {rs.getString("operation"), rs.getString("request_hash"),
                rs.getObject("result_id", UUID.class)}, userId, key);
        if (existing.isEmpty()) {
            return Optional.empty();
        }
        var row = existing.getFirst();
        if (!operation.equals(row[0]) || !hash.equals(row[1])) {
            throw new ApiException(409, "IDEMPOTENCY_KEY_REUSED", "이미 다른 요청에 사용한 Idempotency-Key입니다.");
        }
        return Optional.of((UUID) row[2]);
    }

    private void recordKey(UUID userId, String key, String operation, String hash, UUID resultId) {
        jdbc.update("""
                INSERT INTO teachmi.material_request(user_id, request_key, operation, request_hash, result_id, created_at)
                VALUES (?, ?, ?, ?, ?, ?)
                """, userId, key, operation, hash, resultId, now());
    }

    private static void requireConcept(String name, String scope) {
        if (name == null || name.isBlank() || scope == null || scope.isBlank()) {
            throw ApiException.invalid("CONCEPT_REQUIRED", "개념 이름과 학습 범위를 입력하세요.");
        }
    }

    static String normalizeText(String text) {
        return text == null ? "" : text.replace("\r\n", "\n").replace('\r', '\n').strip();
    }

    /** 빈 줄 기준 문단을 모아 약 2,000자 단위 원문 조각으로 나눈다. 긴 문단은 글자 수로 자른다. */
    static List<String> split(String text) {
        var parts = new ArrayList<String>();
        var current = new StringBuilder();
        for (String paragraph : text.split("\\n\\s*\\n")) {
            String p = paragraph.strip();
            if (p.isEmpty()) {
                continue;
            }
            if (current.length() > 0 && current.length() + 2 + p.length() > CHUNK_TARGET_CHARS) {
                parts.add(current.toString());
                current.setLength(0);
            }
            while (p.length() > CHUNK_TARGET_CHARS) {
                if (current.length() > 0) {
                    parts.add(current.toString());
                    current.setLength(0);
                }
                parts.add(p.substring(0, CHUNK_TARGET_CHARS));
                p = p.substring(CHUNK_TARGET_CHARS).strip();
            }
            if (!p.isEmpty()) {
                if (current.length() > 0) {
                    current.append("\n\n");
                }
                current.append(p);
            }
        }
        if (current.length() > 0) {
            parts.add(current.toString());
        }
        return parts;
    }

    private static String defaultTitle(String text) {
        String first = text.lines().map(String::strip).filter(line -> !line.isEmpty()).findFirst().orElse("직접 입력 자료");
        return first.length() > 40 ? first.substring(0, 40) : first;
    }

    static String label(String kind, int from, int to) {
        String range = from == to ? String.valueOf(from) : from + "~" + to;
        return switch (kind) {
            case "page" -> "p." + range;
            case "slide" -> "슬라이드 " + range;
            default -> "직접 입력 " + range;
        };
    }

    private static String rangeLabel(List<ChunkRow> linked) {
        if (linked.isEmpty()) {
            return null;
        }
        String kind = linked.getFirst().kind();
        if (linked.stream().allMatch(chunk -> chunk.kind().equals(kind))) {
            int min = linked.stream().mapToInt(ChunkRow::number).min().orElseThrow();
            int max = linked.stream().mapToInt(ChunkRow::number).max().orElseThrow();
            return label(kind, min, max);
        }
        return String.join(", ", linked.stream().map(chunk -> label(chunk.kind(), chunk.number(), chunk.number())).toList());
    }

    private static String hash(String... parts) {
        try {
            var digest = MessageDigest.getInstance("SHA-256");
            for (String part : parts) {
                digest.update((part == null ? "\u0000null" : part).getBytes(StandardCharsets.UTF_8));
                digest.update((byte) 0x1f);
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static OffsetDateTime now() {
        return OffsetDateTime.now(ZoneOffset.UTC);
    }
}
