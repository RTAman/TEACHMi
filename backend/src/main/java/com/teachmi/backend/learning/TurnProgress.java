package com.teachmi.backend.learning;

import java.util.UUID;

/** Retry uses the original key and body, including the version from before the saved input. */
public record TurnProgress(UUID turnId, String kind, String state, UUID userMessageId, String errorCode,
                           String idempotencyKey, long expectedVersion) {}
