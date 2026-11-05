package com.teachmi.backend.learning;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;

public record TurnRequest(
        @NotNull @Pattern(regexp = "answer|hint|example|dont_know") String kind,
        @Size(max = 4000) String text,
        @NotNull @PositiveOrZero Long expectedVersion) {
    @AssertTrue(message = "answer에는 답변이 필요하며 도움 요청은 text를 생략해야 합니다.")
    public boolean isTextValid() {
        return "answer".equals(kind) ? text != null && !text.isBlank() && text.indexOf('\0') < 0 : text == null;
    }
}
