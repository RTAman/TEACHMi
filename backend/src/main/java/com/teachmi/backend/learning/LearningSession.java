package com.teachmi.backend.learning;

import java.time.Instant;
import java.util.UUID;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

@Entity
@Table(name = "learning_session", schema = "teachmi")
class LearningSession {
    @Id
    UUID id;
    @Column(nullable = false, length = 128)
    String memberId;
    @Column(nullable = false, length = 128)
    String materialId;
    @Column(nullable = false, length = 128)
    String conceptId;
    @Column(nullable = false, columnDefinition = "text")
    String conceptSnapshot;
    @Column(length = 16)
    String mode;
    @Column(nullable = false, length = 16)
    String stage;
    @Column(nullable = false, length = 16)
    String status;
    @Column(columnDefinition = "text")
    String summaryJson;
    @Column(columnDefinition = "text")
    String assessmentReportSnapshot;
    @Column(length = 16)
    String chatStage;
    @Column UUID pendingTurnId;
    @Column UUID initialAnswerId;
    @Column UUID finalExplanationId;
    @Column UUID activeReteachId;
    @Column UUID returnReteachId;
    @Version
    long version;
    @Column(nullable = false)
    Instant createdAt;
    @Column(nullable = false)
    Instant updatedAt;

    protected LearningSession() {}

    LearningSession(String memberId, CreateSessionRequest request, String snapshot) {
        id = UUID.randomUUID();
        this.memberId = memberId;
        materialId = request.materialId();
        conceptId = request.conceptId();
        conceptSnapshot = snapshot;
        stage = "mode";
        status = "active";
        // PostgreSQL stores microsecond precision. Keep initial POST and later GET values identical.
        createdAt = Instant.now().truncatedTo(java.time.temporal.ChronoUnit.MICROS);
        updatedAt = createdAt;
    }

    void abandon() {
        status = "abandoned";
        pendingTurnId = null;
        activeReteachId = null;
        returnReteachId = null;
        updatedAt = Instant.now().truncatedTo(java.time.temporal.ChronoUnit.MICROS);
    }

    void selectMode(String mode, String summaryJson) {
        this.mode = mode;
        this.summaryJson = summaryJson;
        stage = mode.equals("first") ? "summary" : "chat";
        chatStage = mode.equals("review") ? "case" : null;
        updatedAt = Instant.now().truncatedTo(java.time.temporal.ChronoUnit.MICROS);
    }

    void startChat() {
        stage = "chat";
        chatStage = "case";
        updatedAt = Instant.now().truncatedTo(java.time.temporal.ChronoUnit.MICROS);
    }

    void acceptAnswer(UUID messageId) {
        switch (chatStage) {
            case "case" -> chatStage = "concept";
            case "concept" -> chatStage = "reason";
            case "reason" -> chatStage = "wrapup";
            case "wrapup" -> {
                stage = "problem";
                if (activeReteachId == null) finalExplanationId = messageId;
            }
            default -> throw new IllegalStateException("Invalid conversation stage");
        }
    }

    void touch() {
        var now = Instant.now().truncatedTo(java.time.temporal.ChronoUnit.MICROS);
        updatedAt = now.isAfter(updatedAt) ? now : updatedAt.plusNanos(1000);
    }
}
