package com.teachmi.backend.learning;

import java.time.Instant;
import java.util.UUID;

public record ReteachView(UUID reteachId, String problemId, String attemptId, String focus, String state,
                          UUID questionMessageId, UUID initialAnswerId, UUID finalExplanationId,
                          Instant createdAt, Instant completedAt, String idempotencyKey,
                          long expectedVersion, String errorCode) {}
