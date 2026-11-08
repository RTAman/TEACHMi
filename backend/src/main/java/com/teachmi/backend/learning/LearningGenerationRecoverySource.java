package com.teachmi.backend.learning;

import java.util.UUID;

/** Read-only generation status for an already ownership-checked learning session. No reservation or retry. */
public interface LearningGenerationRecoverySource {
    GenerationRecovery unresolved(String memberId, UUID sessionId);
}
