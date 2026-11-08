package com.teachmi.backend.learning.gemini;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;
class GeminiDiagnosticsTest {
    @Test void historicalOrUntrustedDiagnosticsNeverExposeTheirStoredText() {
        assertThat(GeminiDiagnostics.safe("TRANSPORT_HttpTimeoutException")).isEqualTo("TRANSPORT_REQUEST_TIMEOUT");
        assertThat(GeminiDiagnostics.safe("TRANSPORT_HttpConnectTimeoutException")).isEqualTo("TRANSPORT_CONNECT_TIMEOUT");
        var failure=GeminiDiagnostics.uncertain("LEDGER_CLAIM","private lecture / credential value","결과 확인 필요");
        assertThat(failure.recovery().diagnosticCode()).isEqualTo("AI_RESULT_UNCONFIRMED");
        assertThat(failure.recovery().toString()).doesNotContain("private","credential");
        assertThat(failure.getCause()).isNull();
    }
}
