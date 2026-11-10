package com.teachmi.backend.learning.gemini;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import com.teachmi.backend.learning.LearningException;
import com.teachmi.backend.learning.LearningSourceReader;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * B 생성 요청의 인용을 "후보 번호 선택" 방식으로 받는다.
 *
 * <p>모델은 인용문이나 출처 ID를 직접 쓰지 않고 서버가 제시한 후보 번호(c01…)만 고른다.
 * 서버는 그 번호의 후보, 즉 소유권이 확인된 원문에서 그대로 잘라 낸 6~40자 구절과 그 구절의 출처 ID를
 * sourceChunkIds·evidence로 붙인다. 따라서 모델이 구절을 옮겨 적다 생기는 공백·기호 차이나
 * 다른 조각의 ID와 짝이 어긋나는 오류(EVIDENCE_QUOTE_NOT_EXACT)가 생기지 않는다.
 * 결과는 기존과 똑같이 GroundedSources의 정확한 부분 문자열 검사를 통과해야 하며 검증은 완화하지 않는다.
 */
final class CandidateCitations {
    static final String FIELD = "citations";
    static final int MAX_CITATIONS = 2;

    private CandidateCitations() {}

    static String id(int index) {
        return "c%02d".formatted(index + 1);
    }

    /** 원문에서 결정적으로 뽑은 후보. 비어 있으면 생성 호출 전에 중단한다. */
    static List<ShortCitationCandidates.Candidate> candidates(List<LearningSourceReader.Chunk> chunks) {
        var candidates = ShortCitationCandidates.from(chunks);
        if (candidates.isEmpty()) {
            throw new LearningException(503, "LEARNING_SOURCE_INCOMPLETE", "선택 원문에서 6~40자의 정확한 인용 후보를 찾을 수 없습니다.");
        }
        return candidates;
    }

    /** 요청에 넣을 앞쪽 count개의 후보. 번호는 전체 목록의 순서와 같다. */
    static List<Map<String, String>> offered(List<ShortCitationCandidates.Candidate> all, int count) {
        var offered = new ArrayList<Map<String, String>>();
        for (int i = 0; i < count; i++) {
            var candidate = all.get(i);
            var entry = new LinkedHashMap<String, String>();
            entry.put("id", id(i));
            entry.put("quote", candidate.quote());
            entry.put("sourceChunkId", candidate.sourceChunkId());
            offered.add(entry);
        }
        return offered;
    }

    static List<String> ids(int count) {
        var ids = new ArrayList<String>();
        for (int i = 0; i < count; i++) {
            ids.add(id(i));
        }
        return ids;
    }

    /**
     * citations 형식의 응답을 sourceChunkIds·evidence 형식으로 바꾼다.
     * 이전 형식(sourceChunkIds·evidence만 있는 응답)은 그대로 두고 기존 검증에 맡긴다.
     * 두 형식이 섞여 있으면 거절한다.
     */
    static JsonNode resolve(JsonNode raw, List<LearningSourceReader.Chunk> chunks, ObjectMapper json) {
        if (raw == null || !raw.isObject() || !raw.has(FIELD)) {
            return raw;
        }
        if (raw.has("sourceChunkIds") || raw.has("evidence")) {
            throw new GeminiResponseFailure("EVIDENCE_STRUCTURE_INVALID");
        }
        var citations = raw.path(FIELD);
        if (!citations.isArray() || citations.isEmpty() || citations.size() > MAX_CITATIONS) {
            throw new GeminiResponseFailure("CITATION_CARDINALITY_INVALID");
        }
        var all = ShortCitationCandidates.from(chunks);
        var chosen = new HashSet<String>();
        var sourceIds = new LinkedHashSet<String>();
        var evidence = json.createArrayNode();
        for (var citation : citations) {
            String id = citation.isString() ? citation.asText() : "";
            int index = index(id);
            if (index < 0 || index >= all.size()) {
                throw new GeminiResponseFailure("EVIDENCE_CANDIDATE_INVALID");
            }
            if (!chosen.add(id)) {
                throw new GeminiResponseFailure("CITATION_ID_DUPLICATE");
            }
            var candidate = all.get(index);
            // 같은 조각의 후보를 두 개 골라도 출처는 하나, 근거는 그 조각의 첫 선택 구절로 기록한다.
            if (sourceIds.add(candidate.sourceChunkId())) {
                var entry = json.createObjectNode();
                entry.put("sourceChunkId", candidate.sourceChunkId());
                entry.put("quote", candidate.quote());
                evidence.add(entry);
            }
        }
        var resolved = (ObjectNode) raw.deepCopy();
        resolved.remove(FIELD);
        var ids = json.createArrayNode();
        sourceIds.forEach(ids::add);
        resolved.set("sourceChunkIds", ids);
        resolved.set("evidence", evidence);
        return resolved;
    }

    private static int index(String id) {
        if (!id.matches("c[0-9]{2}")) {
            return -1;
        }
        return Integer.parseInt(id.substring(1)) - 1;
    }
}
