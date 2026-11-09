package com.teachmi.backend.learning;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;

public record FinishExplanationRequest(@NotNull @PositiveOrZero Long expectedVersion) {}
