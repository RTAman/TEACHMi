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
    /** These holds do not finish by waiting, lease expiry, or a new quota day. */
    public static GenerationRecovery forTerminalError(String code) {
        if("AI_GENERATION_UNCERTAIN".equals(code)) return review("RECOVERY_GUARD","AI_RESULT_UNCONFIRMED");
        if("AI_ATTEMPT_LIMIT".equals(code)) return new GenerationRecovery("RECOVERY_GUARD","AI_ATTEMPT_LIMIT",false,"limit_reached");
        return null;
    }
}
