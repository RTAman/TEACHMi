package com.teachmi.backend.learning.gemini;

import java.util.UUID;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import com.teachmi.backend.learning.ChooseModeRequest;
import com.teachmi.backend.learning.CreateSessionRequest;
import com.teachmi.backend.learning.LearningSessionService;
import com.teachmi.backend.learning.LearningTestApplication;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Explicit opt-in only. Sends a short synthetic accounting excerpt, never workspace files.
 * Requires a dedicated PostgreSQL test database and may incur two Gemini calls.
 */
@Tag("gemini-live")
@EnabledIfEnvironmentVariable(named = "GEMINI_LIVE_TEST", matches = "true")
@SpringBootTest(classes = LearningTestApplication.class, properties = "teachmi.gemini.enabled=true")
@Import({GeminiConfiguration.class, GeminiContentIntegrationTest.Sources.class})
class GeminiLiveTest {
    @Autowired LearningSessionService sessions;
    @Autowired JdbcTemplate jdbc;
    @Autowired GeminiSettings settings;

    @Test void realGeminiContentIsGroundedStoredAndRestored() {
        assertThat(settings.apiKey().isBlank()).as("GEMINI_API_KEY must be configured").isFalse();
        assertThat(settings.model().isBlank()).as("GEMINI_MODEL must be configured").isFalse();
        // Validate connectivity/access without generating (or billing) learning content.
        try {
            var request = java.net.http.HttpRequest.newBuilder(java.net.URI.create(
                    "https://generativelanguage.googleapis.com/v1beta/models/" + settings.model()))
                    .timeout(settings.requestTimeout()).header("x-goog-api-key", settings.apiKey()).GET().build();
            var metadata = java.net.http.HttpClient.newBuilder().connectTimeout(settings.connectTimeout()).build()
                    .send(request, java.net.http.HttpResponse.BodyHandlers.discarding());
            assertThat(metadata.statusCode()).as("Gemini model metadata status").isEqualTo(200);
        } catch (java.io.IOException ex) {
            throw new AssertionError("Gemini transport unavailable: " + ex.getClass().getSimpleName()
                    + "/" + (ex.getCause() == null ? "no-cause" : ex.getCause().getClass().getSimpleName()));
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new AssertionError("Gemini metadata lookup interrupted");
        }
        String member = "gemini-live-" + UUID.randomUUID();
        var session = sessions.create(member, "create", new CreateSessionRequest("m01", "c01", null, null));
        try {
            var preparation = sessions.chooseMode(member, session.sessionId(), "mode", new ChooseModeRequest("first", 0L));
            assertThat(preparation.summary().sourceChunkIds()).containsExactly("k01");
            var review = sessions.create(member, "review-create",
                    new CreateSessionRequest("m01", "c01", session.sessionId(), 1L));
            var started = sessions.chooseMode(member, review.sessionId(), "review-mode",
                    new ChooseModeRequest("review", 0L));
            assertThat(started.messages()).hasSize(1);
            assertThat(sessions.get(member, review.sessionId())).isEqualTo(started);
            assertThat(sessions.get(member, session.sessionId()).summary()).isEqualTo(preparation.summary());
            assertThat(sessions.chooseMode(member, session.sessionId(), "mode", new ChooseModeRequest("first", 0L)))
                    .isEqualTo(preparation);
            assertThat(jdbc.queryForObject("SELECT count(*) FROM teachmi.learning_ai_call WHERE member_id=?",
                    Long.class, member)).isEqualTo(2);
        } catch (com.teachmi.backend.learning.LearningException ex) {
            var reasons = jdbc.queryForList("SELECT last_error_code FROM teachmi.learning_ai_generation "
                    + "WHERE member_id=? ORDER BY updated_at", String.class, member);
            throw new AssertionError("Gemini live failure code=" + ex.code() + " diagnostics=" + reasons);
        } finally {
            jdbc.update("DELETE FROM teachmi.learning_session_request WHERE member_id=?", member);
            jdbc.update("DELETE FROM teachmi.learning_message WHERE session_id IN "
                    + "(SELECT id FROM teachmi.learning_session WHERE member_id=?)", member);
            jdbc.update("DELETE FROM teachmi.learning_session WHERE member_id=?", member);
        }
    }
}
