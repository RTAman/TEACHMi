package com.teachmi.backend.learning;

/** Safe operational metadata; never contains prompts, source text, credentials or provider error bodies. */
public record GenerationRecovery(String stage, String diagnosticCode, boolean retryAllowed, String action) {
    public static GenerationRecovery review(String stage,String diagnostic) {
        return new GenerationRecovery(stage,diagnostic,false,"review_required");
    }
    public static GenerationRecovery waitForResult(String stage,String diagnostic) {
        return new GenerationRecovery(stage,diagnostic,false,"wait");
    }
    public static boolean blocksRetry(String code) {
        return "AI_GENERATION_UNCERTAIN".equals(code) || "AI_ATTEMPT_LIMIT".equals(code);
    }
}
