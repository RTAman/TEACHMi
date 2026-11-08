package com.teachmi.backend.ai;

/** Domain validators may classify failures without exposing source text or provider response bodies. */
public final class GeminiValidationException extends IllegalArgumentException {
    public enum Diagnostic {
        CITATION_IDS_MISSING,CITATION_ID_INVALID,CITATION_ID_DUPLICATE,CITATION_CARDINALITY_INVALID,
        EVIDENCE_MISSING,EVIDENCE_ID_UNCITED,EVIDENCE_QUOTE_TOO_SHORT,EVIDENCE_QUOTE_TOO_LONG,EVIDENCE_COVERAGE_MISMATCH
    }
    private final Diagnostic diagnostic;
    public GeminiValidationException(Diagnostic diagnostic,String safeMessage) {
        super(safeMessage);this.diagnostic=java.util.Objects.requireNonNull(diagnostic);
    }
    public Diagnostic diagnostic() {return diagnostic;}
}
