package com.teachmi.backend.learning;

import java.time.Instant;
import java.util.UUID;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Entity
@Table(name = "learning_message", schema = "teachmi")
class LearningMessage {
    @Id UUID id;
    @Column(nullable = false) UUID sessionId;
    @Column(nullable = false) long sequence;
    @Column(nullable = false, length = 16) String role;
    @Column(nullable = false, length = 32) String kind;
    @Column(nullable = false, length = 16) String chatStage;
    @Column(nullable = false, columnDefinition = "text") String text;
    @Column(nullable = false, columnDefinition = "text") String sourceChunkIds;
    @Column(nullable = false) Instant createdAt;
    @Column UUID supportEventId;
    @Column UUID reteachId;

    protected LearningMessage() {}

    LearningMessage(UUID sessionId, String text, String sourceChunkIds) {
        this(sessionId, 1, "assistant", "case_question", "case", text, sourceChunkIds, null);
    }

    LearningMessage(UUID sessionId, long sequence, String role, String kind, String chatStage,
                    String text, String sourceChunkIds, UUID supportEventId) {
        this.id = UUID.randomUUID();
        this.sessionId = sessionId;
        this.sequence = sequence;
        this.role = role;
        this.kind = kind;
        this.chatStage = chatStage;
        this.text = text;
        this.sourceChunkIds = sourceChunkIds;
        this.createdAt = Instant.now().truncatedTo(java.time.temporal.ChronoUnit.MICROS);
        this.supportEventId = supportEventId;
    }
}
