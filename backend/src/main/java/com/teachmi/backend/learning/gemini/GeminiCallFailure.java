package com.teachmi.backend.learning.gemini;

/** Never carry upstream response bodies, prompts, API keys, or raw exception messages. */
class GeminiCallFailure extends RuntimeException {
    final boolean uncertain;
    final int status;
    final String code;
    final int retrySeconds;
    final String diagnostic;

    GeminiCallFailure(boolean uncertain, int status, String code, int retrySeconds) {
        this(uncertain, status, code, retrySeconds, code);
    }

    GeminiCallFailure(boolean uncertain, int status, String code, int retrySeconds, String diagnostic) {
        super(code);
        this.uncertain = uncertain;
        this.status = status;
        this.code = code;
        this.retrySeconds = retrySeconds;
        this.diagnostic = diagnostic;
    }
}
