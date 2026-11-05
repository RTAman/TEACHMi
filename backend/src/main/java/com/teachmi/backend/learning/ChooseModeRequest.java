package com.teachmi.backend.learning;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.PositiveOrZero;

public record ChooseModeRequest(
        @NotNull @Pattern(regexp = "first|review") String mode,
        @NotNull @PositiveOrZero Long expectedVersion) {}
