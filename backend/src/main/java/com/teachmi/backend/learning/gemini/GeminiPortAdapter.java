package com.teachmi.backend.learning.gemini;
import com.teachmi.backend.ai.GeminiGenerationPort;

/** Separate bean lets consumers replace the public port in tests without replacing B's legacy generator. */
final class GeminiPortAdapter implements GeminiGenerationPort {
    private final GeminiGenerationService service;
    GeminiPortAdapter(GeminiGenerationService service) { this.service=service; }
    @Override public <T> T generate(Request request,Validator<T> validator) { return service.generate(request,validator); }
    @Override public <T> T generateWithPreviousSuccess(Request request,Request previous,Validator<T> validator) {
        return service.generateWithPreviousSuccess(request,previous,validator);
    }
}
