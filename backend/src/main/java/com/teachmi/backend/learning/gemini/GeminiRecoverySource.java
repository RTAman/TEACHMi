package com.teachmi.backend.learning.gemini;

import java.util.UUID;
import com.teachmi.backend.learning.GenerationRecovery;
import com.teachmi.backend.learning.LearningGenerationRecoverySource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/** Available even when generation is disabled: reconnect must not hide an older uncertain receipt. */
@Component
final class GeminiRecoverySource implements LearningGenerationRecoverySource {
    private final JdbcTemplate jdbc;
    GeminiRecoverySource(JdbcTemplate jdbc) { this.jdbc=jdbc; }

    @Override public GenerationRecovery unresolved(String memberId,UUID sessionId) {
        var rows=jdbc.queryForList("SELECT state,last_error_code,lease_until>now() AS leased "
                +"FROM teachmi.learning_ai_generation WHERE member_id=? AND session_id=? "
                +"AND state IN ('uncertain','running') "
                +"ORDER BY CASE WHEN state='uncertain' THEN 0 WHEN lease_until<=now() THEN 1 ELSE 2 END,updated_at LIMIT 1",
                memberId,sessionId);
        if(rows.isEmpty()) return null;
        var row=rows.getFirst();
        if("running".equals(row.get("state")) && Boolean.TRUE.equals(row.get("leased")))
            return GenerationRecovery.waitForResult("LEDGER_CLAIM","AI_GENERATION_IN_PROGRESS");
        String diagnostic="uncertain".equals(row.get("state"))
                ?GeminiDiagnostics.safe((String)row.get("last_error_code")):"GENERATION_LEASE_EXPIRED";
        return GenerationRecovery.review("LEDGER_CLAIM",diagnostic);
    }
}
