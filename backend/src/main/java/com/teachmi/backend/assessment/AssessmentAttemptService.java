package com.teachmi.backend.assessment;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
import com.teachmi.backend.auth.ApiException;
import com.teachmi.backend.learning.LearningAssessmentAccess;
import com.teachmi.backend.learning.LearningSessionService;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;
import static com.teachmi.backend.assessment.AttemptViews.*;

@Service
public class AssessmentAttemptService {
    private final LearningSessionService sessions;
    private final LearningAssessmentAccess access;
    private final AssessmentProblemRepository problems;
    private final AssessmentAttemptRepository attempts;
    private final ObjectProvider<AnswerGrader> graders;
    private final ObjectMapper json;

    AssessmentAttemptService(LearningSessionService sessions, LearningAssessmentAccess access,
            AssessmentProblemRepository problems, AssessmentAttemptRepository attempts,
            ObjectProvider<AnswerGrader> graders, ObjectMapper json) {
        this.sessions=sessions; this.access=access; this.problems=problems; this.attempts=attempts;
        this.graders=graders; this.json=json;
    }
    private record Fingerprint(UUID sessionId, UUID problemId, SubmitRequest request) {}

    @Transactional
    public AttemptView submit(String member, UUID sessionId, UUID problemId, String key, SubmitRequest request) {
        if (key == null || key.isBlank() || key.length() > 128 || key.indexOf('\0') >= 0) {
            throw new ApiException(422,"INVALID_IDEMPOTENCY_KEY","Idempotency-Key는 1~128자로 전달해야 합니다.");
        }
        sessions.get(member,sessionId); // Ownership even for a historical replay.
        var problem=problems.findByIdAndSessionIdAndMemberId(problemId,sessionId,member)
                .orElseThrow(AssessmentProblemService::notFound);
        var hash=hash(json.writeValueAsString(new Fingerprint(sessionId,problemId,request)));
        var previous=attempts.findByMemberIdAndRequestKey(member,key);
        if (previous.isPresent()) return replay(previous.get(),hash,problem);

        var locked=access.lockReady(member,sessionId,null);
        previous=attempts.findByMemberIdAndRequestKey(member,key);
        if (previous.isPresent()) return replay(previous.get(),hash,problem);
        if (!attempts.findByProblemIdOrderByAttemptNo(problemId).isEmpty() || problem.confirmedAt != null) {
            throw new ApiException(409,"FIRST_ATTEMPT_ALREADY_SUBMITTED","첫 답안은 보존됩니다. 새 답안 제출은 재설명 후 연결됩니다.");
        }
        if (problem.evidenceVersion != locked.version()) {
            throw new ApiException(409,"ASSESSMENT_EVIDENCE_STALE","문항이 사용한 학습 근거 버전이 변경됐습니다.");
        }
        var ordered=problems.findBySessionIdAndMemberIdOrderBySequence(sessionId,member);
        if (ordered.stream().anyMatch(p -> p.sequence < problem.sequence && p.confirmedAt == null)) {
            throw new ApiException(409,"PREVIOUS_PROBLEM_REQUIRED","앞 문항을 먼저 완료하세요.");
        }
        var grader=graders.getIfAvailable();
        if (grader == null) throw new ApiException(503,"ASSESSMENT_GRADING_PENDING","실제 답·풀이 채점기 연결이 필요합니다.");
        AnswerGrader.Grade grade;
        try {
            grade=grader.grade(new AnswerGrader.Input(problem.question,problem.expectedAnswer,
                    problem.gradingCriteria,request.answer(),request.work()));
        } catch (ApiException failure) {
            throw failure;
        } catch (RuntimeException failure) {
            throw new ApiException(502,"ASSESSMENT_GRADING_FAILED","채점에 실패했습니다. 같은 키와 답안으로 다시 시도하세요.");
        }
        if (grade == null || grade.judgement() == null
                || !List.of("correct","wrong","pending_review").contains(grade.judgement())
                || (grade.reteachFocus() != null && (grade.reteachFocus().isBlank() || grade.reteachFocus().length() > 2000))) {
            throw new ApiException(502,"INVALID_ASSESSMENT_GRADE","채점 결과를 확인할 수 없습니다.");
        }
        var attempt=new AssessmentAttempt(problem,1,request.answer(),request.work(),null,
                problem.solutionViewedAt != null,key,hash);
        attempt.judgement=grade.judgement();
        attempt.reteachFocus=grade.reteachFocus();
        attempt.gradedAt=Instant.now().truncatedTo(ChronoUnit.MICROS);
        attempts.saveAndFlush(attempt);
        if (grade.judgement().equals("correct")) {
            problem.confirmedAt=attempt.gradedAt;
            problems.saveAndFlush(problem);
        }
        return view(attempt,problem);
    }

    private AttemptView replay(AssessmentAttempt previous, String hash, AssessmentProblem problem) {
        if (!previous.requestHash.equals(hash)) {
            throw new ApiException(409,"IDEMPOTENCY_KEY_REUSED","같은 키에 다른 요청을 보냈습니다.");
        }
        if (previous.judgement == null) {
            throw new ApiException(503,"ASSESSMENT_GRADING_PENDING","저장된 답안의 채점이 아직 완료되지 않았습니다.");
        }
        return view(previous,problem);
    }

    private AttemptView view(AssessmentAttempt attempt, AssessmentProblem problem) {
        UUID next=null;
        List<String> actions;
        if (attempt.judgement.equals("correct")) {
            next=problems.findBySessionIdAndMemberIdOrderBySequence(attempt.sessionId,attempt.memberId).stream()
                    .filter(p -> p.sequence > problem.sequence).map(p -> p.id).findFirst().orElse(null);
            actions=List.of(next == null ? "finish_problems" : "next_problem");
        } else if (attempt.judgement.equals("wrong")) {
            actions=List.of("reteach","view_solution");
        } else actions=List.of("wait_review");
        return new AttemptView(attempt.id,attempt.problemId,attempt.attemptNo,attempt.judgement,actions,next);
    }

    private static String hash(String value) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8))); }
        catch (NoSuchAlgorithmException ex) { throw new IllegalStateException(ex); }
    }
}
