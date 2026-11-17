package com.teachmi.backend.learning.gemini;

import java.time.Duration;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import com.teachmi.backend.ai.GeminiGenerationPort.Scope;
import static org.assertj.core.api.Assertions.*;

@ExtendWith(OutputCaptureExtension.class)
class GeminiGenerationLogTest {
    @Test void cardinalityDiagnosticsContainCountsWithoutSourceOrResponseText(CapturedOutput output) {
        var json=tools.jackson.databind.json.JsonMapper.builder().build();
        GeminiGenerationLog.citationCounts("""
                {"sourceChunkIds":["source-private"],"evidence":[
                {"sourceChunkId":"source-private","quote":"lecture-private"},
                {"sourceChunkId":"source-private","quote":"answer-private"}]}
                """,json);
        assertThat(output.getAll()).contains("sourceCount=1","evidenceCount=2","repeatedEvidenceSourceCount=1","candidateCount=-1")
                .doesNotContain("source-private","lecture-private","answer-private");
        GeminiGenerationLog.citationCounts("broken-response-private",json);
        assertThat(output.getAll()).doesNotContain("broken-response-private");
    }
    @Test void quotaDiagnosticsRemainSpecificAndUnsafeMetadataIsNotLogged(CapturedOutput output) {
        var settings=new GeminiSettings("credential-sentinel","invalid\nmodel-sentinel",Duration.ofSeconds(3),Duration.ofSeconds(40),
                10,100,20,2,16000,2048);
        var scope=new Scope("member-sentinel",UUID.randomUUID(),"material-sentinel","concept-sentinel",List.of("source-sentinel"));
        GeminiGenerationLog.event("LEDGER_CLAIM","AI_ATTEMPT_LIMIT",scope,"invalid\rprompt-sentinel","not-a-digest-sentinel",settings,100,2048);
        assertThat(output.getAll()).contains("diagnostic=AI_ATTEMPT_LIMIT","generationKey=unknown","promptVersion=unknown","model=unknown")
                .doesNotContain("credential-sentinel","model-sentinel","member-sentinel","material-sentinel","concept-sentinel",
                        "source-sentinel","prompt-sentinel","not-a-digest-sentinel");
    }
}
