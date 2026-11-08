package com.teachmi.backend.learning.gemini;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;
import static org.assertj.core.api.Assertions.*;

class ShortCitationPolicyTest {
    private final tools.jackson.databind.ObjectMapper json=JsonMapper.builder().build();

    @Test void aQuoteFromADifferentCandidateIdIsRejectedEvenWhenItIsLiteralInBothSources() {
        String quote="원가를 사용 기간에 배분한다.";
        var candidates=List.of(new ShortCitationCandidates.Candidate("s1",quote),
                new ShortCitationCandidates.Candidate("s2","별도 원문에서 선택한 후보입니다."));
        var response=json.valueToTree(Map.of("evidence",List.of(Map.of("sourceChunkId","s2","quote",quote))));
        var failure=catchThrowableOfType(() -> ShortCitationPolicy.validate(response,candidates),GeminiResponseFailure.class);
        assertThat(failure.diagnostic).isEqualTo("EVIDENCE_CANDIDATE_INVALID");
    }

    @Test void whitespaceAndMathSymbolsMustRemainExactlyAsTheyWereOffered() {
        String quote="(취득원가 - 잔존가치) ÷ 내용연수";
        var candidates=List.of(new ShortCitationCandidates.Candidate("s1",quote));
        var exact=json.valueToTree(Map.of("evidence",List.of(Map.of("sourceChunkId","s1","quote",quote))));
        assertThatCode(() -> ShortCitationPolicy.validate(exact,candidates)).doesNotThrowAnyException();
        var changed=json.valueToTree(Map.of("evidence",List.of(Map.of("sourceChunkId","s1","quote",quote.replace(" ÷ ","/")))));
        assertThatThrownBy(() -> ShortCitationPolicy.validate(changed,candidates)).isInstanceOf(GeminiResponseFailure.class);
    }

    @Test void quoteChoiceBudgetIsBoundedWithoutMutatingTheDomainSchema() {
        var full=json.readTree("""
                {"type":"object","properties":{
                "sourceChunkIds":{"type":"array","minItems":1,"maxItems":2,"items":{"type":"string","enum":["s1","s2"]}},
                "evidence":{"type":"array","minItems":1,"items":{"type":"object","additionalProperties":false,
                "properties":{"sourceChunkId":{"type":"string"},"quote":{"type":"string","minLength":6}},
                "required":["sourceChunkId","quote"]}}},"required":["sourceChunkIds","evidence"],"additionalProperties":false}
                """);
        var candidates=List.of(new ShortCitationCandidates.Candidate("s1","원가를 사용 기간에 배분한다."));
        var schema=ShortCitationPolicy.schema(full,candidates,json);
        JsonResponseSchema.check(schema);
        assertThat(full.path("properties").path("evidence").path("items").path("properties").path("quote").has("enum")).isFalse();
        assertThat(schema.path("properties").path("sourceChunkIds").path("items").path("enum")).hasSize(1);
        assertThat(schema.path("properties").path("sourceChunkIds").path("maxItems").asInt()).isEqualTo(1);
        assertThat(schema.path("properties").path("evidence").path("maxItems").asInt()).isEqualTo(1);
    }
}
