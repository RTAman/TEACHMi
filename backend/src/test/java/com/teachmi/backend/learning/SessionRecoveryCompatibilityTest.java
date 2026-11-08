package com.teachmi.backend.learning;

import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;
import static org.assertj.core.api.Assertions.*;

class SessionRecoveryCompatibilityTest {
    final tools.jackson.databind.ObjectMapper json=JsonMapper.builder().build();
    SessionView view(TurnProgress turn,ReteachView reteach) {
        var now=Instant.parse("2026-01-01T00:00:00Z");
        // This is the original constructor used by A/B/C and saved response fixtures.
        return new SessionView(UUID.randomUUID(),"m","c",new ConceptSource.Snapshot("개념","범위",List.of("k")),
                "review","chat","active",1,now,now,null,null,"case",List.of(),null,null,List.of(),turn,List.of("wait"),reteach,null,List.of());
    }
    @Test void olderTerminalSnapshotsWithoutRecoveryRestoreAnExplicitNotice() {
        var pending=new TurnProgress(UUID.randomUUID(),"answer","failed",UUID.randomUUID(),"AI_GENERATION_UNCERTAIN","key",1);
        var old=(ObjectNode)json.valueToTree(view(pending,null));old.remove("recovery");
        var restored=json.treeToValue(old,SessionView.class);
        assertThat(restored.recovery().action()).isEqualTo("review_required");
        assertThat(restored.recovery().retryAllowed()).isFalse();
        var now=Instant.parse("2026-01-01T00:00:00Z");
        var reteach=new ReteachView(UUID.randomUUID(),"p","a","개념","failed",null,null,null,now,null,"key",1,"AI_ATTEMPT_LIMIT");
        var older=(ObjectNode)json.valueToTree(view(null,reteach));older.remove("recovery");
        assertThat(json.treeToValue(older,SessionView.class).recovery().action()).isEqualTo("limit_reached");
    }
    @Test void ordinaryProcessingAndOldSuccessPayloadsKeepTheirExistingFields() {
        var pending=new TurnProgress(UUID.randomUUID(),"answer","generating",UUID.randomUUID(),null,"key",1);
        var node=json.valueToTree(view(pending,null));
        assertThat(node.has("recovery")).isFalse();
        tools.jackson.databind.JsonNode restored=json.valueToTree(json.treeToValue(node,SessionView.class));
        assertThat(restored).isEqualTo(node);
    }
    @Test void terminalErrorWinsOverAStaleProcessingHint() {
        var pending=new TurnProgress(UUID.randomUUID(),"answer","failed",UUID.randomUUID(),"AI_ATTEMPT_LIMIT","key",1);
        var node=(ObjectNode)json.valueToTree(view(pending,null));
        node.set("recovery",json.valueToTree(GenerationRecovery.waitForResult("LEDGER_CLAIM","AI_GENERATION_IN_PROGRESS")));
        var restored=json.treeToValue(node,SessionView.class);
        assertThat(restored.recovery().action()).isEqualTo("limit_reached");
        assertThat(restored.recovery().retryAllowed()).isFalse();
    }
}
