package com.teachmi.backend.learning;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import jakarta.validation.ConstraintViolationException;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

@SpringBootTest(classes = LearningTestApplication.class)
@Import({LearningSessionApiTest.Sources.class, LearningModeTransitionTest.Generation.class,
        LearningTurnsApiTest.Dialogue.class, LearningReteachApiTest.Providers.class,
        LearningAssessmentServiceTest.Reports.class})
class LearningAssessmentServiceTest {
    @TestConfiguration(proxyBeanMethods = false)
    static class Reports {
        @Bean AssessmentStateSource reports(JdbcTemplate jdbc) {
            // This fixture table exists only during these tests; no production C report is invented.
            return (member, session, report) -> jdbc.query("SELECT * FROM teachmi.test_assessment_report WHERE report_id=?",
                    (rs, row) -> new AssessmentStateSource.State(rs.getString("member_id"),
                            rs.getObject("session_id", UUID.class), rs.getString("report_id"), rs.getLong("revision"),
                            rs.getLong("evidence_version"), rs.getBoolean("assessment_complete"),
                            rs.getBoolean("report_ready"), rs.getBoolean("completion_allowed")), report)
                    .stream().findFirst().orElse(null);
        }
    }

    @Autowired LearningAssessmentService service;
    @Autowired LearningSessionService sessions;
    @Autowired LearningSessionRepository repository;
    @Autowired LearningTurnService turns;
    @Autowired LearningReteachService reteach;
    @Autowired LearningSessionApiTest.FixtureConceptSource concepts;
    @Autowired LearningModeTransitionTest.FixtureGenerator preparation;
    @Autowired LearningTurnsApiTest.FixtureDialogue dialogue;
    @Autowired LearningReteachApiTest.Opening opening;
    @Autowired LearningReteachApiTest.Problems problems;
    @Autowired JdbcTemplate jdbc;
    @Autowired PlatformTransactionManager transactionManager;
    @Autowired WebApplicationContext context;
    UUID id;
    SessionView original;

    @BeforeEach void setup() {
        jdbc.execute("TRUNCATE teachmi.learning_reteach, teachmi.learning_turn, teachmi.learning_support_event, "
                + "teachmi.learning_session_request, teachmi.learning_message, teachmi.learning_session");
        jdbc.execute("""
                CREATE TABLE teachmi.test_assessment_report (
                    report_id VARCHAR(128) PRIMARY KEY,member_id VARCHAR(128) NOT NULL,session_id UUID NOT NULL,
                    revision BIGINT NOT NULL,evidence_version BIGINT NOT NULL,assessment_complete BOOLEAN NOT NULL,
                    report_ready BOOLEAN NOT NULL,completion_allowed BOOLEAN NOT NULL)
                """);
        concepts.unavailable = false; preparation.fail = false; preparation.unknownEvidence = false;
        dialogue.sufficient = true; dialogue.fail = false; dialogue.invalidHelp = false;
        dialogue.wrongEvidence = false; dialogue.entered = null; dialogue.release = null;
        opening.fail = false; opening.wrongEvidence = false; opening.entered = null; opening.release = null;
        problems.member = "member-1"; problems.problem = "p01"; problems.attempt = "a01";
        problems.judgement = "wrong"; problems.focus = "기간별 비용 배분";
        id = sessions.create("member-1", "create", new CreateSessionRequest("m01", "c01", null, null)).sessionId();
        sessions.chooseMode("member-1", id, "mode", new ChooseModeRequest("review", 0L));
        finish("normal");
        original = current(); problems.session = id;
    }

    @AfterEach void cleanup() { jdbc.execute("DROP TABLE IF EXISTS teachmi.test_assessment_report"); }

