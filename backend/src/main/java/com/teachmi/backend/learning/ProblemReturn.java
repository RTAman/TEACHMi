package com.teachmi.backend.learning;

import java.util.UUID;

/** C resumes this existing problem; this object never creates or grades an attempt. */
public record ProblemReturn(UUID reteachId, String problemId, String originAttemptId, String path) {}
