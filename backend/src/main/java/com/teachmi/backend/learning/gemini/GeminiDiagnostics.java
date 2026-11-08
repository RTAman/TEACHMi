package com.teachmi.backend.learning.gemini;

import java.util.Set;
import com.teachmi.backend.learning.GenerationRecovery;
import com.teachmi.backend.learning.LearningException;

/** Only these fixed categories may leave the provider boundary or be read from historical ledger rows. */
final class GeminiDiagnostics {
    private static final Set<String> SAFE=Set.of(
            "AI_RESULT_UNCONFIRMED","GENERATION_LEASE_EXPIRED","AI_GENERATION_UNCERTAIN","AI_GENERATION_IN_PROGRESS",
            "TRANSPORT_CONNECT_TIMEOUT","TRANSPORT_REQUEST_TIMEOUT","TRANSPORT_INTERRUPTED","TRANSPORT_IO_FAILURE","TRANSPORT_RESPONSE_TOO_LARGE",
            "ENVELOPE_JSON_INVALID","ENVELOPE_STRUCTURE_INVALID","CONTENT_JSON_INVALID","PROMPT_BLOCKED","CANDIDATE_COUNT_INVALID",
            "RESPONSE_MAX_TOKENS","RESPONSE_NOT_STOPPED","CONTENT_MISSING","CONTENT_TYPE_INVALID","JSON_SCHEMA_INVALID",
            "CITATION_IDS_MISSING","CITATION_ID_INVALID","CITATION_ID_DUPLICATE","CITATION_CARDINALITY_INVALID",
            "EVIDENCE_MISSING","EVIDENCE_STRUCTURE_INVALID","EVIDENCE_ID_UNCITED","EVIDENCE_QUOTE_TOO_SHORT","EVIDENCE_QUOTE_TOO_LONG",
            "EVIDENCE_QUOTE_NOT_EXACT","EVIDENCE_COVERAGE_MISMATCH","DOMAIN_RESPONSE_INVALID",
            "LEDGER_SUCCESS_WRITE_FAILED","LEDGER_FAILURE_WRITE_FAILED","LEDGER_FAILURE_STATUS_READ_FAILED","GEMINI_RATE_LIMITED","GEMINI_INVALID_REQUEST",
            "GEMINI_ACCESS_DENIED","GEMINI_MODEL_NOT_FOUND","GEMINI_REQUEST_REJECTED","GEMINI_CONFIGURATION_REQUIRED");
    private GeminiDiagnostics() {}
    static String safe(String value) {
        if("TRANSPORT_HttpTimeoutException".equals(value)) return "TRANSPORT_REQUEST_TIMEOUT";
        if("TRANSPORT_HttpConnectTimeoutException".equals(value)) return "TRANSPORT_CONNECT_TIMEOUT";
        if("TRANSPORT_IOException".equals(value)) return "TRANSPORT_IO_FAILURE";
        if(value!=null && (SAFE.contains(value) || value.matches("UPSTREAM_HTTP_5[0-9]{2}"))) return value;
        return "AI_RESULT_UNCONFIRMED";
    }
    static LearningException uncertain(String stage,String diagnostic,String message) {
        return new LearningException(503,"AI_GENERATION_UNCERTAIN",message,null,GenerationRecovery.review(stage,safe(diagnostic)));
    }
}
