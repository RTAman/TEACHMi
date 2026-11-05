package com.teachmi.backend.learning;

import java.util.UUID;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;

public record CreateSessionRequest(
        @NotBlank @Size(max = 128) String materialId,
        @NotBlank @Size(max = 128) String conceptId,
        UUID replaceSessionId,
        @PositiveOrZero Long expectedVersion) {
    @AssertTrue(message = "새 시작에는 replaceSessionId와 expectedVersion을 함께 전달해야 합니다.")
    public boolean isReplacementValid() {
        return (replaceSessionId == null) == (expectedVersion == null);
    }
}
