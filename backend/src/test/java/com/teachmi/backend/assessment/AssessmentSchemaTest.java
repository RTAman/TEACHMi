package com.teachmi.backend.assessment;

import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
@Transactional
class AssessmentSchemaTest {
    @Autowired AssessmentProblemRepository problems;
    @Autowired AssessmentAttemptRepository attempts;
    @Autowired JdbcTemplate jdbc;

    private UUID session(String member) {
        var id = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO teachmi.learning_session
                (id, member_id, material_id, concept_id, concept_snapshot, stage, status, created_at, updated_at)
                VALUES (?, ?, 'm01', 'c01', '{}', 'problem', 'active', now(), now())""", id, member);
        return id;
    }

    private AssessmentProblem problem(UUID session, String member, int sequence) {
        return problems.saveAndFlush(new AssessmentProblem(session, member, sequence, "연간 감가상각비는?",
                "취득원가 600만 원, 내용연수 3년", "[\"k01\"]", "200만 원", "600만 원 ÷ 3년", "금액과 이유", 7));
    }

    private AssessmentAttempt attempt(AssessmentProblem problem, int no, String key) {
        return new AssessmentAttempt(problem, no, "200만 원", "600 ÷ 3", null, false, key, "a".repeat(64));
    }

    @Test
    void savesAndReadsBackAProblemAndAttemptPerMember() {
        var member = "member-" + UUID.randomUUID();
        var saved = problem(session(member), member, 1);
        attempts.saveAndFlush(attempt(saved, 1, "submit-1"));

        assertThat(problems.findByIdAndSessionIdAndMemberId(saved.id, saved.sessionId, member)).isPresent();
        assertThat(problems.findByIdAndSessionIdAndMemberId(saved.id, saved.sessionId, "someone-else")).isEmpty();
        assertThat(attempts.findByMemberIdAndRequestKey(member, "submit-1")).isPresent();
    }

    @Test
    void aSessionCannotHoldTheSameProblemSequenceTwice() {
        var member = "member-" + UUID.randomUUID();
        var session = session(member);
        problem(session, member, 1);
        assertThatThrownBy(() -> problem(session, member, 1)).isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void theSameRequestKeyCannotCreateASecondAttempt() {
        var member = "member-" + UUID.randomUUID();
        var saved = problem(session(member), member, 1);
        attempts.saveAndFlush(attempt(saved, 1, "submit-1"));
        assertThatThrownBy(() -> attempts.saveAndFlush(attempt(saved, 2, "submit-1")))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void anAttemptCannotPointAtAnotherMembersProblem() {
        var owner = "member-" + UUID.randomUUID();
        var saved = problem(session(owner), owner, 1);
        var forged = attempt(saved, 1, "submit-1");
        forged.memberId = "someone-else";
        assertThatThrownBy(() -> attempts.saveAndFlush(forged)).isInstanceOf(DataIntegrityViolationException.class);
    }
}