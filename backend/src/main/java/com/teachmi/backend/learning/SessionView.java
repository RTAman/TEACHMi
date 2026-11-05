package com.teachmi.backend.learning;

import java.time.Instant;
import java.util.UUID;

public record SessionView(
        UUID sessionId, String materialId, String conceptId, ConceptSource.Snapshot concept,
        String mode, String stage, String status, long version, Instant createdAt, Instant updatedAt,
        Resume resume) {
    public record Resume(String stage, String path) {}
}
