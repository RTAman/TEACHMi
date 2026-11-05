package com.teachmi.backend.learning;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record LearningMessageView(UUID messageId, long sequence, String role, String kind,
                                  String chatStage, String text, List<String> sourceChunkIds,
                                  Instant createdAt) {}
