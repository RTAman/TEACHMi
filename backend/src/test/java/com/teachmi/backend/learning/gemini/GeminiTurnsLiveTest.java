package com.teachmi.backend.learning.gemini;

import java.util.UUID;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import com.teachmi.backend.learning.*;

import static org.assertj.core.api.Assertions.assertThat;

/** Explicit opt-in: three calls using a synthetic source, never user documents. */
@Tag("gemini-live")
@EnabledIfEnvironmentVariable(named = "GEMINI_LIVE_TEST", matches = "true")
@SpringBootTest(classes = LearningTestApplication.class, properties = "teachmi.gemini.enabled=true")
@Import({GeminiConfiguration.class, GeminiContentIntegrationTest.Sources.class})
class GeminiTurnsLiveTest {
    @Autowired LearningSessionService sessions;
    @Autowired LearningTurnService turns;
    @Autowired JdbcTemplate jdbc;

    @Test void actualStudentDialogueAndHelpAreStoredAndReplayWithoutExtraCalls() {
        String member = "gemini-turns-live-" + UUID.randomUUID();
        var created = sessions.create(member, "create", new CreateSessionRequest("m01", "c01", null, null));
        try {
            sessions.chooseMode(member, created.sessionId(), "mode", new ChooseModeRequest("review", 0L));
            var helped = turns.send(member, created.sessionId(), "hint", new TurnRequest("hint", null, 1L));
            assertThat(helped.chatStage()).isEqualTo("case");
            assertThat(helped.supportEvents()).hasSize(1);
            String answer = "자료에서는 취득원가 600만 원에서 잔존가치 0원을 빼고 내용연수 3년에 나누므로 "
                    + "연간 감가상각비는 200만 원입니다. 여러 해에 걸쳐 사용하는 자산의 원가를 사용 기간에 배분합니다.";
            var request = new TurnRequest("answer", answer, helped.version());
            var answered = turns.send(member, created.sessionId(), "answer", request);
            assertThat(answered.chatStage()).isIn("case", "concept");
            assertThat(answered.messages()).hasSize(5);
            assertThat(answered.initialAnswerId()).isEqualTo(answered.messages().get(3).messageId());
            assertThat(answered.messages().get(3).supportEventId()).isEqualTo(helped.supportEvents().getFirst().eventId());
            assertThat(sessions.get(member, created.sessionId())).isEqualTo(answered);
            assertThat(turns.send(member, created.sessionId(), "answer", request)).isEqualTo(answered);
            assertThat(jdbc.queryForObject("SELECT count(*) FROM teachmi.learning_ai_call WHERE member_id=?",
                    Long.class, member)).isEqualTo(3);
        } catch (LearningException ex) {
            throw new AssertionError("Gemini turns live failure code=" + ex.code());
        } finally {
            jdbc.update("DELETE FROM teachmi.learning_turn WHERE session_id=?", created.sessionId());
            jdbc.update("DELETE FROM teachmi.learning_support_event WHERE session_id=?", created.sessionId());
            jdbc.update("DELETE FROM teachmi.learning_session_request WHERE member_id=?", member);
            jdbc.update("DELETE FROM teachmi.learning_message WHERE session_id=?", created.sessionId());
            jdbc.update("DELETE FROM teachmi.learning_session WHERE id=?", created.sessionId());
        }
    }
}
