package com.teachmi.backend.learning;

import java.time.Instant;
import java.util.UUID;
import com.fasterxml.jackson.annotation.JsonInclude;

public record SupportEventView(UUID eventId, String kind, UUID targetMessageId,
                               UUID requestMessageId, UUID responseMessageId, Instant createdAt,
                               @JsonInclude(JsonInclude.Include.NON_NULL) UUID reteachId) {}