    @Test void reportReadinessAndExplicitCompletionPreserveEvidenceAndReleaseTheActiveSlot() throws Exception {
        var ready = publish("ready", reportRequest());
        assertThat(ready.stage()).isEqualTo("report");
        assertThat(ready.status()).isEqualTo("active");
        assertThat(ready.version()).isEqualTo(original.version() + 1);
        assertThat(ready.availableActions()).containsExactly("view_report");
        assertThat(ready.resume()).isNotNull();
        assertThat(sessions.getActive("member-1")).contains(ready);
        assertThat(ready.messages()).isEqualTo(original.messages());
        assertThat(ready.initialAnswerId()).isEqualTo(original.initialAnswerId());
        assertThat(ready.finalExplanationId()).isEqualTo(original.finalExplanationId());
        var request = reportRequest();
        var completed = service.completeLearning("member-1", id, "complete", request, locked -> approve());
        assertThat(completed.stage()).isEqualTo("report");
        assertThat(completed.status()).isEqualTo("completed");
        assertThat(completed.version()).isEqualTo(ready.version() + 1);
        assertThat(completed.updatedAt()).isAfter(ready.updatedAt());
        assertThat(completed.resume()).isNull();
        assertThat(completed.availableActions()).isEmpty();
        assertThat(completed.messages()).isEqualTo(original.messages());
        assertThat(sessions.getActive("member-1")).isEmpty();
        assertThat(MockMvcBuilders.webAppContextSetup(context).build()
                .perform(get("/api/v1/sessions/active").principal(() -> "member-1")).andReturn().getResponse().getStatus()).isEqualTo(204);
        var next = sessions.create("member-1", "new", new CreateSessionRequest("m01", "c02", null, null));
        assertThat(sessions.getActive("member-1")).contains(next);
        assertThat(current()).isEqualTo(completed);
    }

    @Test void successfulCommandsReplayWithoutRunningCWritesAndShareBsKeySpace() {
        var called = new AtomicInteger();
        var request = reportRequest();
        var ready = service.publishReport("member-1", id, "ready", request, locked -> { called.incrementAndGet(); saveReport(locked); });
        var completeRequest = reportRequest();
        var completed = service.completeLearning("member-1", id, "complete", completeRequest, locked -> { called.incrementAndGet(); approve(); });
        assertThat(service.publishReport("member-1", id, "ready", request, locked -> fail("Replay ran C writes"))).isEqualTo(ready);
        assertThat(service.completeLearning("member-1", id, "complete", completeRequest, locked -> fail("Replay ran C writes"))).isEqualTo(completed);
        assertThat(called.get()).isEqualTo(2);
        assertThat(reportCount()).isEqualTo(1);
        expect("IDEMPOTENCY_KEY_REUSED", () -> service.publishReport("member-1", id, "ready",
                new AssessmentTransitionRequest("different", request.expectedVersion()), locked -> fail("Reused key")));
        expect("IDEMPOTENCY_KEY_REUSED", () -> service.completeLearning("member-1", id, "ready", request, locked -> fail("Cross-operation key")));
        expect("SESSION_STATE_CONFLICT", () -> service.completeLearning("member-1", id, "new-key", reportRequest(), locked -> fail("Already complete")));
    }

    @Test void cPersistenceFailureOrInvalidPersistedFactsRollBackAndCanRetryTheSameKey() {
        var request = reportRequest();
        assertThatThrownBy(() -> service.publishReport("member-1", id, "retry", request, locked -> {
            saveReport(locked); throw new IllegalStateException("C database failure");
        })).isInstanceOf(IllegalStateException.class);
        assertUnchanged();
        expect("ASSESSMENT_NOT_READY", () -> service.publishReport("member-1", id, "retry", request, locked -> {
            saveReport(locked); jdbc.update("UPDATE teachmi.test_assessment_report SET report_ready=false");
        }));
        assertUnchanged();
        assertThat(publish("retry", request).stage()).isEqualTo("report");
        assertThat(reportCount()).isEqualTo(1);
    }

