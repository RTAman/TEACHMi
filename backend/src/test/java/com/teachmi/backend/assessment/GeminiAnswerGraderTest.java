package com.teachmi.backend.assessment;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import com.teachmi.backend.ai.GeminiGenerationPort;
import com.teachmi.backend.learning.ConceptSource;
import com.teachmi.backend.learning.SessionView;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 실제 Gemini 대신 응답을 고정한 가짜 포트로 요청 내용과 판정 정리만 확인한다. */
class GeminiAnswerGraderTest {
    private final ObjectMapper json = JsonMapper.builder().build();
    private final UUID sessionId = UUID.randomUUID();
    private final UUID attemptId = UUID.randomUUID();

    private RemoteAnswerGrader.Input input() {
        var now = Instant.now();
        var concept = new ConceptSource.Snapshot("감가상각", "원가 배분", List.of("k01", "k02"));
        var session = new SessionView(sessionId, "m01", "c01", concept, "review", "problem", "active", 7, now, now,
                null, null, "wrapup", List.of(), null, null, List.of(), null, List.of(), null, null, List.of());
        return new RemoteAnswerGrader.Input("member-1", session, attemptId, "연간 감가상각비는?", "200만 원",
                "600만 원 ÷ 3년", "금액과 배분 이유", List.of("k01"), "200만원", "원가를 3년에 나눈다.");
    }

    private AnswerGrader.Grade grade(AtomicReference<GeminiGenerationPort.Request> seen, String response) {
        GeminiGenerationPort port = new GeminiGenerationPort() {
            @Override
            public <T> T generate(Request request, Validator<T> validator) {
                seen.set(request);
                return validator.validate(json.readTree(response));
            }
        };
        return new GeminiAnswerGrader(port, json).grade(input());
    }

    private static String response(String judgement, String focus) {
        return "{\"judgement\":\"%s\",\"reteachFocus\":\"%s\",\"reason\":\"이유\",\"sourceChunkIds\":[\"k01\"],\"evidence\":[]}"
                .formatted(judgement, focus);
    }

    @Test
    void sendsTheSessionScopeAndTheSavedAnswer() {
        var seen = new AtomicReference<GeminiGenerationPort.Request>();
        var grade = grade(seen, response("correct", ""));

        assertThat(grade.judgement()).isEqualTo("correct");
        assertThat(grade.reteachFocus()).isNull();
        var request = seen.get();
        assertThat(request.operation()).isEqualTo("assessment/grade");
        assertThat(request.scope().memberId()).isEqualTo("member-1");
        assertThat(request.scope().sessionId()).isEqualTo(sessionId);
        assertThat(request.scope().sourceChunkIds()).containsExactly("k01", "k02");
        assertThat(request.input().path("attemptId").asText()).isEqualTo(attemptId.toString());
        assertThat(request.input().path("answer").asText()).isEqualTo("200만원");
        assertThat(request.schema().path("properties").has("evidence")).as("근거 필드는 포트가 추가한다").isFalse();
    }

    @Test
    void theSameAttemptAlwaysSendsTheSameInput() {
        var first = new AtomicReference<GeminiGenerationPort.Request>();
        var second = new AtomicReference<GeminiGenerationPort.Request>();
        grade(first, response("correct", ""));
        grade(second, response("correct", ""));
        assertThat(first.get().input()).isEqualTo(second.get().input());
    }

    @Test
    void aWrongAnswerKeepsTheFocus() {
        var grade = grade(new AtomicReference<>(), response("wrong", "원가를 사용 기간에 나누는 이유를 생각해 보세요."));
        assertThat(grade.judgement()).isEqualTo("wrong");
        assertThat(grade.reteachFocus()).isEqualTo("원가를 사용 기간에 나누는 이유를 생각해 보세요.");
    }

    @Test
    void aFocusThatRevealsTheAnswerOrIsEmptyIsReplaced() {
        assertThat(grade(new AtomicReference<>(), response("wrong", "정답은 200만 원입니다.")).reteachFocus())
                .isEqualTo(GeminiAnswerGrader.SAFE_FOCUS);
        assertThat(grade(new AtomicReference<>(), response("wrong", " ")).reteachFocus())
                .isEqualTo(GeminiAnswerGrader.SAFE_FOCUS);
    }

    @Test
    void pendingReviewHasNoFocus() {
        var grade = grade(new AtomicReference<>(), response("pending_review", "무시된다"));
        assertThat(grade.judgement()).isEqualTo("pending_review");
        assertThat(grade.reteachFocus()).isNull();
    }

    @Test
    void anUnknownJudgementIsRejected() {
        assertThatThrownBy(() -> grade(new AtomicReference<>(), response("maybe", "")))
                .isInstanceOf(IllegalArgumentException.class);
    }
}