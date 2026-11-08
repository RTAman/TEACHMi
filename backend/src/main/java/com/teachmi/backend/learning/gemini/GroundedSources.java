package com.teachmi.backend.learning.gemini;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import com.teachmi.backend.ai.GeminiGenerationPort.Scope;
import com.teachmi.backend.learning.LearningException;
import com.teachmi.backend.learning.LearningSourceReader;
import tools.jackson.databind.JsonNode;

final class GroundedSources {
    private GroundedSources() {}
    static List<LearningSourceReader.Chunk> verify(Scope scope, LearningSourceReader.OwnedSource raw, int maxChars) {
        if (raw == null || !scope.memberId().equals(raw.memberId())
                || !scope.materialId().equals(raw.materialId()) || !scope.conceptId().equals(raw.conceptId())) {
            throw LearningException.notFound();
        }
        if (raw.chunks() == null || raw.chunks().isEmpty()) throw incomplete("선택 개념의 원문이 준비되지 않았습니다.");
        var ids = new HashSet<String>();
        long length = 0;
        for (var chunk : raw.chunks()) {
            if (chunk == null || chunk.id() == null || chunk.text() == null || chunk.text().isBlank()
                    || !ids.add(chunk.id())) throw incomplete("원문 조각을 확인할 수 없습니다.");
            length += chunk.text().length();
        }
        if (!ids.equals(new HashSet<>(scope.sourceChunkIds()))) throw incomplete("개념의 출처와 원문 목록이 다릅니다.");
        if (length > maxChars) throw new LearningException(422,"LEARNING_SOURCE_TOO_LARGE","선택한 원문의 범위를 줄여 주세요.");
        return raw.chunks().stream().sorted(java.util.Comparator.comparing(LearningSourceReader.Chunk::id)).toList();
    }

    static void citations(JsonNode result, List<LearningSourceReader.Chunk> chunks) {
        var texts = new HashMap<String,String>();
        chunks.forEach(chunk -> texts.put(chunk.id(),chunk.text()));
        var cited = new HashSet<String>();
        var ids = result.path("sourceChunkIds");
        if (!result.isObject() || !ids.isArray() || ids.isEmpty()) throw new GeminiResponseFailure("CITATION_IDS_MISSING");
        for (var id:ids) {
            if (!id.isString() || !texts.containsKey(id.asText())) throw new GeminiResponseFailure("CITATION_ID_INVALID");
            if (!cited.add(id.asText())) throw new GeminiResponseFailure("CITATION_ID_DUPLICATE");
        }
        var evidence = result.path("evidence");
        if (!evidence.isArray() || evidence.isEmpty()) throw new GeminiResponseFailure("EVIDENCE_MISSING");
        var grounded = new HashSet<String>();
        for (var entry:evidence) {
            var id=entry.path("sourceChunkId"); var quote=entry.path("quote");
            if (!entry.isObject() || entry.size()!=2 || !id.isString() || !quote.isString())
                throw new GeminiResponseFailure("EVIDENCE_STRUCTURE_INVALID");
            if (!cited.contains(id.asText())) throw new GeminiResponseFailure("EVIDENCE_ID_UNCITED");
            if (quote.asText().length()<6) throw new GeminiResponseFailure("EVIDENCE_QUOTE_TOO_SHORT");
            if (!texts.get(id.asText()).contains(quote.asText())) throw new GeminiResponseFailure("EVIDENCE_QUOTE_NOT_EXACT");
            grounded.add(id.asText());
        }
        if (!grounded.equals(cited)) throw new GeminiResponseFailure("EVIDENCE_COVERAGE_MISMATCH");
    }
    private static LearningException incomplete(String message) {
        return new LearningException(503,"LEARNING_SOURCE_INCOMPLETE",message);
    }
}
