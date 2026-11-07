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
    private final ReteachReturns returns;

    AssessmentAttemptService(LearningSessionService sessions, LearningAssessmentAccess access,
                             AssessmentProblemRepository problems, AssessmentAttemptRepository attempts,
                             ObjectProvider<AnswerGrader> graders, ObjectMapper json, ReteachReturns returns) {
        this.sessions=sessions; this.access=access; this.problems=problems; this.attempts=attempts;
        this.graders=graders; this.json=json; this.returns=returns;
    }
    private record Fingerprint(UUID sessionId, UUID problemId, SubmitRequest request) {}

    @Transactional
    public AttemptView submit(String member, UUID sessionId, UUID problemId, String key, SubmitRequest request) {
        if (key == null || key.isBlank() || key.length() > 128 || key.indexOf('\0') >= 0) {
            throw new ApiException(422,"INVALID_IDEMPOTENCY_KEY","Idempotency-Key는 1~128자로 전달해야 합니다.");
        }
        var view=sessions.get(member,sessionId); // Ownership even for a historical replay.
        var problem=problems.findByIdAndSessionIdAndMemberId(problemId,sessionId,member)
                .orElseThrow(AssessmentProblemService::notFound);
        var hash=hash(json.writeValueAsString(new Fingerprint(sessionId,problemId,request)));
        var previous=attempts.findByMemberIdAndRequestKey(member,key);
        if (previous.isPresent()) return replay(previous.get(),hash,problem);

        // 재설명에서 이 문항으로 돌아왔다면 lockReady 전에 B의 복귀 표시를 먼저 소비한다.
        var returned=returns.pendingFor(view,problemId);
        if (returned != null) returns.acknowledge(member,sessionId,returned,view.version());
        var locked=access.lockReady(member,sessionId,null);
        previous=attempts.findByMemberIdAndRequestKey(member,key);
        if (previous.isPresent()) return replay(previous.get(),hash,problem);
        var history=attempts.findByProblemIdOrderByAttemptNo(problemId);
        UUID reteachId=null;
        if (!history.isEmpty() || problem.confirmedAt != null) {
            // 이미 푼 문항은 마지막 오답에서 재설명을 마치고 돌아온 경우에만 다시 제출할 수 있다. 첫 시도는 그대로 남는다.
            var last=history.isEmpty() ? null : history.getLast();
            boolean retry=returned != null && last != null && returned.originAttemptId().equals(last.id.toString())
                    && "wrong".equals(last.judgement) && problem.confirmedAt == null && problem.solutionViewedAt == null;
            if (!retry) {
                throw new ApiException(409,"FIRST_ATTEMPT_ALREADY_SUBMITTED","첫 답안은 보존됩니다. 새 답안 제출은 재설명 후 연결됩니다.");
            }
            reteachId=returned.reteachId();
        }
        if (returned != null) returns.rebaseline(member,sessionId,locked.version());
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
        var attempt=new AssessmentAttempt(problem,history.size()+1,request.answer(),request.work(),reteachId,
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