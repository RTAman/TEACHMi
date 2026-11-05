package com.teachmi.backend.learning;

import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

interface LearningMessageRepository extends JpaRepository<LearningMessage, UUID> {
    List<LearningMessage> findBySessionIdOrderBySequenceAsc(UUID sessionId);
    boolean existsBySessionId(UUID sessionId);
}
