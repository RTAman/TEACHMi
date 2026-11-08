package com.teachmi.backend.learning.gemini;

import java.time.Duration;
import java.util.List;
import com.teachmi.backend.learning.LearningSourceReader;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import static org.assertj.core.api.Assertions.*;

class GeminiInteractiveRequestTest {
    final tools.jackson.databind.ObjectMapper json=JsonMapper.builder().build();
    GeminiContentGenerator generator(int global,int interactive) {
        var settings=new GeminiSettings("fake-key","test-model",Duration.ofSeconds(3),Duration.ofSeconds(40),10,100,20,2,16000,global);
        return new GeminiContentGenerator(null,settings,json,interactive);
    }
    final List<LearningSourceReader.Chunk> chunks=List.of(new LearningSourceReader.Chunk("k01",GeminiFixtures.SOURCE));
    String request(GeminiContentGenerator generator,String kind) {
        return ReflectionTestUtils.invokeMethod(generator,"interactiveRequest",LegacyDialogueRequestsTest.content(),kind,chunks,
                kind.equals("dialogue")?LegacyDialogueRequestsTest.dialogue():null,
                kind.equals("reteach_question")?LegacyDialogueRequestsTest.reteach():null);
    }
    JsonNode data(String request) {return json.readTree(json.readTree(request).path("contents").get(0).path("parts").get(0).path("text").asText());}
    @Test void initialQuestionUsesShortLiteralCandidatesAndStrictSchema() {
        var request=request(generator(6144,2048),"question");var tree=json.readTree(request);var data=data(request);
        assertThat(data.path("promptVersion").asText()).isEqualTo("teachmi-question-v2");
        assertThat(data.path("citationCandidates").isEmpty()).isFalse();
        var quote=tree.path("generationConfig").path("responseJsonSchema").path("properties").path("evidence").path("items").path("properties").path("quote");
        assertThat(quote.path("maxLength").asInt()).isEqualTo(40);
        assertThat(quote.path("enum").isEmpty()).isFalse();
        for(var candidate:data.path("citationCandidates")) {
            assertThat(candidate.path("quote").asText().length()).isBetween(6,40);
            assertThat(GeminiFixtures.SOURCE).contains(candidate.path("quote").asText());
        }
        assertThat(tree.path("generationConfig").path("maxOutputTokens").asInt()).isEqualTo(2048);
        assertCanonical(tree);assertCanonical(data);
    }
    @Test void compactDialogueRetainsEveryTextAndSelectionWithoutDuplicatingTheLearningContext() {
        var generator=generator(6144,2048);
        String old=ReflectionTestUtils.invokeMethod(generator,"request",LegacyDialogueRequestsTest.content(),"dialogue",chunks,LegacyDialogueRequestsTest.dialogue(),null,3);
        String compact=request(generator,"dialogue");var before=data(old);var after=data(compact);
        assertThat(after.path("promptVersion").asText()).isEqualTo("teachmi-dialogue-v4");
        assertThat(after.path("context")).isEqualTo(before.path("context"));
        assertThat(after.path("dialogue").has("learning")).isFalse();
        for(String field:List.of("turnId","kind","text","chatStage","targetQuestion"))
            assertThat(after.path("dialogue").path(field)).isEqualTo(before.path("dialogue").path(field));
        var a=after.path("dialogue").path("messages");var b=before.path("dialogue").path("messages");
        assertThat(a.size()).isEqualTo(b.size());
        for(int i=0;i<a.size();i++) for(String field:List.of("role","kind","chatStage","text"))
            assertThat(a.get(i).path(field)).isEqualTo(b.get(i).path(field));
        assertThat(after.path("lectureExcerpts")).isEqualTo(before.path("lectureExcerpts"));
        assertThat(compact.length()).isLessThan(old.length());
        assertThat(compact).isEqualTo(request(generator,"dialogue"));
        assertCanonical(json.readTree(compact));assertCanonical(after);
    }
    @Test void reteachKeepsItsFocusAndGlobalCapAlwaysWins() {
        var request=request(generator(1024,2048),"reteach_question");
        assertThat(data(request).path("promptVersion").asText()).isEqualTo("teachmi-reteach-v4");
        assertThat(data(request).path("reteach").path("focus").asText()).isEqualTo("배분 이유");
        assertThat(json.readTree(request).path("generationConfig").path("maxOutputTokens").asInt()).isEqualTo(1024);
    }
    @Test void configuredInteractiveCapCannotRaiseGlobalLimitOrAcceptInvalidValues() {
        assertThat(json.readTree(request(generator(6144,4096),"dialogue")).path("generationConfig").path("maxOutputTokens").asInt()).isEqualTo(4096);
        assertThatThrownBy(() -> generator(6144,255)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> generator(6144,8193)).isInstanceOf(IllegalArgumentException.class);
    }
    void assertCanonical(JsonNode node) {
        if(node.isObject()) {
            var names=new java.util.ArrayList<String>();node.properties().forEach(entry -> {names.add(entry.getKey());assertCanonical(entry.getValue());});
            assertThat(names).containsExactlyElementsOf(names.stream().sorted().toList());
        } else if(node.isArray()) for(var item:node)assertCanonical(item);
    }
}
