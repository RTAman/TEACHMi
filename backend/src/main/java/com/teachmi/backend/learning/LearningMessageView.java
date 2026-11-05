package com.teachmi.backend.learning;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import com.fasterxml.jackson.annotation.JsonInclude;

public record LearningMessageView(UUID messageId, long sequence, String role, String kind,
                                  String chatStage, String text, List<String> sourceChunkIds,
                                  Instant createdAt, UUID supportEventId,
                                  @JsonInclude(JsonInclude.Include.NON_NULL) UUID reteachId) {
    public LearningMessageView(UUID messageId, long sequence, String role, String kind, String chatStage,
                               String text, List<String> sourceChunkIds, Instant createdAt, UUID supportEventId) {
        this(messageId, sequence, role, kind, chatStage, text, sourceChunkIds, createdAt, supportEventId, null);
    }
}