    @Test void outerCFailureRollsBackCReportBStateAndSuccessfulReceiptTogether() {
        var request = reportRequest();
        assertThatThrownBy(() -> new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            publish("retry", request);
            assertThat(current().stage()).isEqualTo("report");
            throw new IllegalStateException("C transaction failed after B returned");
        })).isInstanceOf(IllegalStateException.class);
        assertUnchanged();
        assertThat(publish("retry", request).stage()).isEqualTo("report");
    }

    @Test void missingOrOtherMembersReportsAndUnfinishedAssessmentsCannotAdvanceTheSession() {
        var request = reportRequest();
        expect("NOT_FOUND", () -> service.publishReport("member-1", id, "missing", request, locked -> {}));
        expect("NOT_FOUND", () -> service.publishReport("member-1", id, "other", request, locked -> {
            saveReport(locked); jdbc.update("UPDATE teachmi.test_assessment_report SET member_id='member-2'");
        }));
        expect("NOT_FOUND", () -> service.publishReport("member-1", id, "wrong-session", request, locked -> {
            saveReport(locked); jdbc.update("UPDATE teachmi.test_assessment_report SET session_id=?", UUID.randomUUID());
        }));
        expect("ASSESSMENT_NOT_READY", () -> service.publishReport("member-1", id, "ungraded", request, locked -> {
            saveReport(locked); jdbc.update("UPDATE teachmi.test_assessment_report SET assessment_complete=false");
        }));
        expect("NOT_FOUND", () -> service.publishReport("member-2", id, "foreign-session", request, locked -> fail("Owner check skipped")));
        assertUnchanged();
    }

    @Test void staleOrInvalidReportEvidenceCannotPublishAndChangedRevisionCannotComplete() {
        expect("ASSESSMENT_EVIDENCE_STALE", () -> service.publishReport("member-1", id, "stale-evidence", reportRequest(), locked -> {
            saveReport(locked); jdbc.update("UPDATE teachmi.test_assessment_report SET evidence_version=evidence_version-1");
        }));
        expect("INVALID_ASSESSMENT_STATE", () -> service.publishReport("member-1", id, "bad-revision", reportRequest(), locked -> {
            saveReport(locked); jdbc.update("UPDATE teachmi.test_assessment_report SET revision=-1");
        }));
        assertUnchanged();
        var ready = publish("ready", reportRequest());
        expect("ASSESSMENT_REPORT_CHANGED", () -> service.completeLearning("member-1", id, "wrong-report",
                new AssessmentTransitionRequest("r02", ready.version()), locked -> fail("Wrong linked report")));
        expect("ASSESSMENT_REPORT_CHANGED", () -> service.completeLearning("member-1", id, "changed", reportRequest(), locked -> {
            approve(); jdbc.update("UPDATE teachmi.test_assessment_report SET revision=2");
        }));
        assertThat(current()).isEqualTo(ready);
        assertThat(jdbc.queryForObject("SELECT revision FROM teachmi.test_assessment_report", Long.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT completion_allowed FROM teachmi.test_assessment_report", Boolean.class)).isFalse();
    }

    @Test void reportReadinessDoesNotImplyCompletionAndAFailedCompletionRollsBackApproval() {
        var ready = publish("ready", reportRequest());
        var request = reportRequest();
        expect("LEARNING_COMPLETION_NOT_APPROVED", () -> service.completeLearning("member-1", id, "complete", request, locked -> {}));
        assertThatThrownBy(() -> service.completeLearning("member-1", id, "complete", request, locked -> {
            approve(); throw new IllegalStateException("C completion save failed");
        })).isInstanceOf(IllegalStateException.class);
        assertThat(current()).isEqualTo(ready);
        assertThat(jdbc.queryForObject("SELECT completion_allowed FROM teachmi.test_assessment_report", Boolean.class)).isFalse();
        assertThat(service.completeLearning("member-1", id, "complete", request, locked -> approve()).status()).isEqualTo("completed");
    }

    @Test void legacyReportWithoutAVerifiedLinkCannotBeSilentlyCompleted() {
        jdbc.update("UPDATE teachmi.learning_session SET stage='report' WHERE id=?", id);
        expect("REPORT_HANDOFF_REQUIRED", () -> service.completeLearning("member-1", id, "legacy", reportRequest(), locked -> fail("Legacy link missing")));
        assertThat(current().status()).isEqualTo("active");
        assertThat(reportCount()).isZero();
    }

    @Test void stageVersionAndExistingKeysRejectBeforeAnyCCallbackRuns() {
        expect("SESSION_STATE_CONFLICT", () -> service.publishReport("member-1", id, "stale",
                new AssessmentTransitionRequest("r01", original.version() - 1), locked -> fail("Stale version")));
        expect("SESSION_STATE_CONFLICT", () -> service.completeLearning("member-1", id, "too-soon", reportRequest(), locked -> fail("Skip report")));
        expect("IDEMPOTENCY_KEY_REUSED", () -> service.publishReport("member-1", id, "mode", reportRequest(), locked -> fail("Existing B key")));
        // Pending/active guards also protect against an inconsistent C-side stage update.
        for (String column : List.of("pending_turn_id", "active_reteach_id", "return_reteach_id")) {
            jdbc.update("UPDATE teachmi.learning_session SET " + column + "=? WHERE id=?", UUID.randomUUID(), id);
            expect("SESSION_STATE_CONFLICT", () -> service.publishReport("member-1", id, "blocked", reportRequest(), locked -> fail("Unfinished work")));
            jdbc.update("UPDATE teachmi.learning_session SET " + column + "=NULL WHERE id=?", id);
        }
        jdbc.update("UPDATE teachmi.learning_session SET final_explanation_id=NULL WHERE id=?", id);
        expect("FINAL_EXPLANATION_REQUIRED", () -> publish("no-final", reportRequest()));
        assertThat(reportCount()).isZero();
    }

    @Test void realReteachAndItsUnconsumedProblemReturnMustFinishBeforeReportPublication() {
        reteach.start("member-1", id, "reteach", new StartReteachRequest("p01", "a01", current().version()));
        expect("SESSION_STATE_CONFLICT", () -> publish("chat-blocked", reportRequest()));
        finish("reteach");
        expect("SESSION_STATE_CONFLICT", () -> publish("return-blocked", reportRequest()));
        var returned = current();
        reteach.acknowledgeProblemReturn("member-1", id, returned.problemReturn().reteachId(), returned.version());
        // C's source must now certify the actual final assessment; this test certifies only the port contract.
        assertThat(publish("ready", reportRequest()).stage()).isEqualTo("report");
    }

    @Test void absentProductionCAdapterRejectsBeforeWritesAndTheVerifiedLinkSurvivesRestart() {
        try (var restarted = new SpringApplicationBuilder(LearningTestApplication.class).web(WebApplicationType.NONE).run()) {
            var missing = restarted.getBean(LearningAssessmentService.class);
            expect("ASSESSMENT_INTEGRATION_PENDING", () -> missing.publishReport("member-1", id, "missing", reportRequest(), locked -> fail("Missing C")));
        }
        assertUnchanged();
        publish("ready", reportRequest());
        try (var restarted = new SpringApplicationBuilder(LearningTestApplication.class, Reports.class).web(WebApplicationType.NONE).run()) {
            var completed = restarted.getBean(LearningAssessmentService.class)
                    .completeLearning("member-1", id, "complete", reportRequest(), locked -> approve());
            assertThat(completed.status()).isEqualTo("completed");
        }
    }

    @Test void validationAndReadOnlyTransactionsCannotExecutePersistenceAndThereIsNoPublicCompletionRoute() throws Exception {
        for (var request : List.of(new AssessmentTransitionRequest("../report", current().version()),
                new AssessmentTransitionRequest("r01", -1L), new AssessmentTransitionRequest("r01", null))) {
            assertThatThrownBy(() -> service.publishReport("member-1", id, "invalid", request, locked -> fail("Invalid input")))
                    .isInstanceOf(ConstraintViolationException.class);
        }
        expect("INVALID_IDEMPOTENCY_KEY", () -> publish(null, reportRequest()));
        var readOnly = new TransactionTemplate(transactionManager); readOnly.setReadOnly(true);
        expect("WRITABLE_TRANSACTION_REQUIRED", () -> readOnly.executeWithoutResult(status -> publish("readonly", reportRequest())));
        var mvc = MockMvcBuilders.webAppContextSetup(context).build();
        for (String path : List.of("report-ready", "complete")) {
            assertThat(mvc.perform(post("/api/v1/sessions/" + id + "/" + path).principal(() -> "member-1"))
                    .andReturn().getResponse().getStatus()).isEqualTo(404);
        }
        assertUnchanged();
    }

    @Test void aCallbackThatAccidentallyChangesBIsRejectedAndEverythingRollsBack() {
        expect("SESSION_STATE_CONFLICT", () -> service.publishReport("member-1", id, "changed-b", reportRequest(), locked -> {
            saveReport(locked);
            sessions.create("member-1", "replace", new CreateSessionRequest("m01", "c02", id, locked.version()));
        }));
        assertUnchanged();
        assertThat(repository.count()).isEqualTo(1);
    }

    @Test void concurrentDuplicateCommandsRunCOnlyOnce() throws Exception {
        var request = reportRequest(); var entered = new CountDownLatch(1); var release = new CountDownLatch(1); var calls = new AtomicInteger();
        try (var executor = Executors.newFixedThreadPool(2)) {
            var first = executor.submit(() -> service.publishReport("member-1", id, "same", request, locked -> {
                calls.incrementAndGet(); saveReport(locked); entered.countDown(); await(release);
            }));
            try {
                assertThat(entered.await(10, TimeUnit.SECONDS)).isTrue();
                var second = executor.submit(() -> service.publishReport("member-1", id, "same", request, locked -> fail("Duplicate C save")));
                release.countDown();
                assertThat(second.get(10, TimeUnit.SECONDS)).isEqualTo(first.get(10, TimeUnit.SECONDS));
            } finally { release.countDown(); }
        }
        assertThat(calls.get()).isEqualTo(1);
        assertThat(reportCount()).isEqualTo(1);
        assertThat(current().version()).isEqualTo(original.version() + 1);
    }

    @Test void concurrentNewLearningCannotReplaceAReportPublishedAgainstTheSameVersion() throws Exception {
        var request = reportRequest(); var entered = new CountDownLatch(1); var release = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(2)) {
            var publishing = executor.submit(() -> service.publishReport("member-1", id, "ready", request, locked -> {
                saveReport(locked); entered.countDown(); await(release);
            }));
            try {
                assertThat(entered.await(10, TimeUnit.SECONDS)).isTrue();
                var replacing = executor.submit(() -> sessions.create("member-1", "replace",
                        new CreateSessionRequest("m01", "c02", id, original.version())));
                release.countDown();
                assertThat(publishing.get(10, TimeUnit.SECONDS).stage()).isEqualTo("report");
                assertThatThrownBy(() -> replacing.get(10, TimeUnit.SECONDS)).hasCauseInstanceOf(LearningException.class);
            } finally { release.countDown(); }
        }
        assertThat(sessions.getActive("member-1")).contains(current());
        assertThat(reportCount()).isEqualTo(1);
    }

    @Test void aNewLearningThatAlreadyReplacedTheSessionRejectsOldPublicationBeforeCSave() {
        var request = reportRequest();
        var replacement = sessions.create("member-1", "replace", new CreateSessionRequest("m01", "c02", id, current().version()));
        expect("SESSION_STATE_CONFLICT", () -> service.publishReport("member-1", id, "late", request, locked -> fail("Abandoned session")));
        assertThat(sessions.getActive("member-1")).contains(replacement);
        assertThat(reportCount()).isZero();
    }

    private SessionView current() { return sessions.get("member-1", id); }
    private AssessmentTransitionRequest reportRequest() { return new AssessmentTransitionRequest("r01", current().version()); }
    private SessionView publish(String key, AssessmentTransitionRequest request) {
        return service.publishReport("member-1", id, key, request, this::saveReport);
    }
    private void saveReport(SessionView locked) {
        jdbc.update("""
                INSERT INTO teachmi.test_assessment_report VALUES ('r01',?,?,1,?,true,true,false)
                """, "member-1", locked.sessionId(), locked.version());
    }
    private void approve() { jdbc.update("UPDATE teachmi.test_assessment_report SET completion_allowed=true WHERE report_id='r01'"); }
    private long reportCount() { return jdbc.queryForObject("SELECT count(*) FROM teachmi.test_assessment_report", Long.class); }
    private void assertUnchanged() {
        assertThat(current()).isEqualTo(original);
        assertThat(reportCount()).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM teachmi.learning_session_request WHERE request_key NOT IN "
                + "('create','mode','normal0','normal1','normal2','normal3')", Long.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT assessment_report_snapshot FROM teachmi.learning_session WHERE id=?", String.class, id)).isNull();
    }
    private void finish(String prefix) {
        for (int i = 0; i < 4; i++) turns.send("member-1", id, prefix + i,
                new TurnRequest("answer", "의미와 이유를 설명합니다.", current().version()));
    }
    private void expect(String code, ThrowingCallable call) {
        assertThatThrownBy(call).isInstanceOfSatisfying(LearningException.class, ex -> assertThat(ex.code()).isEqualTo(code));
    }
    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(10, TimeUnit.SECONDS)) throw new IllegalStateException("test timeout");
        } catch (InterruptedException ex) { Thread.currentThread().interrupt(); throw new IllegalStateException(ex); }
    }
}
