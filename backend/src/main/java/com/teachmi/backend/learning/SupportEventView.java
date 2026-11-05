package com.teachmi.backend.learning;

import java.time.Instant;
import java.util.UUID;

public record SupportEventView(UUID eventId, String kind, UUID targetMessageId,
                               UUID requestMessageId, UUID responseMessageId, Instant createdAt) {}
