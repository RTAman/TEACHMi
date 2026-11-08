package com.teachmi.backend.learning.gemini;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import com.teachmi.backend.ai.GeminiGenerationPort.*;
import com.teachmi.backend.learning.ConceptSource;
import com.teachmi.backend.learning.LearningSourceReader;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import tools.jackson.databind.json.JsonMapper;
import static org.assertj.core.api.Assertions.*;

/** Synthetic, pre-change common requests protect existing C keys without storing real source data. */
class CommonRequestCompatibilityTest {
    @Test void standardProblemAndGradingRequestsKeepTheirHistoricalBytes() throws Exception {
        var json=JsonMapper.builder().build();
        var settings=new GeminiSettings("fake-key","test-model",Duration.ofSeconds(3),Duration.ofSeconds(40),10,100,20,2,16000,6144);
        var service=new GeminiGenerationService(settings,null,null,null,json);
        var scope=new Scope("member-fixture",UUID.fromString("10000000-0000-0000-0000-000000000001"),"material-fixture","concept-fixture",List.of("s02","s01"));
        var concept=new ConceptSource.Snapshot("감가상각","정액법",scope.sourceChunkIds());
        var chunks=List.of(new LearningSourceReader.Chunk("s01","취득원가를 내용연수에 걸쳐 배분한다."),
                new LearningSourceReader.Chunk("s02","정액법은 매년 같은 비용을 인식한다."));
        for(String operation:List.of("assessment/problems","assessment/grade")) {
            var request=new Request(scope,operation,"v4","원문에 근거하여 판단하세요.",json.valueToTree(Map.of("answer","합성 답변","version",7)),
                    json.readTree("{\"type\":\"object\",\"properties\":{\"result\":{\"type\":\"string\"}},\"required\":[\"result\"],\"additionalProperties\":false}"));
            String actual=ReflectionTestUtils.invokeMethod(service,"request",request,chunks,concept);
            String name="common-"+operation.substring(operation.indexOf('/')+1)+"-v4.json";
            try(var stream=getClass().getResourceAsStream("/gemini/"+name)) {
                assertThat(stream).isNotNull();
                assertThat(actual).isEqualTo(new String(stream.readAllBytes(),java.nio.charset.StandardCharsets.UTF_8));
            }
        }
    }
}
