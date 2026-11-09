package com.teachmi.backend.assessment;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class GeminiKeyPointEvaluatorTest {
    private final JsonMapper json = JsonMapper.builder().build();
    private static final String EVIDENCE = """
            "sourceChunkIds":["c1"],"evidence":[{"sourceChunkId":"c1","quote":"취득원가를 내용연수에"}]""";

    @Test
    void countsKeyPointsInTheInitialAndFinalExplanations() {
        var result = GeminiKeyPointEvaluator.validate(json.readTree("""
                {"keyPoints":[
                  {"point":"원가 배분","inInitial":true,"inFinal":true},
                  {"point":"내용연수","inInitial":false,"inFinal":true},
                  {"point":"수익·비용 대응","inInitial":false,"inFinal":true}],%s}
                """.formatted(EVIDENCE)));
        assertThat(result.total()).isEqualTo(3);
        assertThat(result.initialCount()).isEqualTo(1);
        assertThat(result.finalCount()).isEqualTo(3);
        assertThat(result.items().get(1).point()).isEqualTo("내용연수");
    }

    @Test
    void rejectsDuplicateOrMissingPointsAndMissingEvidence() {
        assertThatThrownBy(() -> GeminiKeyPointEvaluator.validate(json.readTree("""
                {"keyPoints":[{"point":"원가","inInitial":true,"inFinal":true},
                              {"point":"원가","inInitial":false,"inFinal":true}],%s}
                """.formatted(EVIDENCE)))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> GeminiKeyPointEvaluator.validate(json.readTree("""
                {"keyPoints":[{"point":"원가","inInitial":true,"inFinal":true}],%s}
                """.formatted(EVIDENCE)))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> GeminiKeyPointEvaluator.validate(json.readTree("""
                {"keyPoints":[{"point":"원가","inInitial":true,"inFinal":true},
                              {"point":"기간","inInitial":false,"inFinal":true}]}
                """))).isInstanceOf(RuntimeException.class);
    }
}
