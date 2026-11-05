package com.teachmi.backend.learning;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.PositiveOrZero;

/** B's command identity; C must additionally validate/idempotently fingerprint its own persistence payload. */
public record AssessmentTransitionRequest(
        @NotNull @Pattern(regexp = "[A-Za-z0-9][A-Za-z0-9_-]{0,127}") String reportId,
        @NotNull @PositiveOrZero Long expectedVersion) {}
