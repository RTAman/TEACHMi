package com.teachmi.backend.assessment;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Entity
@Table(name = "assessment_problem", schema = "teachmi")
class AssessmentProblem {
    @Id
    UUID id;
    @Column(nullable = false)
    UUID sessionId;
    @Column(nullable = false, length = 128)
    String memberId;
    @Column(nullable = false)
    int sequence;
    @Column(nullable = false, columnDefinition = "text")
    String question;
    @Column(nullable = false, columnDefinition = "text")
    String changedConditions;
    @Column(nullable = false, columnDefinition = "text")
    String sourceChunkIds;
    @Column(nullable = false, columnDefinition = "text")
    String expectedAnswer;
    @Column(nullable = false, columnDefinition = "text")
    String solution;
    @Column(nullable = false, columnDefinition = "text")
    String gradingCriteria;
    @Column(nullable = false)
    long evidenceVersion;
    Instant solutionViewedAt;
    Instant confirmedAt;
    @Column(nullable = false)
    Instant createdAt;

    protected AssessmentProblem() {}

    AssessmentProblem(UUID sessionId, String memberId, int sequence, String question, String changedConditions,
                      String sourceChunkIds, String expectedAnswer, String solution, String gradingCriteria,
                      long evidenceVersion) {
        id = UUID.randomUUID();
        this.sessionId = sessionId;
        this.memberId = memberId;
        this.sequence = sequence;
        this.question = question;
        this.changedConditions = changedConditions;
        this.sourceChunkIds = sourceChunkIds;
        this.expectedAnswer = expectedAnswer;
        this.solution = solution;
        this.gradingCriteria = gradingCriteria;
        this.evidenceVersion = evidenceVersion;
        createdAt = Instant.now().truncatedTo(ChronoUnit.MICROS);
    }
}