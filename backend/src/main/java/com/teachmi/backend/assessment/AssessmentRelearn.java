package com.teachmi.backend.assessment;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Entity
@Table(name = "assessment_relearn", schema = "teachmi")
class AssessmentRelearn {
    @Id
    UUID sessionId;
    @Column(nullable = false)
    UUID originSessionId;
    @Column(nullable = false)
    UUID reportId;
    @Column(nullable = false, length = 128)
    String memberId;
    UUID problemId;
    @Column(columnDefinition = "text")
    String focus;
    @Column(nullable = false)
    Instant createdAt;

    protected AssessmentRelearn() {}

    AssessmentRelearn(UUID sessionId, AssessmentReport report, UUID problemId, String focus) {
        this.sessionId = sessionId;
        originSessionId = report.sessionId;
        reportId = report.id;
        memberId = report.memberId;
        this.problemId = problemId;
        this.focus = focus;
        createdAt = Instant.now().truncatedTo(ChronoUnit.MICROS);
    }
}