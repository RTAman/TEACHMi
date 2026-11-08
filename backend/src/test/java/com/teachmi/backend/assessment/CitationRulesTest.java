package com.teachmi.backend.assessment;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 프롬프트에 적은 출처 표기 규칙이 실제로 지켜지는지 경계값으로 확인한다. */
class CitationRulesTest {
    private final ObjectMapper json = JsonMapper.builder().build();

    private JsonNode response(String sourceChunkIds, String evidence) {
        return json.readTree("{\"sourceChunkIds\":" + sourceChunkIds + ",\"evidence\":" + evidence + "}");
    }

    private JsonNode quoted(String quote) {
        return response("[\"k01\"]", "[{\"sourceChunkId\":\"k01\",\"quote\":\"" + quote + "\"}]");
    }

    @ParameterizedTest
    @ValueSource(ints = {6, 40})
    void quotesOfSixAndFortyCharactersAreAccepted(int length) {
        assertThat(CitationRules.check(quoted("가".repeat(length)), 2)).containsExactly("k01");
    }

    @ParameterizedTest
    @ValueSource(ints = {5, 41})
    void quotesOfFiveAndFortyOneCharactersAreRejected(int length) {
        assertThatThrownBy(() -> CitationRules.check(quoted("가".repeat(length)), 2))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("6~40자");
    }

    @Test
    void symbolsAndSpacesCountAsOneCharacterEach() {
        // "(취득원가 - 잔존가치) ÷ 내용연수"는 20자다.
        assertThat(CitationRules.check(quoted("(취득원가 - 잔존가치) ÷ 내용연수"), 2)).containsExactly("k01");
    }

    @Test
    void sourceCountsUpToTheLimitAreAccepted() {
        var two = response("[\"k01\",\"k02\"]", """
                [{"sourceChunkId":"k01","quote":"취득원가를 내용연수에"},{"sourceChunkId":"k02","quote":"정액법은 매년 같은"}]""");
        assertThat(CitationRules.check(two, 2)).containsExactly("k01", "k02");
    }

    @Test
    void sourceCountsAboveTheLimitAreRejected() {
        var two = response("[\"k01\",\"k02\"]", """
                [{"sourceChunkId":"k01","quote":"취득원가를 내용연수에"},{"sourceChunkId":"k02","quote":"정액법은 매년 같은"}]""");
        assertThatThrownBy(() -> CitationRules.check(two, 1))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("1개까지");
    }

    @Test
    void noSourcesAreRejected() {
        assertThatThrownBy(() -> CitationRules.check(response("[]", "[]"), 2))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("출처가 없습니다");
    }

    @Test
    void aDuplicatedSourceIdIsRejected() {
        var duplicated = response("[\"k01\",\"k01\"]", """
                [{"sourceChunkId":"k01","quote":"취득원가를 내용연수에"},{"sourceChunkId":"k01","quote":"정액법은 매년 같은"}]""");
        assertThatThrownBy(() -> CitationRules.check(duplicated, 2)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void twoEvidenceForOneSourceAreRejected() {
        var doubled = response("[\"k01\"]", """
                [{"sourceChunkId":"k01","quote":"취득원가를 내용연수에"},{"sourceChunkId":"k01","quote":"정액법은 매년 같은"}]""");
        assertThatThrownBy(() -> CitationRules.check(doubled, 2))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("정확히 1개");
    }

    @Test
    void aSourceWithoutEvidenceIsRejectedEvenWhenTheCountMatches() {
        // 개수는 2개로 맞지만 k01에 두 개, k02에는 없다.
        var shifted = response("[\"k01\",\"k02\"]", """
                [{"sourceChunkId":"k01","quote":"취득원가를 내용연수에"},{"sourceChunkId":"k01","quote":"정액법은 매년 같은"}]""");
        assertThatThrownBy(() -> CitationRules.check(shifted, 2))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("정확히 1개");
    }

    @Test
    void evidenceForAnUncitedSourceIsRejected() {
        var uncited = response("[\"k01\"]", "[{\"sourceChunkId\":\"k02\",\"quote\":\"정액법은 매년 같은\"}]");
        assertThatThrownBy(() -> CitationRules.check(uncited, 2)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void missingEvidenceIsRejected() {
        assertThatThrownBy(() -> CitationRules.check(response("[\"k01\"]", "[]"), 2))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("정확히 1개");
    }
}