package com.teachmi.backend.assessment;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Entity
@Table(name = "assessment_attempt", schema = "teachmi")
class AssessmentAttempt {
    @Id
    UUID id;
    @Column(nullable = false)
    UUID problemId;
    @Column(nullable = false)
    UUID sessionId;
    @Column(nullable = false, length = 128)
    String memberId;
    @Column(nullable = false)
    int attemptNo;
    @Column(nullable = false, columnDefinition = "text")
    String answer;
    @Column(nullable = false, columnDefinition = "text")
    String work;
    @Column(length = 16)
    String judgement;
    @Column(columnDefinition = "text")
    String reteachFocus;
    UUID reteachId;
    @Column(nullable = false)
    boolean afterSolution;
    @Column(nullable = false, length = 128)
    String requestKey;
    @Column(nullable = false, length = 64)
    String requestHash;
    @Column(nullable = false)
    Instant createdAt;
    Instant gradedAt;

    protected AssessmentAttempt() {}

    AssessmentAttempt(AssessmentProblem problem, int attemptNo, String answer, String work, UUID reteachId,
                      boolean afterSolution, String requestKey, String requestHash) {
        id = UUID.randomUUID();
        problemId = problem.id;
        sessionId = problem.sessionId;
        memberId = problem.memberId;
        this.attemptNo = attemptNo;
        this.answer = answer;
        this.work = work;
        this.reteachId = reteachId;
        this.afterSolution = afterSolution;
        this.requestKey = requestKey;
        this.requestHash = requestHash;
        createdAt = Instant.now().truncatedTo(ChronoUnit.MICROS);
    }
}