package com.teachmi.backend.learning;

import java.time.Instant;
import java.util.UUID;
import java.util.List;

public record SessionView(
        UUID sessionId, String materialId, String conceptId, ConceptSource.Snapshot concept,
        String mode, String stage, String status, long version, Instant createdAt, Instant updatedAt,
        Resume resume, LearningContentGenerator.Summary summary, String chatStage,
        List<LearningMessageView> messages, UUID initialAnswerId, UUID finalExplanationId,
        List<SupportEventView> supportEvents, TurnProgress pendingTurn, List<String> availableActions) {
    public SessionView {
        // Creation responses saved by the previous release have no messages property.
        messages = messages == null ? List.of() : List.copyOf(messages);
        supportEvents = supportEvents == null ? List.of() : List.copyOf(supportEvents);
        availableActions = availableActions == null ? List.of() : List.copyOf(availableActions);
    }
    public record Resume(String stage, String path) {}
}
