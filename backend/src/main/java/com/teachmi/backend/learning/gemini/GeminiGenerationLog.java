package com.teachmi.backend.learning.gemini;

import com.teachmi.backend.ai.GeminiGenerationPort.Scope;
import com.teachmi.backend.learning.LearningRequestTraceFilter;

/** Only fixed/bounded metadata; never include a prompt, source, answer, API key or provider response. */
final class GeminiGenerationLog {
    private static final org.slf4j.Logger LOG=org.slf4j.LoggerFactory.getLogger(GeminiGenerationService.class);
    private GeminiGenerationLog() {}

    static void event(String stage,String diagnostic,Scope scope,String version,String key,
                      GeminiSettings settings,int inputBytes,int outputTokens) {
        var parts=version.split("\n",-1);
        String operation=parts.length==3?parts[1]:"learning";
        String prompt=parts.length==3?parts[2]:version;
        if(!operation.matches("[a-z][a-z0-9/_.-]{0,63}")) operation="unknown";
        if(!prompt.matches("[A-Za-z0-9][A-Za-z0-9._-]{0,127}")) prompt="unknown";
        String model=settings.model().matches("[A-Za-z0-9][A-Za-z0-9._-]{0,99}")?settings.model():"unknown";
        String generationKey=key.matches("[0-9a-f]{64}")?key:"unknown";
        String detail=java.util.Set.of("AI_ATTEMPT_LIMIT","AI_USAGE_LIMIT","AI_RETRY_LATER").contains(diagnostic)
                ?diagnostic:GeminiDiagnostics.safe(diagnostic);
        LOG.info("Gemini stage={} diagnostic={} requestId={} sessionId={} operation={} promptVersion={} generationKey={} model={} requestTimeoutMs={} inputBytes={} maxOutputTokens={}",
                stage,detail,LearningRequestTraceFilter.currentRequestId(),
                scope.sessionId(),operation,prompt,generationKey,model,settings.requestTimeout().toMillis(),inputBytes,outputTokens);
    }
}
