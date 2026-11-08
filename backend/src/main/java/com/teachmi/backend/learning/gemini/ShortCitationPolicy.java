package com.teachmi.backend.learning.gemini;

import java.util.List;
import com.teachmi.backend.learning.LearningException;
import com.teachmi.backend.learning.LearningSourceReader;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

/** Opt-in contract; standard common requests and B's historical request composers remain unchanged. */
final class ShortCitationPolicy {
    static List<ShortCitationCandidates.Candidate> candidates(List<LearningSourceReader.Chunk> chunks) {
        var candidates=ShortCitationCandidates.from(chunks);
        if(candidates.isEmpty())
            throw new LearningException(503,"LEARNING_SOURCE_INCOMPLETE","선택 원문에서 6~40자의 정확한 인용 후보를 찾을 수 없습니다.");
        return candidates;
    }

    static JsonNode schema(JsonNode full,List<ShortCitationCandidates.Candidate> candidates,ObjectMapper json) {
        var schema=(ObjectNode)full.deepCopy();
        var ids=candidates.stream().map(ShortCitationCandidates.Candidate::sourceChunkId).distinct().sorted().toList();
        var quotes=candidates.stream().map(ShortCitationCandidates.Candidate::quote).distinct().toList();
        var sources=(ObjectNode)schema.path("properties").path("sourceChunkIds");
        sources.put("maxItems",ids.size());
        ((ObjectNode)sources.path("items")).set("enum",json.valueToTree(ids));
        var evidence=(ObjectNode)schema.path("properties").path("evidence");
        evidence.put("maxItems",ids.size());
        var properties=evidence.path("items").path("properties");
        ((ObjectNode)properties.path("sourceChunkId")).set("enum",json.valueToTree(ids));
        var quote=(ObjectNode)properties.path("quote");
        quote.put("maxLength",40);
        quote.set("enum",json.valueToTree(quotes));
        return schema;
    }

    /** Exact grounding is checked first by GroundedSources; never trim or repair the model's citation. */
    static void validate(JsonNode response,List<ShortCitationCandidates.Candidate> candidates) {
        for(var evidence:response.path("evidence")) {
            String id=evidence.path("sourceChunkId").asText();
            String quote=evidence.path("quote").asText();
            if(quote.length()>40) throw new GeminiResponseFailure("EVIDENCE_QUOTE_TOO_LONG");
            if(candidates.stream().noneMatch(candidate -> candidate.sourceChunkId().equals(id) && candidate.quote().equals(quote)))
                throw new GeminiResponseFailure("EVIDENCE_CANDIDATE_INVALID");
        }
    }

    private ShortCitationPolicy() {}
}
