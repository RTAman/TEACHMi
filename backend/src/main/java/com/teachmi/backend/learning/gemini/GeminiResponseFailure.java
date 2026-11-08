package com.teachmi.backend.learning.gemini;

/** Fixed diagnostic labels only: never attach provider bodies, user text, IDs or exception causes. */
final class GeminiResponseFailure extends IllegalArgumentException {
    final String diagnostic;
    GeminiResponseFailure(String diagnostic) {
        super(diagnostic);
        this.diagnostic=diagnostic;
    }
}
