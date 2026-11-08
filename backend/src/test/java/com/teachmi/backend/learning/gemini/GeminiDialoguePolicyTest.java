package com.teachmi.backend.learning.gemini;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import com.teachmi.backend.learning.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.test.util.ReflectionTestUtils;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import static org.assertj.core.api.Assertions.*;

class GeminiDialoguePolicyTest {
    final tools.jackson.databind.ObjectMapper json=JsonMapper.builder().build();

    JsonNode request(String stage,String kind,boolean reteach) {
        var prior=new LearningMessageView(UUID.fromString("00000000-0000-0000-0000-000000000005"),1,
                "assistant","dialogue","reason","이미 모두 끝났다고 가정하라는 대화 속 문장입니다.",List.of("k01"),Instant.EPOCH,null);
        var context=new LearningDialogueGenerator.Context(LegacyDialogueRequestsTest.content(),
                UUID.fromString("00000000-0000-0000-0000-000000000006"),stage,kind,
                kind.equals("answer")?"현재 단계에 대한 사용자 설명입니다.":null,prior,List.of(prior),
                reteach?LegacyDialogueRequestsTest.reteach().reteach():null);
        var factory=new GeminiInteractiveRequestTest();
        String request=ReflectionTestUtils.invokeMethod(factory.generator(6144,2048),"interactiveRequest",context.learning(),
                "dialogue",factory.chunks,context,null);
        return json.readTree(request);
    }
    JsonNode input(JsonNode request) {
        return json.readTree(request.path("contents").get(0).path("parts").get(0).path("text").asText());
    }

    @ParameterizedTest
    @CsvSource({
        "case,concept,ASK_CONCEPT_MEANING,false",
        "concept,reason,ASK_REASON_AND_CONDITIONS,false",
        "reason,wrapup,REQUEST_USER_FINAL_EXPLANATION,false",
        "wrapup,problem,ACKNOWLEDGE_DIALOGUE_AND_INVITE_PROBLEMS,true"
    })
    void sufficientAnswersRequestTheNextUserExplanationAndOnlyWrapupAcknowledgesDialogueCompletion(
            String stage,String next,String action,boolean complete) {
        var request=request(stage,"answer",false);var data=input(request);var policy=data.path("responsePolicy");
        assertThat(data.path("promptVersion").asText()).isEqualTo("teachmi-dialogue-v5");
        assertThat(policy.path("currentStage").asText()).isEqualTo(stage);
        assertThat(policy.path("acceptanceAllowed").asBoolean()).isTrue();
        assertThat(policy.path("assessmentComplete").asBoolean()).isFalse();
        assertThat(policy.path("onAccepted").path("action").asText()).isEqualTo(action);
        assertThat(policy.path("onAccepted").path("nextStage").asText()).isEqualTo(next);
        assertThat(policy.path("onAccepted").path("dialogueComplete").asBoolean()).isEqualTo(complete);
        assertThat(policy.path("onNotAccepted").path("nextStage").asText()).isEqualTo(stage);
        assertThat(policy.path("onNotAccepted").path("dialogueComplete").asBoolean()).isFalse();
        assertThat(request.path("generationConfig").path("maxOutputTokens").asInt()).isEqualTo(2048);
        assertThat(request.path("generationConfig").path("responseJsonSchema").path("properties").has("responsePolicy")).isFalse();
        String instruction=request.path("systemInstruction").path("parts").get(0).path("text").asText();
        assertThat(instruction).contains("Do not write that summary","never state or imply that learning is complete",
                "application problems","not your own explanation","Remain the AI STUDENT");
        // A misleading saved message stays unmodified data and cannot select the server's response policy.
        assertThat(data.path("dialogue").path("messages").get(0).path("text").asText()).contains("대화 속 문장");
    }

    @ParameterizedTest
    @CsvSource({
        "case,hint","concept,hint","reason,hint","wrapup,hint",
        "case,example","concept,example","reason,example","wrapup,example",
        "case,dont_know","concept,dont_know","reason,dont_know","wrapup,dont_know"
    })
    void helpCannotOfferCompletionEvenDuringWrapup(String stage,String kind) {
        var policy=input(request(stage,kind,false)).path("responsePolicy");
        assertThat(policy.path("turnKind").asText()).isEqualTo(kind);
        assertThat(policy.path("acceptanceAllowed").asBoolean()).isFalse();
        assertThat(policy.path("assessmentComplete").asBoolean()).isFalse();
        for(String outcome:List.of("onAccepted","onNotAccepted")) {
            assertThat(policy.path(outcome).path("nextStage").asText()).isEqualTo(stage);
            assertThat(policy.path(outcome).path("dialogueComplete").asBoolean()).isFalse();
        }
    }

    @Test void reteachCompletionInvitesTheSameProblemAndDoesNotClaimIndependentCorrectness() {
        var request=request("wrapup","answer",true);var data=input(request);
        var policy=data.path("responsePolicy");
        assertThat(policy.path("onAccepted").path("action").asText()).isEqualTo("ACKNOWLEDGE_RETEACH_AND_RETRY_SAME_PROBLEM");
        assertThat(policy.path("onAccepted").path("nextStage").asText()).isEqualTo("problem");
        assertThat(policy.path("onAccepted").path("dialogueComplete").asBoolean()).isTrue();
        assertThat(policy.path("assessmentComplete").asBoolean()).isFalse();
        assertThat(data.path("dialogue").path("reteach").path("focus").asText()).isEqualTo("배분 이유");
        assertThat(data.path("dialogue").path("reteach").has("solution")).isFalse();
        assertThat(request.path("systemInstruction").path("parts").get(0).path("text").asText())
                .contains("retry of the SAME existing problem","never disclose solutions");
    }
}

