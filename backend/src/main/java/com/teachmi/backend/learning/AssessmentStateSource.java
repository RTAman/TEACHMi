package com.teachmi.backend.learning;

import java.util.UUID;

/**
 * C implements this read port against its real persisted assessment/report records, in B's transaction.
 * Check ownership, session association, latest assessment completion and report readiness. Revision
 * identifies the report content/evidence, not an acknowledgement. C defines completionAllowed explicitly.
 * Return null or throw LearningException.notFound() for missing/inaccessible reports. No remote I/O.
 */
public interface AssessmentStateSource {
    State loadOwnedReport(String memberId, UUID sessionId, String reportId);

    record State(String memberId, UUID sessionId, String reportId, long revision, long evidenceVersion,
                 boolean assessmentComplete, boolean reportReady, boolean completionAllowed) {}
}
