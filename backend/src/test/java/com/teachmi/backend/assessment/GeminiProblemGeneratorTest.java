package com.teachmi.backend.assessment;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import com.teachmi.backend.ai.GeminiGenerationPort;
import com.teachmi.backend.learning.ConceptSource;
import com.teachmi.backend.learning.LearningMessageView;
import com.teachmi.backend.learning.SessionView;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 실제 Gemini 대신 응답을 고정한 가짜 포트로 요청 내용과 응답 검증만 확인한다. */
class GeminiProblemGeneratorTest {
    private final ObjectMapper json = JsonMapper.builder().build();
    private final UUID sessionId = UUID.randomUUID();
    private final UUID question = UUID.randomUUID();
    private final UUID last = UUID.randomUUID();

    private SessionView session() {
        var concept = new ConceptSource.Snapshot("감가상각", "원가 배분", List.of("k01", "k02"));
        var now = Instant.now();
        var messages = List.of(
                new LearningMessageView(question, 1, "assistant", "question", "case",
                        "3년 쓰는 컴퓨터의 구입비를 첫해에 모두 비용 처리할까요?", List.of("k01"), now, null),
                new LearningMessageView(last, 2, "user", "answer", "wrapup", "원가를 사용 기간에 나눕니다.", List.of(), now, null));
        return new SessionView(sessionId, "m01", "c01", concept, "review", "problem", "active", 7, now, now,
                null, null, "wrapup", messages, null, last, List.of(), null, List.of(), null, null, List.of());
    }

    private GeminiProblemGenerator using(AtomicReference<GeminiGenerationPort.Request> seen, String response) {
        GeminiGenerationPort port = new GeminiGenerationPort() {
            @Override
            public <T> T generate(Request request, Validator<T> validator) {
                seen.set(request);
                return validator.validate(json.readTree(response));
            }
        };
        return new GeminiProblemGenerator(port, json);
    }

    private static String problem(String question, String answer) {
        return """
                {"question":"%s","changedConditions":"대상을 기계로 바꿈","expectedAnswer":"%s",
                 "solution":"원가를 내용연수로 나눈다.","gradingCriteria":"금액과 배분 이유"}""".formatted(question, answer);
    }

    private static String response(String... problems) {
        return "{\"problems\":[" + String.join(",", problems) + "],\"sourceChunkIds\":[\"k01\"],\"evidence\":[]}";
    }

    @Test
    void sendsTheSessionScopeAndReturnsGroundedDrafts() {
        var seen = new AtomicReference<GeminiGenerationPort.Request>();
        var drafts = using(seen, response(problem("취득원가 600만 원인 기계의 연간 감가상각비는?", "200만 원"),
                problem("잔존가치 100만 원인 차량의 연간 감가상각비는?", "180만 원"))).generate("member-1", session());

        assertThat(drafts).hasSize(2);
        assertThat(drafts.getFirst().sourceChunkIds()).containsExactly("k01");
        assertThat(drafts.getFirst().sourcesJson()).isEqualTo("[\"k01\"]");
        var request = seen.get();
        assertThat(request.scope().memberId()).isEqualTo("member-1");
        assertThat(request.scope().sessionId()).isEqualTo(sessionId);
        assertThat(request.scope().sourceChunkIds()).containsExactly("k01", "k02");
        assertThat(request.operation()).isEqualTo("assessment/problems");
        assertThat(request.input().path("caseQuestion").asText()).contains("컴퓨터");
        assertThat(request.input().path("finalExplanation").asText()).isEqualTo("원가를 사용 기간에 나눕니다.");
        assertThat(request.schema().path("properties").has("sourceChunkIds")).as("출처 필드는 포트가 추가한다").isFalse();
    }

    @Test
    void theSameSessionAlwaysSendsTheSameInput() {
        var first = new AtomicReference<GeminiGenerationPort.Request>();
        var second = new AtomicReference<GeminiGenerationPort.Request>();
        var body = response(problem("문제 하나입니다. 값을 구하세요.", "1"), problem("문제 둘입니다. 값을 구하세요.", "2"));
        using(first, body).generate("member-1", session());
        using(second, body).generate("member-1", session());
        assertThat(first.get().input()).as("재시도 때 저장된 결과를 다시 쓰려면 입력이 같아야 한다")
                .isEqualTo(second.get().input());
    }

    @Test
    void rejectsAWrongNumberOfProblems() {
        var generator = using(new AtomicReference<>(), response(problem("문제 하나입니다. 값을 구하세요.", "1")));
        assertThatThrownBy(() -> generator.generate("member-1", session())).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsDuplicatedQuestions() {
        var same = problem("같은 문제입니다. 값을 구하세요.", "1");
        assertThatThrownBy(() -> using(new AtomicReference<>(), response(same, same)).generate("member-1", session()))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsBlankFields() {
        var blank = response(problem("문제 하나입니다. 값을 구하세요.", " "), problem("문제 둘입니다. 값을 구하세요.", "2"));
        assertThatThrownBy(() -> using(new AtomicReference<>(), blank).generate("member-1", session()))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void theSchemaUsesOnlyKeysThePortSupports() {
        JsonNode schema = new GeminiProblemGenerator(null, json).schema();
        assertThat(schema.toString()).doesNotContain("$ref", "pattern", "oneOf", "anyOf");
    }
}