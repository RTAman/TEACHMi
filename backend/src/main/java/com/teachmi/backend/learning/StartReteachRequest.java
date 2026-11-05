package com.teachmi.backend.learning;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.PositiveOrZero;

public record StartReteachRequest(
        @NotNull @Pattern(regexp = "[A-Za-z0-9][A-Za-z0-9_-]{0,127}") String problemId,
        @NotNull @Pattern(regexp = "[A-Za-z0-9][A-Za-z0-9_-]{0,127}") String attemptId,
        @NotNull @PositiveOrZero Long expectedVersion) {}
