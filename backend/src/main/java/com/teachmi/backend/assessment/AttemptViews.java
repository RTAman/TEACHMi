package com.teachmi.backend.assessment;
import java.util.List;
import java.util.UUID;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

final class AttemptViews {
    private AttemptViews() {}
    record SubmitRequest(@NotBlank @Size(max=2000) String answer, @NotBlank @Size(max=4000) String work) {}
    record AttemptView(UUID attemptId, UUID problemId, int attemptNo, String judgement,
                       List<String> availableActions, UUID nextProblemId) {}
}
