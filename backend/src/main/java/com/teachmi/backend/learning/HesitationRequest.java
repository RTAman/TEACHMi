package com.teachmi.backend.learning;

import java.util.UUID;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

/** targetMessageId는 학생이 답하지 못하고 있던 AI 질문 메시지, waitedSeconds는 입력 없이 기다린 시간(초)이다. */
public record HesitationRequest(@NotNull UUID targetMessageId,
                                @NotNull @Min(30) @Max(86400) Integer waitedSeconds) {}
