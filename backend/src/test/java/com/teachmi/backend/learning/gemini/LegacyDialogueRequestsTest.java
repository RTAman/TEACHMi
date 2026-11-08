package com.teachmi.backend.learning.gemini;

import java.nio.file.*;
import java.util.*;
import java.time.*;
import com.teachmi.backend.learning.*;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import tools.jackson.databind.json.JsonMapper;
import static org.assertj.core.api.Assertions.*;

/** Golden v1 requests were exported from f5cb9f6 without executing a provider or using user documents. */
class LegacyDialogueRequestsTest {
    static LearningContentGenerator.Context content() {
        return new LearningContentGenerator.Context("legacy-member",UUID.fromString("00000000-0000-0000-0000-000000000001"),"m01","c01","review",GeminiFixtures.concept(),null);
    }
    static LearningDialogueGenerator.Context dialogue() {
        var question=new LearningMessageView(UUID.fromString("00000000-0000-0000-0000-000000000002"),1,"assistant","case_question","case","구입비를 첫해에 모두 비용 처리할까요?",List.of("k01"),Instant.parse("2026-01-01T00:00:00Z"),null);
        return new LearningDialogueGenerator.Context(content(),UUID.fromString("00000000-0000-0000-0000-000000000003"),"wrapup","answer","원가를 사용 기간에 배분합니다.",question,List.of(question));
    }
    static LearningReteachGenerator.Context reteach() { return new LearningReteachGenerator.Context(content(),new ReteachFocus(UUID.fromString("00000000-0000-0000-0000-000000000004"),"배분 이유")); }
    @Test void historicalSuccessLookupUsesByteIdenticalDialogueAndReteachRequests() throws Exception {
        var json=JsonMapper.builder().build();
        var settings=new GeminiSettings("fake-key","test-model",Duration.ofSeconds(3),Duration.ofSeconds(20),10,100,20,2,16000,2048);
        var generator=new GeminiContentGenerator((GeminiGenerationService)null,settings,json);
        var chunks=List.of(new LearningSourceReader.Chunk("k01",GeminiFixtures.SOURCE));
        String oldDialogue=ReflectionTestUtils.invokeMethod(generator,"request",content(),"dialogue",chunks,dialogue(),null,false);
        String oldReteach=ReflectionTestUtils.invokeMethod(generator,"request",content(),"reteach_question",chunks,null,reteach(),false);
        assertThat(oldDialogue).isEqualTo(Files.readString(Path.of("src/test/resources/gemini-legacy/dialogue-v1.json")));
        assertThat(oldReteach).isEqualTo(Files.readString(Path.of("src/test/resources/gemini-legacy/reteach-v1.json")));
        String updated=ReflectionTestUtils.invokeMethod(generator,"request",content(),"dialogue",chunks,dialogue(),null,true);
        String v3Dialogue=ReflectionTestUtils.invokeMethod(generator,"request",content(),"dialogue",chunks,dialogue(),null,3);
        String v3Reteach=ReflectionTestUtils.invokeMethod(generator,"request",content(),"reteach_question",chunks,null,reteach(),3);
        String savedQuestion=Files.readString(Path.of("src/test/resources/gemini-legacy/question-v1.json"));
        var oldData=json.readTree(json.readTree(savedQuestion).path("contents").get(0).path("parts").get(0).path("text").asText());
        var oldOrder=oldData.properties().stream().map(java.util.Map.Entry::getKey).toList();
        String v1Question=ReflectionTestUtils.invokeMethod(generator,"historicalContentRequest",content(),"question",chunks,oldOrder);
        assertThat(v3Dialogue).isEqualTo(Files.readString(Path.of("src/test/resources/gemini-legacy/dialogue-v3.json")));
        assertThat(v3Reteach).isEqualTo(Files.readString(Path.of("src/test/resources/gemini-legacy/reteach-v3.json")));
        String v4Dialogue=ReflectionTestUtils.invokeMethod(generator,"interactiveRequest",content(),"dialogue",chunks,dialogue(),null,4);
        String v4Reteach=ReflectionTestUtils.invokeMethod(generator,"interactiveRequest",content(),"reteach_question",chunks,null,reteach());
        String v2Question=ReflectionTestUtils.invokeMethod(generator,"interactiveRequest",content(),"question",chunks,null,null);
        assertThat(v4Dialogue).isEqualTo(Files.readString(Path.of("src/test/resources/gemini-legacy/dialogue-v4.json")));
        assertThat(v4Reteach).isEqualTo(Files.readString(Path.of("src/test/resources/gemini-legacy/reteach-v4.json")));
        assertThat(v2Question).isEqualTo(Files.readString(Path.of("src/test/resources/gemini-legacy/question-v2.json")));
        assertThat(v1Question).isEqualTo(savedQuestion);
        List<List<String>> orders=ReflectionTestUtils.invokeMethod(generator,"legacyDataOrders");
        assertThat(orders).hasSize(24).contains(oldOrder);
        assertThat(orders.stream().distinct()).hasSize(24);
        String v2Reteach=ReflectionTestUtils.invokeMethod(generator,"request",content(),"reteach_question",chunks,null,reteach(),true);
        assertThat(updated).isEqualTo(Files.readString(Path.of("src/test/resources/gemini-legacy/dialogue-v2.json")));
        assertThat(v2Reteach).isEqualTo(Files.readString(Path.of("src/test/resources/gemini-legacy/reteach-v2.json")));
        var request=json.readTree(updated);var schema=request.path("generationConfig").path("responseJsonSchema").path("properties");
        assertThat(schema.path("sourceChunkIds").path("maxItems").asInt()).isEqualTo(1);
        assertThat(schema.path("evidence").path("items").path("properties").path("quote").path("maxLength").asInt()).isEqualTo(40);
        assertThat(updated).contains("teachmi-dialogue-v2","contiguous 6-40", "Do not paraphrase");
    }
}
