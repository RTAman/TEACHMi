package com.teachmi.backend.assessment;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.Executors;
import com.teachmi.backend.auth.ApiException;
import com.teachmi.backend.learning.LearningAssessmentAccess;
import com.teachmi.backend.learning.LearningException;
import com.teachmi.backend.learning.LearningSessionService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;
import static org.assertj.core.api.Assertions.*;

@SpringBootTest
class AssessmentProblemSafetyTest {
    @Autowired LearningSessionService sessions;
    @Autowired AssessmentProblemRepository problems;
    @Autowired LearningAssessmentAccess access;
    @Autowired TransactionTemplate transaction;
    @Autowired JdbcTemplate jdbc;
    @Autowired AssessmentProblemService production;
    String member() { return "safety-" + UUID.randomUUID(); }
    AssessmentProblemService using(ProblemGenerator generator) {
        var beans = new StaticListableBeanFactory();
        beans.addBean("generator", generator);
        return new AssessmentProblemService(sessions, problems, access,
                beans.getBeanProvider(ProblemGenerator.class), transaction);
    }
    long count(UUID id) {
        return jdbc.queryForObject("SELECT count(*) FROM teachmi.assessment_problem WHERE session_id=?",Long.class,id);
    }
    @Test void defaultConfigurationDoesNotInventProblemsOrLectureSources() {
        var member=member(); var id=AssessmentFixtures.ready(jdbc,member);
        assertThatThrownBy(() -> production.start(member,id)).isInstanceOfSatisfying(ApiException.class,
                ex -> { assertThat(ex.status()).isEqualTo(503); assertThat(ex.code()).isEqualTo("ASSESSMENT_GENERATION_PENDING"); });
        assertThat(count(id)).isZero();
    }
    @Test void abandonmentDuringGenerationRejectsAllWrites() {
        var member=member(); var id=AssessmentFixtures.ready(jdbc,member);
        var service=using(snapshot -> {
            jdbc.update("UPDATE teachmi.learning_session SET status='abandoned',version=version+1 WHERE id=?",id);
            return new FixedDepreciationProblems().generate(snapshot);
        });
        assertThatThrownBy(() -> service.start(member,id)).isInstanceOf(LearningException.class);
        assertThat(count(id)).isZero();
    }
    @Test void aChangedEvidenceVersionCannotBeSaved() {
        var member=member(); var id=AssessmentFixtures.ready(jdbc,member);
        var service=using(snapshot -> {
            jdbc.update("UPDATE teachmi.learning_session SET version=version+1 WHERE id=?",id);
            return new FixedDepreciationProblems().generate(snapshot);
        });
        assertThatThrownBy(() -> service.start(member,id)).isInstanceOf(LearningException.class);
        assertThat(count(id)).isZero();
    }
    @Test void concurrentStartsReturnTheSamePair() throws Exception {
        var member=member(); var id=AssessmentFixtures.ready(jdbc,member);
        var barrier=new java.util.concurrent.CyclicBarrier(2);
        var service=using(snapshot -> {
            try { barrier.await(10, java.util.concurrent.TimeUnit.SECONDS); }
            catch (Exception ex) { throw new IllegalStateException(ex); }
            return new FixedDepreciationProblems().generate(snapshot);
        });
        try (var executor=Executors.newFixedThreadPool(2)) {
            var a=executor.submit(() -> service.start(member,id));
            var b=executor.submit(() -> service.start(member,id));
            assertThat(a.get(20,java.util.concurrent.TimeUnit.SECONDS)).isEqualTo(b.get(20,java.util.concurrent.TimeUnit.SECONDS));
        }
        assertThat(count(id)).isEqualTo(2);
    }
    @Test void invalidGeneratedContentNeverBecomesAnEmptySuccess() {
        var member=member(); var id=AssessmentFixtures.ready(jdbc,member);
        var service=using(snapshot -> List.of(new ProblemGenerator.Draft(null,"x","x","x","x"),
                new ProblemGenerator.Draft("x","x","x","x","x")));
        assertThatThrownBy(() -> service.start(member,id)).isInstanceOfSatisfying(ApiException.class,
                ex -> assertThat(ex.status()).isEqualTo(502));
        assertThat(count(id)).isZero();
    }
    @Test void aDanglingFinalExplanationIsRejectedBeforeSaving() {
        var member=member(); var id=AssessmentFixtures.ready(jdbc,member);
        jdbc.update("UPDATE teachmi.learning_session SET final_explanation_id=? WHERE id=?",UUID.randomUUID(),id);
        var service=using(snapshot -> new FixedDepreciationProblems().generate(snapshot));
        assertThatThrownBy(() -> service.start(member,id)).isInstanceOfSatisfying(LearningException.class,
                ex -> assertThat(ex.code()).isEqualTo("FINAL_EXPLANATION_REQUIRED"));
        assertThat(count(id)).isZero();
    }
}
