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
        updatedAt = Instant.now().truncatedTo(java.time.temporal.ChronoUnit.MICROS);
    }
}
