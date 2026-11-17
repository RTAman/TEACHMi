package com.teachmi.backend.learning.gemini;

import com.teachmi.backend.ai.GeminiGenerationPort.Scope;
import com.teachmi.backend.learning.LearningRequestTraceFilter;

/** Only fixed/bounded metadata; never include a prompt, source, answer, API key or provider response. */
final class GeminiGenerationLog {
    private static final org.slf4j.Logger LOG=org.slf4j.LoggerFactory.getLogger(GeminiGenerationService.class);
    private GeminiGenerationLog() {}

    static void citationCounts(String payload,tools.jackson.databind.ObjectMapper json) {
        if(payload==null) return;
        tools.jackson.databind.JsonNode response;
        try { response=json.readTree(payload); }
        catch(RuntimeException invalid) { return; }
        if(response==null) return;
        var sources=response.path("sourceChunkIds");
        var evidence=response.path("evidence");
        var citations=response.path("citations");
        var seen=new java.util.HashSet<String>();
        int repeated=0;
        if(evidence.isArray()) for(var entry:evidence) {
            var id=entry.path("sourceChunkId");
            if(id.isString() && !seen.add(id.asText())) repeated++;
        }
        LOG.info("Gemini citation cardinality requestId={} sourceCount={} evidenceCount={} repeatedEvidenceSourceCount={} candidateCount={}",
                LearningRequestTraceFilter.currentRequestId(),sources.isArray()?sources.size():-1,
                evidence.isArray()?evidence.size():-1,repeated,citations.isArray()?citations.size():-1);
    }

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
