package com.teachmi.backend.assessment;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Entity
@Table(name = "assessment_report", schema = "teachmi")
class AssessmentReport {
    @Id
    UUID id;
    @Column(nullable = false)
    UUID sessionId;
    @Column(nullable = false, length = 128)
    String memberId;
    @Column(nullable = false)
    long revision;
    @Column(nullable = false)
    long evidenceVersion;
    @Column(nullable = false, columnDefinition = "text")
    String content;
    @Column(nullable = false)
    Instant createdAt;
    Instant confirmedAt;

    protected AssessmentReport() {}

    AssessmentReport(UUID sessionId, String memberId, long evidenceVersion, String content) {
        id = UUID.randomUUID();
        this.sessionId = sessionId;
        this.memberId = memberId;
        this.evidenceVersion = evidenceVersion;
        this.content = content;
        createdAt = Instant.now().truncatedTo(ChronoUnit.MICROS);
    }
}