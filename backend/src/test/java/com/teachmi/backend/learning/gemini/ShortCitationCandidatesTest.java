package com.teachmi.backend.learning.gemini;

import java.util.List;
import java.time.Duration;
import com.teachmi.backend.learning.LearningSourceReader;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import tools.jackson.databind.json.JsonMapper;
import static org.assertj.core.api.Assertions.*;

class ShortCitationCandidatesTest {
    @Test void candidatesAreShortLiteralContiguousExcerptsIncludingWhitespaceAndUnicode() {
        String source="정액법 공식: (취득원가 - 잔존가치) ÷ 내용연수.\r\n  다른 조건에서도 같은 원리를 적용합니다. "+"😀".repeat(50);
        var chunks=List.of(new LearningSourceReader.Chunk("s1",source));
        var candidates=ShortCitationCandidates.from(chunks);
        assertThat(candidates).isNotEmpty().isEqualTo(ShortCitationCandidates.from(chunks));
        for(var candidate:candidates) {
            assertThat(candidate.sourceChunkId()).isEqualTo("s1");
            assertThat(candidate.quote().length()).isBetween(6,40);
            assertThat(source).contains(candidate.quote());
            assertThat(Character.isLowSurrogate(candidate.quote().charAt(0))).isFalse();
            assertThat(Character.isHighSurrogate(candidate.quote().charAt(candidate.quote().length()-1))).isFalse();
        }
        assertThat(chunks.getFirst().text()).isEqualTo(source);
    }
    @Test void candidateBudgetIsBoundedAndShortFragmentsAreNotInvented() {
        assertThat(ShortCitationCandidates.from(List.of(new LearningSourceReader.Chunk("s","짧음")))).isEmpty();
        var chunks=java.util.stream.IntStream.range(0,100).mapToObj(i -> new LearningSourceReader.Chunk("s"+i,"자료의 원문 그대로 복사합니다. ".repeat(100))).toList();
        assertThat(ShortCitationCandidates.from(chunks)).hasSize(64);
    }
    @Test void v3OffersLiteralQuoteEnumsAndLeavesHistoricalV2RequestsUntouched() {
        var json=JsonMapper.builder().build();
        var settings=new GeminiSettings("fake-key","test-model",Duration.ofSeconds(3),Duration.ofSeconds(20),10,100,20,2,16000,2048);
        var generator=new GeminiContentGenerator((GeminiGenerationService)null,settings,json);
        var chunks=List.of(new LearningSourceReader.Chunk("k01",GeminiFixtures.SOURCE));
        String v2=ReflectionTestUtils.invokeMethod(generator,"request",LegacyDialogueRequestsTest.content(),"dialogue",chunks,LegacyDialogueRequestsTest.dialogue(),null,true);
        String v3=ReflectionTestUtils.invokeMethod(generator,"request",LegacyDialogueRequestsTest.content(),"dialogue",chunks,LegacyDialogueRequestsTest.dialogue(),null,3);
        var request=json.readTree(v3);var data=json.readTree(request.path("contents").get(0).path("parts").get(0).path("text").asText());
        assertThat(data.path("promptVersion").asText()).isEqualTo("teachmi-dialogue-v3");
        var quotes=request.path("generationConfig").path("responseJsonSchema").path("properties").path("evidence").path("items").path("properties").path("quote").path("enum");
        assertThat(quotes.isEmpty()).isFalse();
        for(var quote:quotes) {assertThat(quote.asText().length()).isBetween(6,40);assertThat(GeminiFixtures.SOURCE).contains(quote.asText());}
        assertThat(data.path("citationCandidates").size()).isEqualTo(quotes.size());
        assertThat(v2).contains("teachmi-dialogue-v2").doesNotContain("citationCandidates");
    }

    @Test void candidateOverheadDoesNotRejectAnAlreadyAdmissibleLegacyContext() {
        var json=JsonMapper.builder().build();
        var settings=new GeminiSettings("fake-key","test-model",Duration.ofSeconds(3),Duration.ofSeconds(20),10,100,20,2,16000,2048);
        var generator=new GeminiContentGenerator((GeminiGenerationService)null,settings,json);
        var chunks=List.of(new LearningSourceReader.Chunk("k01",GeminiFixtures.SOURCE));
        var original=LegacyDialogueRequestsTest.dialogue();
        String base=ReflectionTestUtils.invokeMethod(generator,"request",original.learning(),"dialogue",chunks,original,null,true);
        int size=json.readTree(base).path("contents").get(0).path("parts").get(0).path("text").asText().length();
        String text=original.text()+"x".repeat(settings.maxSourceChars()+48000-size-2);
        var large=new com.teachmi.backend.learning.LearningDialogueGenerator.Context(original.learning(),original.turnId(),
                original.chatStage(),original.kind(),text,original.targetQuestion(),original.messages());
        String v2=ReflectionTestUtils.invokeMethod(generator,"request",large.learning(),"dialogue",chunks,large,null,true);
        String v3=ReflectionTestUtils.invokeMethod(generator,"request",large.learning(),"dialogue",chunks,large,null,3);
        var oldData=json.readTree(json.readTree(v2).path("contents").get(0).path("parts").get(0).path("text").asText());
        String newData=json.readTree(v3).path("contents").get(0).path("parts").get(0).path("text").asText();
        assertThat(newData.length()).isLessThanOrEqualTo(settings.maxSourceChars()+48000);
        assertThat(json.readTree(newData).path("dialogue")).isEqualTo(oldData.path("dialogue"));
        assertThat(json.readTree(newData).has("citationCandidates")).isFalse();
    }
}
