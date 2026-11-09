package com.teachmi.backend.assessment;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import com.teachmi.backend.assessment.ReportViews.Flow;
import com.teachmi.backend.assessment.ReportViews.FlowPoint;
import com.teachmi.backend.learning.LearningMessageView;

/** 저장된 메시지와 망설임 기록만으로 처음 학습 대화의 흐름을 만든다. DB·AI를 쓰지 않는다. */
final class ReportFlow {
    private static final Set<String> ACCEPTED_REPLIES = Set.of("stage_question", "wrapup_question", "learning_complete");

    private ReportFlow() {}

    static Flow of(List<LearningMessageView> allMessages, Collection<UUID> hesitationTargets) {
        var messages = allMessages.stream().filter(message -> message.reteachId() == null)
                .sorted(java.util.Comparator.comparingLong(LearningMessageView::sequence)).toList();
        var hesitated = new HashSet<>(hesitationTargets);
        int hesitationCount = (int) messages.stream().filter(message -> hesitated.contains(message.messageId())).count();
        if (messages.isEmpty()) return new Flow(hesitationCount, List.of());

        var start = messages.getFirst().createdAt();
        var points = new ArrayList<FlowPoint>();
        UUID question = null;
        boolean questionAnswered = false;
        for (int index = 0; index < messages.size(); index++) {
            var message = messages.get(index);
            if (message.role().equals("assistant")) {
                if (message.kind().endsWith("_question")) {
                    question = message.messageId();
                    questionAnswered = false;
                }
                continue;
            }
            if (!message.role().equals("user")) continue;
            Boolean accepted = null;
            if (message.kind().equals("answer")) accepted = acceptedBy(messages, index);
            // 같은 질문에 여러 번 행동했다면 망설임은 첫 행동에만 표시한다.
            boolean hesitatedHere = question != null && !questionAnswered && hesitated.contains(question);
            questionAnswered = true;
            points.add(new FlowPoint(points.size() + 1, message.chatStage(), level(message.chatStage()), message.kind(),
                    accepted, Math.max(0, Duration.between(start, message.createdAt()).toSeconds()), hesitatedHere));
        }
        return new Flow(hesitationCount, List.copyOf(points));
    }

    /** 다음 AI 메시지로 판정한다. 아직 응답이 없으면 null이다. */
    private static Boolean acceptedBy(List<LearningMessageView> messages, int userIndex) {
        for (int index = userIndex + 1; index < messages.size(); index++) {
            var reply = messages.get(index);
            if (reply.role().equals("user")) return null;
            if (reply.role().equals("assistant")) return ACCEPTED_REPLIES.contains(reply.kind());
        }
        return null;
    }

    private static int level(String chatStage) {
        if (chatStage == null) return 0;
        return switch (chatStage) {
            case "case" -> 1;
            case "concept" -> 2;
            case "reason" -> 3;
            case "wrapup" -> 4;
            default -> 0;
        };
    }
}
