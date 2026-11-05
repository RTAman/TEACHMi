package com.teachmi.backend.learning;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** Versioned, factual evidence for C. Completion does not represent independent assessment success. */
public record LearningEvidenceView(
        int schemaVersion, UUID sessionId, long version, String materialId, String conceptId,
        ConceptSource.Snapshot concept, String mode, String stage, String status, Instant updatedAt,
        OriginalLearning originalLearning, List<ReteachEvidence> reteachHistory,
        PendingGeneration pendingGeneration) {
    public LearningEvidenceView {
        reteachHistory = List.copyOf(reteachHistory);
    }

    public record OriginalLearning(String state, ExplanationEvidence evidence) {}

    public record ReteachEvidence(UUID reteachId, String problemId, String originAttemptId, String focus,
                                 String state, Instant createdAt, Instant completedAt,
                                 ExplanationEvidence evidence) {}

    public record ExplanationEvidence(LearningMessageView initialAnswer,
                                      LearningMessageView finalExplanation, SupportUsage support) {}

    public record SupportUsage(SupportCounts requested, SupportCounts delivered, List<SupportEventView> events) {
        public SupportUsage { events = List.copyOf(events); }
    }

    public record SupportCounts(int total, int hint, int example, int dontKnow) {}

    // Retry credentials belong to SessionView, not to a report/assessment evidence contract.
    public record PendingGeneration(String operation, UUID id, UUID reteachId, String state, String errorCode) {}
}
