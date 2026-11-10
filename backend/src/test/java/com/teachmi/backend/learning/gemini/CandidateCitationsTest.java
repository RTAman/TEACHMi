package com.teachmi.backend.learning.gemini;

import java.util.List;
import java.util.Map;
import com.teachmi.backend.learning.LearningSourceReader.Chunk;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CandidateCitationsTest {
    static final ObjectMapper JSON = JsonMapper.builder().build();
    // ASCII keeps candidate boundaries independent of the platform's source encoding.
    static final List<Chunk> CHUNKS = List.of(
            new Chunk("k01", "Depreciation allocates the cost of an asset over its useful life. "
                    + "The straight-line method expenses the same amount each year."),
            new Chunk("k02", "The declining-balance method applies a fixed rate to the opening book value."));

    @Test
    void candidateIdsResolveToTheLiteralQuoteAndItsOwnSourceAcrossChunks() {
        var all = ShortCitationCandidates.from(CHUNKS);
        int fromSecond = indexOf(all, "k02");
        var resolved = CandidateCitations.resolve(json(Map.of("text", "Could you explain the declining-balance method?",
                "citations", List.of(CandidateCitations.id(fromSecond)))), CHUNKS, JSON);

        assertThat(resolved.has("citations")).isFalse();
        assertThat(resolved.path("sourceChunkIds").get(0).asText()).isEqualTo("k02");
        assertThat(resolved.path("evidence").get(0).path("sourceChunkId").asText()).isEqualTo("k02");
        assertThat(CHUNKS.get(1).text()).contains(resolved.path("evidence").get(0).path("quote").asText());
        // 결과는 기존과 같은 정확한 부분 문자열 검사를 그대로 통과한다.
        GroundedSources.citations(resolved, CHUNKS);
    }

    @Test
    void theOldLiteralFormatCouldPairAQuoteWithTheWrongChunk() {
        var all = ShortCitationCandidates.from(CHUNKS);
        String quoteFromSecond = all.get(indexOf(all, "k02")).quote();
        // 수정 전 방식에서 모델이 k02의 문장에 k01을 붙인 경우: 검증은 그대로 거절한다.
        var mismatched = json(Map.of("text", "x", "sourceChunkIds", List.of("k01"),
                "evidence", List.of(Map.of("sourceChunkId", "k01", "quote", quoteFromSecond))));
        assertThatThrownBy(() -> GroundedSources.citations(CandidateCitations.resolve(mismatched, CHUNKS, JSON), CHUNKS))
                .isInstanceOf(GeminiResponseFailure.class).hasMessageContaining("EVIDENCE_QUOTE_NOT_EXACT");
    }

    @Test
    void twoCandidatesFromOneChunkCiteThatChunkOnce() {
        var all = ShortCitationCandidates.from(CHUNKS);
        var first = all.stream().filter(candidate -> candidate.sourceChunkId().equals("k01")).limit(2).toList();
        assertThat(first).hasSize(2);
        var resolved = CandidateCitations.resolve(json(Map.of("text", "x",
                "citations", List.of(CandidateCitations.id(all.indexOf(first.get(0))), CandidateCitations.id(all.indexOf(first.get(1)))))),
                CHUNKS, JSON);
        assertThat(resolved.path("sourceChunkIds")).hasSize(1);
        assertThat(resolved.path("evidence")).hasSize(1);
        assertThat(resolved.path("evidence").get(0).path("quote").asText()).isEqualTo(first.get(0).quote());
        GroundedSources.citations(resolved, CHUNKS);
    }

    @Test
    void invalidSelectionsAreRejectedNotRepaired() {
        assertFailure(Map.of("text", "x", "citations", List.of("c99")), "EVIDENCE_CANDIDATE_INVALID");
        assertFailure(Map.of("text", "x", "citations", List.of("k01")), "EVIDENCE_CANDIDATE_INVALID");
        assertFailure(Map.of("text", "x", "citations", List.of("c01", "c01")), "CITATION_ID_DUPLICATE");
        assertFailure(Map.of("text", "x", "citations", List.of("c01", "c02", "c03")), "CITATION_CARDINALITY_INVALID");
        assertFailure(Map.of("text", "x", "citations", List.of()), "CITATION_CARDINALITY_INVALID");
        assertFailure(Map.of("text", "x", "citations", List.of("c01"), "sourceChunkIds", List.of("k01")),
                "EVIDENCE_STRUCTURE_INVALID");
    }

    @Test
    void legacyFormatPassesThroughToTheUnchangedValidation() {
        var legacy = json(Map.of("text", "x", "sourceChunkIds", List.of("k01"),
                "evidence", List.of(Map.of("sourceChunkId", "k01", "quote", "cost of an asset"))));
        assertThat(CandidateCitations.resolve(legacy, CHUNKS, JSON)).isSameAs(legacy);
    }

    @Test
    void offeredCandidatesAreNumberedInSourceOrder() {
        var all = ShortCitationCandidates.from(CHUNKS);
        var offered = CandidateCitations.offered(all, all.size());
        assertThat(offered.getFirst()).containsEntry("id", "c01").containsEntry("sourceChunkId", "k01");
        assertThat(CandidateCitations.ids(3)).containsExactly("c01", "c02", "c03");
        assertThatThrownBy(() -> CandidateCitations.candidates(List.of(new Chunk("k01", "tiny"))))
                .hasMessageContaining("인용 후보");
    }

    private static void assertFailure(Map<String, Object> payload, String code) {
        assertThatThrownBy(() -> CandidateCitations.resolve(json(payload), CHUNKS, JSON))
                .isInstanceOf(GeminiResponseFailure.class).hasMessageContaining(code);
    }

    private static int indexOf(List<ShortCitationCandidates.Candidate> all, String chunkId) {
        for (int i = 0; i < all.size(); i++) {
            if (all.get(i).sourceChunkId().equals(chunkId)) {
                return i;
            }
        }
        throw new AssertionError("no candidate for " + chunkId);
    }

    private static JsonNode json(Map<String, Object> value) {
        return JSON.valueToTree(value);
    }
}
