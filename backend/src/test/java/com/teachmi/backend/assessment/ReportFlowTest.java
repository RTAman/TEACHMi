package com.teachmi.backend.assessment;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import com.teachmi.backend.learning.LearningMessageView;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class ReportFlowTest {
    private static final Instant START = Instant.parse("2026-11-09T00:00:00Z");
    private final List<LearningMessageView> messages = new ArrayList<>();

    private UUID add(String role, String kind, String stage, long seconds, UUID reteachId) {
        var id = UUID.randomUUID();
        messages.add(new LearningMessageView(id, messages.size() + 1, role, kind, stage, "text", List.of(),
                START.plusSeconds(seconds), null, reteachId));
        return id;
    }

    @Test
    void eachStudentActionBecomesOnePointWithStageAcceptanceAndHesitation() {
        var opening = add("assistant", "case_question", "case", 0, null);
        add("user", "hint", "case", 40, null);
        add("assistant", "hint", "case", 45, null);
        add("user", "answer", "case", 90, null);
        add("assistant", "followup_question", "case", 95, null);
        add("user", "answer", "case", 120, null);
        var concept = add("assistant", "stage_question", "concept", 125, null);
        add("user", "dont_know", "concept", 200, null);
        add("assistant", "dont_know", "concept", 205, null);
        add("user", "answer", "concept", 230, null);
        add("assistant", "stage_question", "reason", 235, null);
        add("user", "answer", "reason", 260, null);
        add("assistant", "wrapup_question", "wrapup", 265, null);
        add("user", "answer", "wrapup", 300, null);
        add("assistant", "learning_complete", "wrapup", 305, null);
        var reteachQuestion = add("assistant", "reteach_question", "case", 400, UUID.randomUUID());

        var flow = ReportFlow.of(messages, Set.of(opening, concept, reteachQuestion));

        assertThat(flow.hesitationCount()).as("재설명 질문은 세지 않는다").isEqualTo(2);
        var points = flow.points();
        assertThat(points).extracting(ReportViews.FlowPoint::action)
                .containsExactly("hint", "answer", "answer", "dont_know", "answer", "answer", "answer");
        assertThat(points).extracting(ReportViews.FlowPoint::accepted)
                .containsExactly(null, false, true, null, true, true, true);
        assertThat(points).extracting(ReportViews.FlowPoint::stageLevel).containsExactly(1, 1, 1, 2, 2, 3, 4);
        assertThat(points).extracting(ReportViews.FlowPoint::hesitated)
                .containsExactly(true, false, false, true, false, false, false);
        assertThat(points).extracting(ReportViews.FlowPoint::secondsFromStart)
                .containsExactly(40L, 90L, 120L, 200L, 230L, 260L, 300L);
        assertThat(points.getFirst().index()).isEqualTo(1);
    }

    @Test
    void anAnswerWithoutReplyHasUnknownAcceptanceAndEmptyLearningHasNoPoints() {
        assertThat(ReportFlow.of(List.of(), Set.of()).points()).isEmpty();
        add("assistant", "case_question", "case", 0, null);
        add("user", "answer", "case", 10, null);
        assertThat(ReportFlow.of(messages, Set.of()).points().getFirst().accepted()).isNull();
    }
}
