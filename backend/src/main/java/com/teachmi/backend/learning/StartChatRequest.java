package com.teachmi.backend.learning;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;

public record StartChatRequest(@NotNull @PositiveOrZero Long expectedVersion) {}
